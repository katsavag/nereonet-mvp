package com.nereonix.nereonet_mvp.stores;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.ai.CountExtractionException;
import com.nereonix.nereonet_mvp.core.ai.CountExtractor;
import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Extraction;
import com.nereonix.nereonet_mvp.core.ai.MistralSettings;
import com.nereonix.nereonet_mvp.core.speech.AssemblyAiSettings;
import com.nereonix.nereonet_mvp.core.speech.SpeechToTextClient;
import com.nereonix.nereonet_mvp.core.speech.SpeechToTextException;
import com.nereonix.nereonet_mvp.core.stock.StockService;
import com.nereonix.nereonet_mvp.core.stock.StockService.Level;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * Counting the store by voice (P57). propose: the recording becomes text (SpeechToTextClient, the vessel's item names
 * as key terms), the text becomes count lines (CountExtractor, only the text and the active provisions: id, name,
 * unit), and the lines become a Proposal for the cook to review. propose never touches the stock and keeps nothing:
 * the recording is only in memory for the call. confirm applies the reviewed rows through StockService.count, with the
 * note "Voice count", all or none. The Proposal lives in the cook's HTTP session between the two (StoresController).
 */
@Service
public class VoiceCountService {

    /** The note of every COUNT movement saved by voice. */
    public static final String NOTE = "Voice count";
    /** The longest recording accepted, in bytes (about 10 minutes of WebM/Opus; the page stops at 2). */
    public static final int MAX_AUDIO_BYTES = 10 * 1024 * 1024;

    /** Why there is no proposal, or why a confirm saved nothing. Messages: stores.voice.failure.<name>. */
    public enum Failure {
        /** Either provider has no key (and no fake): counting by voice is not offered. */
        NOT_CONFIGURED,
        /** No recording, an empty one, too big, or not audio. */
        NO_AUDIO,
        /** The vessel has no active provisions to count. */
        NO_ITEMS,
        TIMEOUT,
        PROVIDER_ERROR,
        /** No text, or nothing in it the extractor could read. */
        NOTHING_UNDERSTOOD,
        /** confirm: an item is no longer an active provisions item of the vessel. Nothing saved. */
        ITEM_GONE;

        public String messageKey() {
            return "stores.voice.failure." + name();
        }
    }

    public static class VoiceCountException extends RuntimeException {

        private final Failure failure;

        public VoiceCountException(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /** How sure the extractor was of a line: High from 0.8, Medium from 0.5, Low below. */
    public enum Confidence {
        HIGH, MEDIUM, LOW;

        public static Confidence of(double value) {
            return value >= 0.8 ? HIGH : value >= 0.5 ? MEDIUM : LOW;
        }

        public String labelKey() {
            return "stores.voice.confidence." + name();
        }
    }

    /** One line of the review: the item, the quantity heard, what the store expects, the words and the confidence. */
    public record ProposedLine(Long itemId, String name, String unit, BigDecimal counted, BigDecimal expected,
                               String words, Confidence confidence) implements Serializable {
    }

    /** What we heard: the text, one line per item counted, the phrases not matched to an item. */
    public record Proposal(String transcript, List<ProposedLine> lines, List<String> unmatched)
            implements Serializable {
    }

    /** One row the cook confirms: the item and the quantity counted (already a valid store quantity). */
    public record Row(Long itemId, BigDecimal quantity) {
    }

    private final UserRepository userRepository;
    private final StockService stockService;
    private final SpeechToTextClient speech;
    private final CountExtractor extractor;
    private final AssemblyAiSettings assemblyAi;
    private final MistralSettings mistral;

    public VoiceCountService(UserRepository userRepository, StockService stockService, SpeechToTextClient speech,
                             CountExtractor extractor, AssemblyAiSettings assemblyAi, MistralSettings mistral) {
        this.userRepository = userRepository;
        this.stockService = stockService;
        this.speech = speech;
        this.extractor = extractor;
        this.assemblyAi = assemblyAi;
        this.mistral = mistral;
    }

    /** Both providers have a key, or answer with their fake: the Count page offers the microphone. */
    public boolean configured() {
        return assemblyAi.configured() && mistral.configured();
    }

    public Proposal propose(String email, byte[] audio, String contentType) {
        return propose(userRepository.findByEmail(email).orElseThrow(), audio, contentType);
    }

    /**
     * The recording as a proposal, nothing saved. Not in a transaction: the providers may take seconds. The cook's
     * vessel's active provisions are read once, for the key terms, the extractor and the expected quantities.
     * VoiceCountException for every failure (Failure); AccessDeniedException when the user is not an enabled Chief
     * cook with a vessel.
     */
    public Proposal propose(User cook, byte[] audio, String contentType) {
        checkCook(cook);
        checkReady(audio, contentType);
        Map<Long, Level> levels = new LinkedHashMap<>();
        stockService.levels(cook.getShip()).forEach(level -> levels.put(level.item().getId(), level));
        if (levels.isEmpty()) {
            throw new VoiceCountException(Failure.NO_ITEMS);
        }
        List<CountExtractor.Item> items = levels.values().stream()
                .map(level -> new CountExtractor.Item(level.item().getId(), level.item().getName(),
                        level.item().getUnit()))
                .toList();
        Heard heard = listen(audio, contentType, items);
        String transcript = heard.transcript();
        Extraction extraction = heard.extraction();
        // Checked once more here, whatever the extractor: an item of the list, once, with a valid quantity.
        List<ProposedLine> lines = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (CountExtractor.Line line : extraction.lines()) {
            Level level = levels.get(line.itemId());
            if (level == null || !StockService.isValidQuantity(line.quantity()) || !seen.add(line.itemId())) {
                if (line.words() != null && !line.words().isBlank()) {
                    unmatched.add(line.words());
                }
                continue;
            }
            lines.add(new ProposedLine(line.itemId(), level.item().getName(), level.item().getUnit(),
                    line.quantity(), level.quantity(), line.words(), Confidence.of(line.confidence())));
        }
        unmatched.addAll(extraction.unmatched());
        if (lines.isEmpty() && unmatched.isEmpty()) {
            throw new VoiceCountException(Failure.NOTHING_UNDERSTOOD);
        }
        return new Proposal(transcript.strip(), List.copyOf(lines), List.copyOf(unmatched));
    }

    /**
     * The rows the cook confirmed, each through StockService.count with the note "Voice count", in one transaction:
     * all are saved or none. IllegalArgumentException for no rows, an item twice or a quantity that is not valid;
     * VoiceCountException(ITEM_GONE) when an item is no longer an active provisions item of the cook's vessel.
     * The number of items saved.
     */
    @Transactional
    public int confirm(String email, List<Row> rows) {
        User cook = userRepository.findByEmail(email).orElseThrow();
        checkCook(cook);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Nothing to confirm");
        }
        Set<Long> seen = new HashSet<>();
        for (Row row : rows) {
            if (!seen.add(row.itemId()) || !StockService.isValidQuantity(row.quantity())) {
                throw new IllegalArgumentException("Each item once, with a valid quantity");
            }
        }
        for (Row row : rows) {
            if (stockService.count(cook, row.itemId(), row.quantity(), NOTE).isEmpty()) {
                throw new VoiceCountException(Failure.ITEM_GONE);
            }
        }
        return rows.size();
    }

