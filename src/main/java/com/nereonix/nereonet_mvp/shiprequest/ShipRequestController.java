package com.nereonix.nereonet_mvp.shiprequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.confirm.ConfirmPages;
import com.nereonix.nereonet_mvp.core.request.RequestService.Outcome;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.shiprequest.ShipRequestService.Read;

// The Chief cook's and crew's own requests to the Master: the list, one request, a new one, and while it waits
// for the Master, editing and cancelling it. SecurityConfig limits /ship/requests/** to CHIEF_COOK and CREW_MEMBER
// (the Master decides on /ship/approvals and gets 403 here). Anyone else's request is a 404. English only, as every
// crew screen. The form works without JavaScript: Add and Remove (the cook's new items) post the form and show it
// again with everything typed kept; js/request-form.js adds the search and sends Add and Remove with fetch, taking
// the new-items card out of the page that comes back.
@Controller
public class ShipRequestController {

    static final String PATH = "/ship/requests";

    // A ship has a few hundred items at most; Spring's default (256 lines in a posted list) is too few.
    private static final int MAX_LINES = 2000;

    private final ShipRequestService requestService;
    private final ConfirmPages confirmPages;

    public ShipRequestController(ShipRequestService requestService, ConfirmPages confirmPages) {
        this.requestService = requestService;
        this.confirmPages = confirmPages;
    }

    @InitBinder("form")
    void initBinder(WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(MAX_LINES);
    }

    // status: waiting, approved or declined; anything else is All.
    @GetMapping(PATH)
    public String list(@AuthenticationPrincipal CurrentUser currentUser,
                       @RequestParam(required = false) String status, Model model) {
        model.addAttribute("requests", requestService.list(currentUser.getUsername(), RequestFilter.of(status)));
        return "shiprequest/list";
    }

    @GetMapping(PATH + "/{id}")
    public String detail(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        model.addAttribute("detail", requestService.detail(currentUser.getUsername(), id));
        return "shiprequest/detail";
    }

    @GetMapping(PATH + "/new")
    public String newForm(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        RequestForm form = requestService.newForm(currentUser.getUsername());
        return show(currentUser, null, form, new BeanPropertyBindingResult(form, "form"), model);
    }

    // add: "Add to request" (the cook's new item); remove: "Remove" on a new item, its index; neither: Send.
    @PostMapping(PATH + "/new")
    public String create(@AuthenticationPrincipal CurrentUser currentUser,
                         @ModelAttribute("form") RequestForm posted, BindingResult ignored,
                         @RequestParam(required = false) String add, @RequestParam(required = false) Integer remove,
                         Model model, RedirectAttributes redirectAttributes) {
        return post(currentUser, null, posted, add, remove, model, redirectAttributes);
    }

    // Once the Master decided (or it was cancelled) a request is read only: back to it, saying so.
    @GetMapping(PATH + "/{id}/edit")
    public String editForm(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model,
                           RedirectAttributes redirectAttributes) {
        if (!requestService.isPending(currentUser.getUsername(), id)) {
            return notPending(id, redirectAttributes);
        }
        RequestForm form = requestService.editForm(currentUser.getUsername(), id);
        return show(currentUser, id, form, new BeanPropertyBindingResult(form, "form"), model);
    }

    @PostMapping(PATH + "/{id}/edit")
    public String edit(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                       @ModelAttribute("form") RequestForm posted, BindingResult ignored,
                       @RequestParam(required = false) String add, @RequestParam(required = false) Integer remove,
                       Model model, RedirectAttributes redirectAttributes) {
        if (!requestService.isPending(currentUser.getUsername(), id)) {
            return notPending(id, redirectAttributes);
        }
        return post(currentUser, id, posted, add, remove, model, redirectAttributes);
    }

    // The confirmation page: where Cancel request leads when js/confirm.js did not load.
    @GetMapping(PATH + "/{id}/cancel")
    public String confirmCancel(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model,
                                RedirectAttributes redirectAttributes) {
        if (!requestService.isPending(currentUser.getUsername(), id)) {
            return notPending(id, redirectAttributes);
        }
        return confirmPages.danger()
                .title("request.cancel.confirm.title", id)
                .body("request.cancel.confirm.body")
                .confirmLabel("request.cancel")
                .dismissLabel("request.cancel.keep")
                .action(PATH + "/" + id + "/cancel", null)
                .cancelUrl(PATH + "/" + id)
                .crumb("nav.requests", PATH)
                .show(model);
    }

    @PostMapping(PATH + "/{id}/cancel")
    public String cancel(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         RedirectAttributes redirectAttributes) {
        return afterChange(requestService.cancel(currentUser.getUsername(), id), id, "request.cancelled",
                redirectAttributes);
    }

    private String post(CurrentUser currentUser, Long id, RequestForm posted, String add, Integer remove, Model model,
                        RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        Read read = requestService.read(email, posted, id);
        RequestForm form = read.form();
        BindingResult errors = new BeanPropertyBindingResult(form, "form");
        if (remove != null) {
            requestService.removeNewItem(form, remove);
            return show(currentUser, id, form, errors, model);
        }
        if (add != null) {
            requestService.addNewItem(email, form, id, errors);
            return show(currentUser, id, form, errors, model);
        }
        if (id == null) {
            Long created = requestService.submit(email, read, errors);
            if (created == null) {
                return show(currentUser, null, form, errors, model);
            }
            redirectAttributes.addFlashAttribute("successMessage", "request.sent");
            return "redirect:" + PATH + "/" + created;
        }
        Outcome outcome = requestService.update(email, id, read, errors);
        if (outcome == null) {
            return show(currentUser, id, form, errors, model);
        }
        return afterChange(outcome, id, "request.saved", redirectAttributes);
    }

    private String show(CurrentUser currentUser, Long id, RequestForm form, BindingResult errors, Model model) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", errors);
        model.addAttribute("picker", requestService.picker(currentUser.getUsername(), form, id));
        return "shiprequest/form";
    }

    // Done: back to the request with a message. Decided or cancelled meanwhile (another tab, the Master at the same
    // moment): nothing changed, back to it saying so.
    private static String afterChange(Outcome outcome, Long id, String successKey,
                                      RedirectAttributes redirectAttributes) {
        return switch (outcome) {
            case DONE -> {
                redirectAttributes.addFlashAttribute("successMessage", successKey);
                yield "redirect:" + PATH + "/" + id;
            }
            case NOT_PENDING -> notPending(id, redirectAttributes);
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    private static String notPending(Long id, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", "request.notPending");
        return "redirect:" + PATH + "/" + id;
    }
}
