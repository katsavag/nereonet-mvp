package com.nereonix.nereonet_mvp.alert;

import java.time.LocalDate;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.alert.AlertKey;
import com.nereonix.nereonet_mvp.core.alert.AlertSnooze;
import com.nereonix.nereonet_mvp.core.alert.SnoozeReason;
import com.nereonix.nereonet_mvp.core.alert.SnoozeService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.validation.Valid;

/**
 * Snoozing alerts (P43), office users only (/office/** in SecurityConfig):
 *   GET  /office/alerts/snooze?items=KIND:ID&amp;...&amp;returnTo=...   the snooze page: the items reviewed, the form
 *   POST /office/alerts/snooze                                      snoozes them, back to returnTo
 *   POST /office/alerts/snooze/{id}/undo                            undoes one snooze, back to returnTo
 * The lists send their checkboxes to the GET (items), so "Snooze selected" works without JavaScript; a row's Snooze
 * is the same link with one item. returnTo is an office page of this app, else the Overview.
 */
@Controller
public class AlertController {

    static final String PATH = "/office/alerts/snooze";

    private final AlertService alertService;
    private final SnoozeService snoozeService;
    private final UserRepository userRepository;

    public AlertController(AlertService alertService, SnoozeService snoozeService, UserRepository userRepository) {
        this.alertService = alertService;
        this.snoozeService = snoozeService;
        this.userRepository = userRepository;
    }

    @GetMapping(PATH)
    public String form(@RequestParam(required = false) List<String> items,
                       @RequestParam(required = false) String returnTo, Model model,
                       RedirectAttributes redirectAttributes) {
        SnoozeForm form = new SnoozeForm();
        form.setItems(items);
        form.setReturnTo(AlertReturn.clean(returnTo));
        form.setDuration(SnoozeForm.Duration.DAYS_30);
        List<AlertView> views = alertService.describe(AlertKey.parseAll(items));
        if (views.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "snooze.error.nothing");
            return "redirect:" + form.getReturnTo();
        }
        return show(form, views, model);
    }

    @PostMapping(PATH)
    public String snooze(@Valid @ModelAttribute("form") SnoozeForm form, BindingResult result,
                         @AuthenticationPrincipal CurrentUser currentUser, Model model,
                         RedirectAttributes redirectAttributes) {
        form.setReturnTo(AlertReturn.clean(form.getReturnTo()));
        List<AlertView> views = alertService.describe(AlertKey.parseAll(form.getItems()));
        if (views.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "snooze.error.nothing");
            return "redirect:" + form.getReturnTo();
        }
        LocalDate today = SnoozeService.today();
        check(form, views, result, today);
        if (result.hasErrors()) {
            return show(form, views, model);
        }
        User by = userRepository.findById(currentUser.getId()).orElseThrow();
        for (AlertView view : views) {
            snoozeService.snooze(view.alert(), view.ship(), view.auditTitle(), form.getReason(),
                    form.getReason() == SnoozeReason.OTHER ? form.getNote() : form.getNote(),
                    until(form, view, today), by);
        }
        redirectAttributes.addFlashAttribute("successMessage", "snooze.done");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {views.size()});
        return "redirect:" + form.getReturnTo();
    }

    @PostMapping(PATH + "/{id}/undo")
    public String undo(@PathVariable Long id, @RequestParam(required = false) String returnTo,
                       @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        AlertSnooze snooze = snoozeService.get(id);
        User by = userRepository.findById(currentUser.getId()).orElseThrow();
        String title = alertService.describe(List.of(snooze.key())).stream().map(AlertView::auditTitle).findFirst()
                .orElse(snooze.getKind().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ": " + snooze.getSubjectId());
        snoozeService.undo(id, title, by);
        redirectAttributes.addFlashAttribute("successMessage", "snooze.undone");
        return "redirect:" + AlertReturn.clean(returnTo);
    }

    // The rules the form cannot check on its own: Other needs a note; a date must be after today; an expired item
    // (or missing documents) for 30 days at most and never until it expires; until it expires only with a date ahead.
    private static void check(SnoozeForm form, List<AlertView> views, BindingResult result, LocalDate today) {
        if (form.getReason() == null) {
            result.rejectValue("reason", "snooze.error.reason");
        } else if (form.getReason() == SnoozeReason.OTHER && form.getNote() == null) {
            result.rejectValue("note", "snooze.error.note");
        }
        if (form.getDuration() == null) {
            result.rejectValue("duration", "snooze.error.duration");
            return;
        }
        boolean anyExpired = views.stream().anyMatch(AlertView::expired);
        switch (form.getDuration()) {
            case DATE -> {
                if (form.getUntilDate() == null) {
                    if (!result.hasFieldErrors("untilDate")) {
                        result.rejectValue("untilDate", "snooze.error.date");
                    }
                } else if (!form.getUntilDate().isAfter(today)) {
                    result.rejectValue("untilDate", "snooze.error.dateFuture");
                } else if (anyExpired && form.getUntilDate().isAfter(today.plusDays(SnoozeService.EXPIRED_MAX_DAYS))) {
                    result.rejectValue("untilDate", "snooze.error.expiredMax");
                }
            }
            case UNTIL_EXPIRY -> {
                if (views.stream().anyMatch(v -> !v.canWaitForDate())) {
                    result.rejectValue("duration", anyExpired ? "snooze.error.expiredMax" : "snooze.error.noExpiry");
                }
            }
            default -> { }
        }
    }

    private static LocalDate until(SnoozeForm form, AlertView view, LocalDate today) {
        return switch (form.getDuration()) {
            case DAYS_7 -> today.plusDays(7);
            case DAYS_30 -> today.plusDays(30);
            case DATE -> form.getUntilDate();
            case UNTIL_EXPIRY -> view.date();
        };
    }

    private static String show(SnoozeForm form, List<AlertView> views, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("items", views);
        model.addAttribute("reasons", SnoozeReason.values());
        model.addAttribute("durations", SnoozeForm.Duration.values());
        model.addAttribute("anyExpired", views.stream().anyMatch(AlertView::expired));
        model.addAttribute("untilExpiryAllowed", views.stream().allMatch(AlertView::canWaitForDate));
        model.addAttribute("backLabelKey", AlertReturn.labelKey(form.getReturnTo()));
        return "alert/snooze";
    }
}
