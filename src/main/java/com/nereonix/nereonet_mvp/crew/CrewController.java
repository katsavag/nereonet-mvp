package com.nereonix.nereonet_mvp.crew;

import java.util.List;
import java.util.Objects;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
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
import com.nereonix.nereonet_mvp.core.export.XlsExport;
import com.nereonix.nereonet_mvp.core.invitation.EmailAlreadyRegisteredException;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.profile.PhotoResponse;
import com.nereonix.nereonet_mvp.core.profile.ProfileService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.ShipRoleTakenException;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;
import com.nereonix.nereonet_mvp.requirement.ComplianceExport;
import com.nereonix.nereonet_mvp.requirement.ComplianceService;
import com.nereonix.nereonet_mvp.requirement.RequirementRules;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
@Controller
public class CrewController {

    private final CrewService crewService;
    private final ShipFrame shipFrame;
    private final ConfirmPages confirmPages;
    private final ProfileService profileService;
    private final ComplianceService complianceService;

    // ?documents=missing: the Crew list's "Missing documents" filter (P42).
    private static final String MISSING = "missing";

    public CrewController(CrewService crewService, ShipFrame shipFrame, ConfirmPages confirmPages,
                          ProfileService profileService, ComplianceService complianceService) {
        this.crewService = crewService;
        this.shipFrame = shipFrame;
        this.confirmPages = confirmPages;
        this.profileService = profileService;
        this.complianceService = complianceService;
    }

    // The Crew list's columns that sort (P19), the default first; the Crew tab has no Ship column.
    private static final ListSort[] FLEET_SORTS =
            {ListSort.asc("name"), ListSort.asc("email"), ListSort.asc("rank"), ListSort.asc("ship"), ListSort.asc("status"),
                    ListSort.desc("documents")};
    private static final ListSort[] TAB_SORTS =
            {ListSort.asc("name"), ListSort.asc("email"), ListSort.asc("rank"), ListSort.asc("status"),
                    ListSort.desc("documents")};

    // The "Add crew member" form is a modal on the list page. status: the Status filter (CrewStatus key); an unknown
    // value shows every status. documents=missing: only people missing a required document (P42). listRequest:
    // search, sort and page (P19, core/list/ListQuery).
    @GetMapping("/office/crew")
    public String list(@RequestParam(required = false) Long shipId, @RequestParam(required = false) String status,
                       @RequestParam(required = false) String documents, @ModelAttribute ListRequest listRequest,
                       Model model) {
        model.addAttribute("addForm", new AddCrewForm());
        return showList(shipId, CrewStatus.of(status).orElse(null), MISSING.equals(documents), listRequest, model);
    }

    // The required documents of the people of the list as filtered, every page (P42): one row per person and document.
    @GetMapping("/office/crew/documents/export")
    public Object exportDocuments(@RequestParam(required = false) Long shipId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String documents,
                                  @ModelAttribute ListRequest listRequest, RedirectAttributes redirectAttributes) {
        CrewStatus crewStatus = CrewStatus.of(status).orElse(null);
        boolean missing = MISSING.equals(documents);
        ListQuery list = fleetList(shipId, crewStatus, missing, listRequest, crewService.findAllShips());
        return exportDocuments(crewService.findAllCrewUsers(shipId, crewStatus, missing, list), "required-documents",
                list.getUrl(), redirectAttributes);
    }

    // The same for the ship page's Crew tab.
    @GetMapping("/office/ships/{id}/crew/documents/export")
    public Object exportShipDocuments(@PathVariable Long id, @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String documents,
                                      @ModelAttribute ListRequest listRequest, RedirectAttributes redirectAttributes) {
        Ship ship = shipFrame.ship(id);
        CrewStatus crewStatus = CrewStatus.of(status).orElse(null);
        boolean missing = MISSING.equals(documents);
        ListQuery list = shipList(id, crewStatus, missing, listRequest);
        return exportDocuments(crewService.findAllCrewUsers(id, crewStatus, missing, list),
                "required-documents-" + ship.getImoNumber(), list.getUrl(), redirectAttributes);
    }

    // The workbook, or back to the list with "Too many rows for one file" when it does not fit in one .xls sheet.
    private Object exportDocuments(List<User> users, String name, String back, RedirectAttributes redirectAttributes) {
        try {
            return ComplianceExport.xls(name, complianceService.of(users).values(), complianceService.today())
                    .response();
        } catch (XlsExport.TooManyRowsException e) {
            redirectAttributes.addFlashAttribute("errorMessage", XlsExport.TooManyRowsException.MESSAGE);
            return "redirect:" + back;
        }
    }

