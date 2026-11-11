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
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// Crew invitations for one ship: Add crew member on the ship page's Crew tab. Office invitations are on the Office users page (crew/OfficeUserController).
// /office/** is limited to OFFICE_USER in SecurityConfig.
@Controller
public class InvitationController {

    private static final List<Role> CREW_ROLES = List.of(Role.MASTER, Role.CHIEF_COOK, Role.CREW_MEMBER);

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
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
        Invitation invitation;
        try {
            invitation = invitationService.invite(form.getEmail(), form.getFullName(), form.getRole(), ship.getId(),
                    form.getRank(), user.getUsername());
        } catch (EmailAlreadyRegisteredException e) {
            result.rejectValue("email", "invitation.error.userExists");
            return showCrewForm(ship, model);
        } catch (ShipRoleTakenException e) {
            result.rejectValue("role", "user.error.shipRoleTaken");
            return showCrewForm(ship, model);
        }
        sent(invitation, redirectAttributes);
        // Back to the ship page's Crew tab, where the new member is listed as "Invited".
        return "redirect:" + ShipTab.CREW.url(ship.getId());
    }

    // "Invitation sent to <address>.": there is no "Repeat email" field, so the office sees where it went.
    private static void sent(Invitation invitation, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("successMessage", "invitation.sent");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {invitation.getEmail()});
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
