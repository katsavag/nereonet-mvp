package com.nereonix.nereonet_mvp.crew;

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

import com.nereonix.nereonet_mvp.core.confirm.ConfirmPages;
import com.nereonix.nereonet_mvp.core.invitation.EmailAlreadyRegisteredException;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationService;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

/**
 * The Office users page (P07): office users and pending office invitations in one list, with "Invite office user".
 * The former Office invitations page (/office/invitations) redirects here; its POST URLs stay.
 * Deactivate, activate and delete are in CrewController (shared with the crew list).
 * /office/** is limited to OFFICE_USER in SecurityConfig.
 */
@Controller
public class OfficeUserController {

    private static final String LIST = "redirect:/office/users";

    // The columns that sort (P19), the default first.
    private static final ListSort[] SORTS = {ListSort.asc("name"), ListSort.asc("email"), ListSort.asc("status")};

    private final CrewService crewService;
    private final InvitationService invitationService;
    private final ConfirmPages confirmPages;

    public OfficeUserController(CrewService crewService, InvitationService invitationService,
                                ConfirmPages confirmPages) {
        this.crewService = crewService;
        this.invitationService = invitationService;
        this.confirmPages = confirmPages;
    }

    // The invite form is a modal on the list page. status: the Status filter (CrewStatus key); listRequest: search,
    // sort and page (P19).
    @GetMapping("/office/users")
    public String list(@RequestParam(required = false) String status, @ModelAttribute ListRequest listRequest,
                       Model model) {
        model.addAttribute("form", new OfficeInvitationForm());
        return showList(CrewStatus.of(status).orElse(null), listRequest, model);
    }

    // Old bookmarks and links in sent emails.
    @GetMapping("/office/invitations")
    public String invitations() {
        return LIST;
    }

    // On errors the list page is shown again and the modal reopens with the errors.
    @PostMapping("/office/invitations")
    public String invite(@Valid @ModelAttribute("form") OfficeInvitationForm form, BindingResult result,
                         @AuthenticationPrincipal CurrentUser user, Model model,
                         RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showList(null, ListRequest.EMPTY, model);
        }
        Invitation invitation;
        try {
            invitation = invitationService.invite(form.getEmail(), form.getFullName(), Role.OFFICE_USER, null, null,
                    user.getUsername());
        } catch (EmailAlreadyRegisteredException e) {
            result.rejectValue("email", "invitation.error.userExists");
            return showList(null, ListRequest.EMPTY, model);
        }
        // "Invitation sent to <address>.": there is no "Repeat email" field, so the office sees where it went.
        redirectAttributes.addFlashAttribute("successMessage", "invitation.sent");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {invitation.getEmail()});
        return LIST;
    }

    // Office and crew invitations. Each returns to the list it was started from (CrewListReturn): Office users, the
    // Overview's "Needs attention" list (returnTo=overview, P09), the Crew list with its filters (returnTo=crew, P17) or
    // a ship page's Crew tab (tab=crew). Never a redirect to a URL taken from the request.
    @PostMapping("/office/invitations/{id}/resend")
    public String resend(@PathVariable Long id, @RequestParam(required = false) String returnTo,
                         @RequestParam(required = false) String tab, @RequestParam(required = false) Long shipId,
                         @RequestParam(required = false) String status, @ModelAttribute ListRequest listRequest,
                         RedirectAttributes redirectAttributes) {
        boolean done = invitationService.resend(id);
        redirectAttributes.addFlashAttribute(done ? "successMessage" : "errorMessage",
                done ? "invitation.resent" : "invitation.error.notPending");
        return "redirect:" + CrewListReturn.of(returnTo, tab, shipId, status, listRequest).url();
    }

    // The confirmation page: where Cancel leads when js/confirm.js did not load (P10). It keeps where to return to, as
    // fields of its form and in its breadcrumbs. An invitation that is no longer pending goes back to the list with the
    // same message as cancelling it.
    @GetMapping("/office/invitations/{id}/cancel")
    public String confirmCancel(@PathVariable Long id, @RequestParam(required = false) String returnTo,
                                @RequestParam(required = false) String tab, @RequestParam(required = false) Long shipId,
                                @RequestParam(required = false) String status, @ModelAttribute ListRequest listRequest,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        CrewListReturn back = CrewListReturn.of(returnTo, tab, shipId, status, listRequest);
        Invitation invitation = invitationService.findPending(id).orElse(null);
        if (invitation == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "invitation.error.notPending");
            return "redirect:" + back.url();
        }
        ConfirmPages.Builder page = confirmPages.danger()
                .title("invitation.cancel.confirm.title", invitation.getFullName())
                .body("invitation.cancel.confirm.body", invitation.getEmail())
                .confirmLabel("invitation.cancel.confirm.submit")
                .dismissLabel("invitation.cancel.confirm.keep")
                .action("/office/invitations/" + id + "/cancel", null)
                .fields(back.params())
                .cancelUrl(back.url());
        switch (back.kind()) {
            case SHIP_TAB -> page.crumb("nav.ships", "/office/ships")
                    .crumbText(invitation.getShip() == null ? "" : invitation.getShip().getName(),
                            ShipTab.OVERVIEW.url(back.shipId()))
                    .crumb("ship.tab.crew", back.url());
            case CREW -> page.crumb("nav.crew", back.url());
            case OVERVIEW -> page.crumb("nav.overview", back.url());
            case OFFICE_USERS -> page.crumb("nav.officeUsers", back.url());
        }
        return page.show(model);
    }

    @PostMapping("/office/invitations/{id}/cancel")
    public String cancel(@PathVariable Long id, @RequestParam(required = false) String returnTo,
                         @RequestParam(required = false) String tab, @RequestParam(required = false) Long shipId,
                         @RequestParam(required = false) String status, @ModelAttribute ListRequest listRequest,
                         RedirectAttributes redirectAttributes) {
        boolean done = invitationService.cancel(id);
        redirectAttributes.addFlashAttribute(done ? "successMessage" : "errorMessage",
                done ? "invitation.cancelled" : "invitation.error.notPending");
        return "redirect:" + CrewListReturn.of(returnTo, tab, shipId, status, listRequest).url();
    }

    // Model: list (ListQuery), people (one page of PeopleQueries.Row), the status filter.
    private String showList(CrewStatus status, ListRequest request, Model model) {
        ListQuery list = CrewStatus.filter(ListQuery.of("/office/users", request, SORTS), status);
        model.addAttribute("list", list);
        model.addAttribute("people", crewService.findOfficeUsers(status, list));
        model.addAttribute("status", status == null ? null : status.key());
        model.addAttribute("statuses", CrewStatus.values());
        // Resend and Cancel on an invitation come back here, as it is.
        model.addAttribute("listParams", CrewListReturn.officeUsers(status, list.getRequest()).params());
        return "crew/office-users";
    }
}
