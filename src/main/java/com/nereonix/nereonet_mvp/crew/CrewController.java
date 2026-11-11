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
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.ShipRoleTakenException;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
@Controller
public class CrewController {

    private final CrewService crewService;
    private final ShipFrame shipFrame;
    private final ConfirmPages confirmPages;

    public CrewController(CrewService crewService, ShipFrame shipFrame, ConfirmPages confirmPages) {
        this.crewService = crewService;
        this.shipFrame = shipFrame;
        this.confirmPages = confirmPages;
    }

    // The "Add crew member" form is a modal on the list page.
    @GetMapping("/office/crew")
    public String list(@RequestParam(required = false) Long shipId, Model model) {
        model.addAttribute("addForm", new AddCrewForm());
        return showList(shipId, model);
    }

    // The Crew tab of the ship page: the same list for one ship, without the ship filter and column. Add crew member
    // opens the ship's invitation form (/office/ships/{id}/invitations/new), which never asks for the ship.
    @GetMapping("/office/ships/{id}/crew")
    public String shipCrew(@PathVariable Long id, Model model) {
        shipFrame.show(model, shipFrame.ship(id), ShipTab.CREW);
        model.addAttribute("crew", crewService.findCrew(id));
        model.addAttribute("invited", crewService.findInvited(id));
        return "crew/list";
    }