    /** What a recording said: the text, and the extractor's lines over the items given (unchecked). */
    public record Heard(String transcript, Extraction extraction) {
    }

    /**
     * P61: a recording against any list of items (the delivery count: the order's lines' items), the same two steps
     * as propose: the text (the item names as key terms), then CountExtractor over those items. Nothing checked
     * beyond the extractor's own checks, nothing saved. The caller checks who may record. Not in a transaction.
     * VoiceCountException: NOT_CONFIGURED, NO_AUDIO, NO_ITEMS (an empty list), TIMEOUT, PROVIDER_ERROR,
     * NOTHING_UNDERSTOOD (no text).
     */
    public Heard listen(User user, byte[] audio, String contentType, List<CountExtractor.Item> items) {
        if (user == null || !user.isEnabled() || user.getShip() == null) {
            throw new AccessDeniedException("Only a member of a vessel's crew records a count");
        }
        checkReady(audio, contentType);
        if (items.isEmpty()) {
            throw new VoiceCountException(Failure.NO_ITEMS);
        }
        return listen(audio, contentType, items);
    }

    private Heard listen(byte[] audio, String contentType, List<CountExtractor.Item> items) {
        try {
            String transcript = speech.transcribe(audio, contentType,
                    items.stream().map(CountExtractor.Item::name).toList());
            if (transcript == null || transcript.isBlank()) {
                throw new VoiceCountException(Failure.NOTHING_UNDERSTOOD);
            }
            return new Heard(transcript, extractor.extract(transcript, items));
        } catch (SpeechToTextException e) {
            throw new VoiceCountException(e.reason() == SpeechToTextException.Reason.TIMEOUT
                    ? Failure.TIMEOUT : Failure.PROVIDER_ERROR);
        } catch (CountExtractionException e) {
            throw new VoiceCountException(e.reason() == CountExtractionException.Reason.TIMEOUT
                    ? Failure.TIMEOUT : Failure.PROVIDER_ERROR);
        }
    }

    private void checkReady(byte[] audio, String contentType) {
        if (!configured()) {
            throw new VoiceCountException(Failure.NOT_CONFIGURED);
        }
        if (audio == null || audio.length == 0 || audio.length > MAX_AUDIO_BYTES || !isAudio(contentType)) {
            throw new VoiceCountException(Failure.NO_AUDIO);
        }
    }

    /** Recording types browsers make with MediaRecorder: audio/webm, audio/mp4, audio/ogg... (parameters ignored). */
    static boolean isAudio(String contentType) {
        return contentType != null && contentType.strip().toLowerCase(Locale.ROOT).startsWith("audio/");
    }

    private static void checkCook(User cook) {
        if (cook == null || cook.getRole() != Role.CHIEF_COOK || !cook.isEnabled() || cook.getShip() == null) {
            throw new AccessDeniedException("Only the Chief cook of the vessel counts its stores");
        }
    }
}
