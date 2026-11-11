package com.nereonix.nereonet_mvp.invitation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.invitation.EmailAlreadyRegisteredException;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.ShipRoleTakenException;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
@Controller
public class InvitationController {

    private static final List<Role> CREW_ROLES = List.of(Role.MASTER, Role.CHIEF_COOK, Role.CREW_MEMBER);

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    // Office users

    // The invite form is a modal on the list page.
    @GetMapping("/office/invitations")
    public String list(@ModelAttribute("filter") InvitationFilterForm filter, Model model) {
        model.addAttribute("form", new OfficeInvitationForm());
        return showList(filter, model);
    }

    // On errors the list page is shown again and the modal reopens with the errors.
    @PostMapping("/office/invitations")
    public String inviteOfficeUser(@Valid @ModelAttribute("form") OfficeInvitationForm form, BindingResult result,
                                   @AuthenticationPrincipal CurrentUser user, Model model,
                                   RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showList(new InvitationFilterForm(), model);
        }
        try {
            invitationService.invite(form.getEmail(), form.getFullName(), Role.OFFICE_USER, null, null,
                    user.getUsername());
        } catch (EmailAlreadyRegisteredException e) {
            result.rejectValue("email", "invitation.error.userExists");
            return showList(new InvitationFilterForm(), model);
        }
        redirectAttributes.addFlashAttribute("successMessage", "invitation.sent");
        return "redirect:/office/invitations";
    }

    @PostMapping("/office/invitations/{id}/resend")
    public String resend(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        boolean done = invitationService.resend(id);
        redirectAttributes.addFlashAttribute(done ? "successMessage" : "errorMessage",
                done ? "invitation.resent" : "invitation.error.notPending");
        return "redirect:/office/invitations";
    }

    @PostMapping("/office/invitations/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        boolean done = invitationService.cancel(id);
        redirectAttributes.addFlashAttribute(done ? "successMessage" : "errorMessage",
                done ? "invitation.cancelled" : "invitation.error.notPending");
        return "redirect:/office/invitations";
    }

    // Crew members of one ship

    @GetMapping("/office/ships/{shipId}/invitations/new")
    public String crewForm(@PathVariable Long shipId, Model model) {
        model.addAttribute("form", new CrewInvitationForm());
        return showCrewForm(findShip(shipId), model);
    }

    @PostMapping("/office/ships/{shipId}/invitations/new")
    public String inviteCrewMember(@PathVariable Long shipId,
                                   @Valid @ModelAttribute("form") CrewInvitationForm form, BindingResult result,
                                   @AuthenticationPrincipal CurrentUser user, Model model,
                                   RedirectAttributes redirectAttributes) {
        Ship ship = findShip(shipId);
        if (result.hasErrors()) {
            return showCrewForm(ship, model);
        }
        try {
            invitationService.invite(form.getEmail(), form.getFullName(), form.getRole(), ship.getId(),
                    form.getRank(), user.getUsername());
        } catch (EmailAlreadyRegisteredException e) {
            result.rejectValue("email", "invitation.error.userExists");
            return showCrewForm(ship, model);
        } catch (ShipRoleTakenException e) {
            result.rejectValue("role", "user.error.shipRoleTaken");
            return showCrewForm(ship, model);
        }
        redirectAttributes.addFlashAttribute("successMessage", "invitation.sent");
        // Back to an empty form until the ship page exists.
        return "redirect:/office/ships/" + ship.getId() + "/invitations/new";
    }

    private String showList(InvitationFilterForm filter, Model model) {
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", Invitation.DisplayStatus.values());
        model.addAttribute("invitations", invitationService.findOfficeInvitations(filter.getStatus()));
        return "invitation/list";
    }

    private Ship findShip(Long shipId) {
        return invitationService.findActiveShip(shipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String showCrewForm(Ship ship, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("roles", CREW_ROLES);
        model.addAttribute("ranks", Rank.values());
        return "invitation/crew-form";
    }
}
