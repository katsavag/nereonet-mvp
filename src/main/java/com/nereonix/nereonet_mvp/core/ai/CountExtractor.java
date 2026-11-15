package com.nereonix.nereonet_mvp.core.ai;

import java.math.BigDecimal;
import java.util.List;

/**
 * Turns what the Chief cook said while counting the store into count lines, called by the server only
 * (VoiceCountService). ClaudeCountExtractor is the real one; FakeCountExtractor answers in dev (app.claude.fake) and
 * in the tests. Only the text and the item list are sent anywhere: never a name of the crew or anything else.
 */
public interface CountExtractor {

    /** One item the cook may have counted: an active provisions item of their vessel. */
    record Item(Long id, String name, String unit) {
    }

    /** One thing counted: an item of the list, the quantity as spoken (a valid store quantity), the words it came
     *  from and how sure the extractor is, from 0 to 1. */
    record Line(Long itemId, BigDecimal quantity, String words, double confidence) {
    }

    /** lines: at most one per item; unmatched: the phrases that name nothing of the list, or not clearly. */
    record Extraction(List<Line> lines, List<String> unmatched) {

        public static Extraction nothing() {
            return new Extraction(List.of(), List.of());
        }

        public boolean isEmpty() {
            return lines.isEmpty() && unmatched.isEmpty();
        }
    }

    /**
     * The lines of transcript, each naming an item of items. Never null; Extraction.nothing() when nothing was
     * understood (including an answer that is not valid JSON). CountExtractionException when the provider fails or
     * takes longer than its timeout.
     */
    Extraction extract(String transcript, List<Item> items);
}
