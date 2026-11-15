package com.nereonix.nereonet_mvp.stores;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Use;
import com.nereonix.nereonet_mvp.core.stock.StockService.Level;
import com.nereonix.nereonet_mvp.stores.VoiceCountService.Failure;
import com.nereonix.nereonet_mvp.stores.VoiceCountService.Proposal;
import com.nereonix.nereonet_mvp.stores.VoiceCountService.ProposedLine;
import com.nereonix.nereonet_mvp.stores.VoiceCountService.Row;
import com.nereonix.nereonet_mvp.stores.VoiceCountService.VoiceCountException;

import jakarta.servlet.http.HttpSession;

// Counting the store by voice, the Chief cook only (SecurityConfig: /ship/stores/**).
//   POST /ship/stores/count/voice           the recording (multipart "audio") from js/stores-voice.js: transcribed and
//                                           read into a Proposal kept in the HTTP session (SESSION_KEY), replacing
//                                           any earlier one; the stock is not touched. The recording is not kept.
//   GET  /ship/stores/count/voice           "What we heard": the review of the session's proposal.
//   POST /ship/stores/count/voice/confirm   the reviewed rows, through StockService.count ("Voice count"), all or none.
//   POST /ship/stores/count/voice/discard   the proposal thrown away.
@Controller
public class VoiceCountController {

    static final String PATH = StoresController.PATH + "/count/voice";
    static final String SESSION_KEY = "storesVoiceProposal";

    /** The answer to js/stores-voice.js: where the review is, or the error and whether sending again may help. */
    public record Sent(String reviewUrl, String error, boolean retry) {
    }

    private final VoiceCountService voiceCountService;
    private final StoresService storesService;
    private final MessageSource messageSource;
    private final QuantityRules quantityRules;

    public VoiceCountController(VoiceCountService voiceCountService, StoresService storesService,
                                MessageSource messageSource, QuantityRules quantityRules) {
        this.quantityRules = quantityRules;
        this.voiceCountService = voiceCountService;
        this.storesService = storesService;
        this.messageSource = messageSource;
    }

    // With JavaScript (Accept: application/json): the review's address, or the failure in plain words.
    @PostMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Sent> sendInPlace(@AuthenticationPrincipal CurrentUser currentUser,
                                            @RequestParam(required = false) MultipartFile audio, HttpSession session) {
        try {
            session.setAttribute(SESSION_KEY, propose(currentUser, audio));
            return ResponseEntity.ok(new Sent(PATH, null, false));
        } catch (VoiceCountException e) {
            Failure failure = e.failure();
            HttpStatus status = switch (failure) {
                case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                case PROVIDER_ERROR -> HttpStatus.BAD_GATEWAY;
                case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.UNPROCESSABLE_ENTITY;
            };
            boolean retry = failure == Failure.TIMEOUT || failure == Failure.PROVIDER_ERROR;
            return ResponseEntity.status(status).body(new Sent(null, message(failure.messageKey()), retry));
        }
    }

