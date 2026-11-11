package com.nereonix.nereonet_mvp.crew;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.invitation.EmailAlreadyRegisteredException;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Role;

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

    private final CrewService crewService;
    private final InvitationService invitationService;

    public OfficeUserController(CrewService crewService, InvitationService invitationService) {
        this.crewService = crewService;
        this.invitationService = invitationService;
    }

    // The invite form is a modal on the list page.
    @GetMapping("/office/users")
    public String list(Model model) {
        model.addAttribute("form", new OfficeInvitationForm());
        return showList(model);
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
            return showList(model);
        }
        Invitation invitation;
        try {
            invitation = invitationService.invite(form.getEmail(), form.getFullName(), Role.OFFICE_USER, null, null,
                    user.getUsername());
        } catch (EmailAlreadyRegisteredException e) {
            result.rejectValue("email", "invitation.error.userExists");
            return showList(model);
        }
        // "Invitation sent to <address>.": there is no "Repeat email" field, so the office sees where it went.
        redirectAttributes.addFlashAttribute("successMessage", "invitation.sent");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {invitation.getEmail()});
        return LIST;
    }

    @PostMapping("/office/invitations/{id}/resend")
    public String resend(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        boolean done = invitationService.resend(id);
        redirectAttributes.addFlashAttribute(done ? "successMessage" : "errorMessage",
                done ? "invitation.resent" : "invitation.error.notPending");
        return LIST;
    }

    @PostMapping("/office/invitations/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        boolean done = invitationService.cancel(id);
        redirectAttributes.addFlashAttribute(done ? "successMessage" : "errorMessage",
                done ? "invitation.cancelled" : "invitation.error.notPending");
        return LIST;
    }

    private String showList(Model model) {
        model.addAttribute("users", crewService.findOfficeUsers());
        model.addAttribute("invited", invitationService.findPendingOfficeInvitations());
        return "crew/office-users";
    }
}