    // On errors the list page is shown again and the modal reopens with the errors.
    @PostMapping("/office/crew/new")
    public String add(@Valid @ModelAttribute("addForm") AddCrewForm form, BindingResult result,
                      @AuthenticationPrincipal CurrentUser currentUser, Model model,
                      RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showList(null, model);
        }
        Invitation invitation;
        try {
            invitation = crewService.addCrewMember(form.getFullName(), form.getEmail(), form.getRank(), form.getRole(),
                    form.getShipId(), currentUser.getUsername());
        } catch (EmailAlreadyRegisteredException e) {
            result.rejectValue("email", "invitation.error.userExists");
            return showList(null, model);
        } catch (ShipRoleTakenException e) {
            result.rejectValue("role", "user.error.shipRoleTaken");
            return showList(null, model);
        }
        redirectAttributes.addFlashAttribute("successMessage", "invitation.sent");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {invitation.getEmail()});
        return "redirect:/office/crew";
    }

    @GetMapping("/office/crew/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        User user = crewService.getCrewMember(id);
        model.addAttribute("form", EditCrewForm.of(user));
        return showEditForm(user, model);
    }

    @PostMapping("/office/crew/{id}/edit")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") EditCrewForm form,
                         BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        User user = crewService.getCrewMember(id);
        if (result.hasErrors()) {
            return showEditForm(user, model);
        }
        try {
            crewService.update(id, form.getFullName(), form.getRank(), form.getRole(),
                    form.getShipId());
        } catch (ShipRoleTakenException e) {
            result.rejectValue("role", "user.error.shipRoleTaken");
            return showEditForm(user, model);
        }
        redirectAttributes.addFlashAttribute("successMessage", "crew.updated");
        return "redirect:/office/crew";
    }

    // The confirmation pages: where Deactivate and Delete lead when js/confirm.js did not load (P10).
    @GetMapping("/office/users/{id}/deactivate")
    public String confirmDeactivate(@PathVariable Long id, @RequestParam(required = false) String tab, Model model) {
        User user = crewService.getUser(id);
        return withCrumbs(confirmPages.neutral()
                .title("user.deactivate.confirm.title", user.getFullName())
                .body("user.deactivate.confirm.body", user.getFullName())
                .confirmLabel("user.deactivate")
                .action("/office/users/" + id + "/deactivate", crewTab(user, tab) ? ShipTab.CREW.key() : null)
                .cancelUrl(listUrl(user, tab)), user, tab)
                .show(model);
    }

    @GetMapping("/office/users/{id}/delete")
    public String confirmDelete(@PathVariable Long id, @RequestParam(required = false) String tab, Model model) {
        User user = crewService.getUser(id);
        return withCrumbs(confirmPages.danger()
                .title("user.delete.confirm.title", user.getFullName())
                .body("user.delete.confirm.body", user.getFullName(), user.getEmail())
                .confirmLabel("user.delete")
                .action("/office/users/" + id + "/delete", crewTab(user, tab) ? ShipTab.CREW.key() : null)
                .cancelUrl(listUrl(user, tab)), user, tab)
                .show(model);
    }

    @PostMapping("/office/users/{id}/deactivate")
    public String deactivate(@PathVariable Long id, @RequestParam(required = false) String tab,
                             @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        User user = crewService.setEnabled(id, false, currentUser.getUsername());
        redirectAttributes.addFlashAttribute("successMessage", "user.deactivated");
        return redirectToList(user, tab);
    }

    @PostMapping("/office/users/{id}/activate")
    public String activate(@PathVariable Long id, @RequestParam(required = false) String tab,
                           @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        User user = crewService.setEnabled(id, true, currentUser.getUsername());
        redirectAttributes.addFlashAttribute("successMessage", "user.activated");
        return redirectToList(user, tab);
    }

    @PostMapping("/office/users/{id}/delete")
    public String delete(@PathVariable Long id, @RequestParam(required = false) String tab,
                         @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        User user = crewService.delete(id, currentUser.getUsername());
        redirectAttributes.addFlashAttribute("successMessage", "user.deleted");
        return redirectToList(user, tab);
    }

    // Back to the list the button was on: the ship page's Crew tab (tab=crew, sent by crew/parts :: userMenuItems
    // there), Crew or Office users (OfficeUserController).
    private static String redirectToList(User user, String tab) {
        return "redirect:" + listUrl(user, tab);
    }

    private static String listUrl(User user, String tab) {
        if (user.getRole() == Role.OFFICE_USER) {
            return "/office/users";
        }
        if (crewTab(user, tab)) {
            return ShipTab.CREW.url(user.getShip().getId());
        }
        return "/office/crew";
    }

    // Started from the Crew tab of the user's ship page.
    private static boolean crewTab(User user, String tab) {
        return user.getRole() != Role.OFFICE_USER && ShipTab.of(tab).orElse(null) == ShipTab.CREW
                && user.getShip() != null;
    }

    // The confirmation page's breadcrumbs: the list it was started from, then the user (crew members have a page).
    private static ConfirmPages.Builder withCrumbs(ConfirmPages.Builder page, User user, String tab) {
        if (user.getRole() == Role.OFFICE_USER) {
            return page.crumb("nav.officeUsers", "/office/users");
        }
        if (crewTab(user, tab)) {
            page.crumb("nav.ships", "/office/ships")
                    .crumbText(user.getShip().getName(), ShipTab.OVERVIEW.url(user.getShip().getId()))
                    .crumb("ship.tab.crew", ShipTab.CREW.url(user.getShip().getId()));
        } else {
            page.crumb("nav.crew", "/office/crew");
        }
        return page.crumbText(user.getFullName(), "/office/crew/" + user.getId() + "/edit");
    }

    private String showList(Long shipId, Model model) {
        model.addAttribute("crew", crewService.findCrew(shipId));
        model.addAttribute("invited", crewService.findInvited(shipId));
        model.addAttribute("ships", crewService.findAllShips());
        model.addAttribute("activeShips", crewService.findActiveShips());
        model.addAttribute("roles", CrewService.SHIP_ROLES);
        model.addAttribute("ranks", Rank.values());
        model.addAttribute("shipId", shipId);
        return "crew/list";
    }

    private String showEditForm(User user, Model model) {
        model.addAttribute("member", user);
        model.addAttribute("roles", CrewService.SHIP_ROLES);
        model.addAttribute("ranks", Rank.values());
        model.addAttribute("ships", crewService.findActiveShips());
        model.addAttribute("certificates", crewService.findCertificates(user.getId()));
        model.addAttribute("today", crewService.today());
        return "crew/edit";
    }
}