    // The same as an ordinary form post: the review, or back to the Count page with the failure.
    @PostMapping(PATH)
    public String send(@AuthenticationPrincipal CurrentUser currentUser,
                       @RequestParam(required = false) MultipartFile audio, HttpSession session,
                       RedirectAttributes redirectAttributes) {
        try {
            session.setAttribute(SESSION_KEY, propose(currentUser, audio));
            return "redirect:" + PATH;
        } catch (VoiceCountException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.failure().messageKey());
            return "redirect:" + StoresController.PATH + "/count";
        }
    }

    @GetMapping(PATH)
    public String review(@AuthenticationPrincipal CurrentUser currentUser, HttpSession session, Model model) {
        Proposal proposal = proposal(session);
        if (proposal == null) {
            return "redirect:" + StoresController.PATH + "/count";
        }
        return reviewPage(currentUser, proposal, VoiceReviewForm.of(proposal), Map.of(), Map.of(), Set.of(),
                null, model);
    }

    // Every row kept and every phrase given an item, each with a valid quantity and each item once: all saved, the
    // proposal gone, back to the Count page with "Voice count saved: 3 items". Otherwise nothing is saved and the
    // review comes back with the errors and what was typed.
    @PostMapping(PATH + "/confirm")
    public String confirm(@AuthenticationPrincipal CurrentUser currentUser,
                          @ModelAttribute("form") VoiceReviewForm posted, HttpSession session, Model model,
                          RedirectAttributes redirectAttributes) {
        Proposal proposal = proposal(session);
        if (proposal == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "stores.voice.expired");
            return "redirect:" + StoresController.PATH + "/count";
        }
        VoiceReviewForm form = posted.fitTo(proposal);
        Map<Long, Level> items = new HashMap<>();
        storesService.levels(currentUser.getUsername()).forEach(level -> items.put(level.item().getId(), level));
        Map<Integer, String> rowErrors = new HashMap<>();
        Map<Integer, String> extraErrors = new HashMap<>();
        Set<Integer> extraItemErrors = new HashSet<>();
        Map<Long, String> names = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        String duplicate = null;
        for (int i = 0; i < proposal.lines().size(); i++) {
            ProposedLine line = proposal.lines().get(i);
            VoiceReviewForm.RowInput input = form.getRows().get(i);
            if (input.isRemoved()) {
                continue;
            }
            // The rule of the item's unit for a store count.
            Level known = items.get(line.itemId());
            QuantityRules.Check check = (known != null ? quantityRules.forItem(known.item(), Use.COUNT)
                    : quantityRules.forUnit(line.unit(), Use.COUNT)).check(input.getQuantity());
            if (!check.ok()) {
                rowErrors.put(i, messageSource.getMessage(check.message(), LocaleContextHolder.getLocale()));
                continue;
            }
            BigDecimal quantity = check.value();
            if (names.putIfAbsent(line.itemId(), line.name()) != null && duplicate == null) {
                duplicate = line.name();
            }
            rows.add(new Row(line.itemId(), quantity));
        }
        for (int j = 0; j < proposal.unmatched().size(); j++) {
            VoiceReviewForm.ExtraInput input = form.getExtras().get(j);
            if (input.getItemId() == null) {
                continue;
            }
            Level level = items.get(input.getItemId());
            if (level == null) {
                extraErrors.put(j, message("stores.voice.extra.itemError"));
                extraItemErrors.add(j);
                continue;
            }
            QuantityRules.Check check = quantityRules.forItem(level.item(), Use.COUNT).check(input.getQuantity());
            if (!check.ok()) {
                extraErrors.put(j, messageSource.getMessage(check.message(), LocaleContextHolder.getLocale()));
                continue;
            }
            BigDecimal quantity = check.value();
            if (names.putIfAbsent(level.item().getId(), level.item().getName()) != null && duplicate == null) {
                duplicate = level.item().getName();
            }
            rows.add(new Row(level.item().getId(), quantity));
        }
        String error = null;
        if (duplicate != null) {
            error = message("stores.voice.error.twice", duplicate);
        } else if (rowErrors.isEmpty() && extraErrors.isEmpty() && rows.isEmpty()) {
            error = message("stores.voice.error.nothing");
        }
        if (error != null || !rowErrors.isEmpty() || !extraErrors.isEmpty()) {
            return reviewPage(currentUser, proposal, form, rowErrors, extraErrors, extraItemErrors, error, model);
        }
        int saved;
        try {
            saved = voiceCountService.confirm(currentUser.getUsername(), rows);
        } catch (VoiceCountException e) {
            return reviewPage(currentUser, proposal, form, rowErrors, extraErrors, extraItemErrors,
                    message(e.failure().messageKey()),
                    model);
        }
        session.removeAttribute(SESSION_KEY);
        redirectAttributes.addFlashAttribute("successMessage", "stores.voice.saved");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {saved});
        return "redirect:" + StoresController.PATH + "/count";
    }

    @PostMapping(PATH + "/discard")
    public String discard(HttpSession session, RedirectAttributes redirectAttributes) {
        if (session.getAttribute(SESSION_KEY) != null) {
            session.removeAttribute(SESSION_KEY);
            redirectAttributes.addFlashAttribute("successMessage", "stores.voice.discarded");
        }
        return "redirect:" + StoresController.PATH + "/count";
    }

    private Proposal propose(CurrentUser currentUser, MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new VoiceCountException(Failure.NO_AUDIO);
        }
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw new VoiceCountException(Failure.NO_AUDIO);
        }
        return voiceCountService.propose(currentUser.getUsername(), bytes, audio.getContentType());
    }

    private String reviewPage(CurrentUser currentUser, Proposal proposal, VoiceReviewForm form,
                              Map<Integer, String> rowErrors, Map<Integer, String> extraErrors,
                              Set<Integer> extraItemErrors, String error, Model model) {
        model.addAttribute("ship", storesService.ship(currentUser.getUsername()));
        model.addAttribute("proposal", proposal);
        model.addAttribute("form", form);
        model.addAttribute("items", storesService.levels(currentUser.getUsername()));
        model.addAttribute("rowErrors", rowErrors);
        model.addAttribute("extraErrors", extraErrors);
        model.addAttribute("extraItemErrors", extraItemErrors);
        model.addAttribute("reviewError", error);
        return "stores/voice";
    }

    private static Proposal proposal(HttpSession session) {
        return session.getAttribute(SESSION_KEY) instanceof Proposal proposal ? proposal : null;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