    // The Crew tab of the ship page: the same list for one ship, without the ship filter and column. Add crew member
    // opens the same modal as on the Crew page with this ship preselected; ?add=true opens it on load (where the
    // former invitation page, /office/ships/{id}/invitations/new, now leads).
    @GetMapping("/office/ships/{id}/crew")
    public String shipCrew(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean add,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) String documents, @ModelAttribute ListRequest listRequest,
                           Model model) {
        AddCrewForm form = new AddCrewForm();
        form.setShipId(id);
        model.addAttribute("addForm", form);
        model.addAttribute("openAdd", add);
        return showShipCrew(id, CrewStatus.of(status).orElse(null), MISSING.equals(documents), listRequest, model);
    }

    // On errors the list page is shown again and the modal reopens with the errors.
    @PostMapping("/office/crew/new")
    public String add(@Valid @ModelAttribute("addForm") AddCrewForm form, BindingResult result,
                      @AuthenticationPrincipal CurrentUser currentUser, Model model,
                      RedirectAttributes redirectAttributes) {
        Invitation invitation = invite(form, result, currentUser);
        if (invitation == null) {
            return showList(null, null, false, ListRequest.EMPTY, model);
        }
        sent(invitation, redirectAttributes);
        return "redirect:/office/crew";
    }

    // Add crew member from the ship page's Crew tab. On errors the tab is shown again with the modal open; once sent,
    // the Crew tab of the ship the invitation is for, where the new member is listed as "Invited".
    @PostMapping("/office/ships/{id}/crew/new")
    public String addFromShip(@PathVariable Long id, @Valid @ModelAttribute("addForm") AddCrewForm form,
                              BindingResult result, @AuthenticationPrincipal CurrentUser currentUser, Model model,
                              RedirectAttributes redirectAttributes) {
        shipFrame.ship(id);
        Invitation invitation = invite(form, result, currentUser);
        if (invitation == null) {
            return showShipCrew(id, null, false, ListRequest.EMPTY, model);
        }
        sent(invitation, redirectAttributes);
        return "redirect:" + ShipTab.CREW.url(invitation.getShip().getId());
    }

    // Sends the invitation, or rejects the field at fault and returns null. The one-per-ship rule is about the rank's
    // role, so it is reported on the rank field (P18).
    private Invitation invite(AddCrewForm form, BindingResult result, CurrentUser currentUser) {
        if (result.hasErrors()) {
            return null;
        }
        try {
            return crewService.addCrewMember(form.getFullName(), form.getEmail(), form.getRank(), form.getShipId(),
                    currentUser.getUsername());
        } catch (EmailAlreadyRegisteredException e) {
            result.rejectValue("email", "invitation.error.userExists");
        } catch (ShipRoleTakenException e) {
            result.rejectValue("rank", "user.error.shipRoleTaken");
        }
        return null;
    }

    // "Invitation sent to <address>.": there is no "Repeat email" field, so the office sees where it went.
    private static void sent(Invitation invitation, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("successMessage", "invitation.sent");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {invitation.getEmail()});
    }

    @GetMapping("/office/crew/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        User user = crewService.getCrewMember(id);
        model.addAttribute("form", EditCrewForm.of(user));
        return showEditForm(user, model);
    }

    // A crew member's profile photo (P31), for the Profile card of the edit page. size: 512 or 96; 404 for anything
    // but their current photo.
    @GetMapping("/office/crew/{id}/photo/{key}/{size}")
    public ResponseEntity<Resource> photo(@PathVariable Long id, @PathVariable String key, @PathVariable String size) {
        return PhotoResponse.of(profileService.photoForOffice(id, key, PhotoResponse.size(size)));
    }

    @PostMapping("/office/crew/{id}/edit")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") EditCrewForm form,
                         BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        User user = crewService.getCrewMember(id);
        if (result.hasErrors()) {
            return showEditForm(user, model);
        }
        try {
            crewService.update(id, form.getFullName(), form.getRank(), form.getShipId());
        } catch (ShipRoleTakenException e) {
            result.rejectValue("rank", "user.error.shipRoleTaken");
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

    // Model: list (ListQuery), people (one page of PeopleQueries.Row), the filters' options and values.
    private String showList(Long shipId, CrewStatus status, boolean missing, ListRequest request, Model model) {
        List<Ship> ships = crewService.findAllShips();
        ListQuery list = fleetList(shipId, status, missing, request, ships);
        model.addAttribute("list", list);
        Page<PeopleQueries.Row> people = crewService.findCrew(shipId, status, missing, list);
        model.addAttribute("people", people);
        // P18: the "Rank needs review" notice, for the crew members in the list as filtered.
        model.addAttribute("rankNeedsReview", crewService.findRankNeedsReview(shipId, status, missing, list));
        model.addAttribute("ships", ships);
        model.addAttribute("shipId", shipId);
        model.addAttribute("status", status == null ? null : status.key());
        model.addAttribute("statuses", CrewStatus.values());
        // Resend and Cancel on an invitation come back here, as it is.
        model.addAttribute("listParams", CrewListReturn.fleet(shipId, status, list.getRequest()).params());
        showDocuments(people, missing, list, "/office/crew/documents/export", model);
        return showAddForm(model);
    }

    private String showShipCrew(Long shipId, CrewStatus status, boolean missing, ListRequest request, Model model) {
        shipFrame.show(model, shipFrame.ship(shipId), ShipTab.CREW);
        ListQuery list = shipList(shipId, status, missing, request);
        model.addAttribute("list", list);
        Page<PeopleQueries.Row> people = crewService.findCrew(shipId, status, missing, list);
        model.addAttribute("people", people);
        model.addAttribute("status", status == null ? null : status.key());
        model.addAttribute("statuses", CrewStatus.values());
        model.addAttribute("listParams", CrewListReturn.shipTab(shipId, status, list.getRequest()).params());
        showDocuments(people, missing, list, ShipTab.CREW.url(shipId) + "/documents/export", model);
        return showAddForm(model);
    }

    // P42: the list's filters, with "Missing documents" (?documents=missing) after the status.
    private static ListQuery fleetList(Long shipId, CrewStatus status, boolean missing, ListRequest request,
                                       List<Ship> ships) {
        return withDocuments(CrewStatus.filter(ListQuery.of("/office/crew", request, FLEET_SORTS)
                .filterShip(shipId, ships), status), missing);
    }

    private static ListQuery shipList(Long shipId, CrewStatus status, boolean missing, ListRequest request) {
        return withDocuments(CrewStatus.filter(ListQuery.of(ShipTab.CREW.url(shipId), request, TAB_SORTS), status),
                missing);
    }

    private static ListQuery withDocuments(ListQuery list, boolean missing) {
        return list.filter("documents", missing ? MISSING : null, "crew.filter.missingDocuments", null);
    }

    // P42: each user's required documents ("5 of 7"), the filter's state and the export of the list as filtered.
    // Two queries for the page, whatever its size: the requirements, and the certificates of its users.
    private void showDocuments(Page<PeopleQueries.Row> people, boolean missing, ListQuery list, String exportPath,
                               Model model) {
        List<User> users = people.getContent().stream().map(PeopleQueries.Row::user).filter(Objects::nonNull).toList();
        RequirementRules rules = complianceService.rules();
        // No requirement set at all: no column, filter or export.
        model.addAttribute("anyRequirements", !rules.isEmpty() || missing);
        model.addAttribute("compliance", complianceService.of(rules, users));
        model.addAttribute("missingDocuments", missing);
        model.addAttribute("documentsExportUrl", list.urlAt(exportPath));
    }

    // What the Add crew member modal lists.
    private String showAddForm(Model model) {
        model.addAttribute("activeShips", crewService.findActiveShips());
        model.addAttribute("rankGroups", Rank.groups());
        return "crew/list";
    }

    private String showEditForm(User user, Model model) {
        model.addAttribute("member", user);
        model.addAttribute("rankGroups", Rank.groups());
        model.addAttribute("ships", crewService.findActiveShips());
        model.addAttribute("certificates", crewService.findCertificates(user.getId()));
        model.addAttribute("today", crewService.today());
        // P42: the documents their rank needs on their vessel, missing and expired first.
        model.addAttribute("compliance", complianceService.of(user));
        // P31: what the crew member chose to share, read only.
        model.addAttribute("profile", profileService.profileOf(user));
        return "crew/edit";
    }
}
