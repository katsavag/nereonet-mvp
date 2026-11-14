package com.nereonix.nereonet_mvp.ship;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.confirm.ConfirmPages;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.ImoNumber;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselDataService;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselLookup;
import com.nereonix.nereonet_mvp.core.vetting.RightShipView;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;
import com.nereonix.nereonet_mvp.dashboard.DashboardService;

import jakarta.validation.Validator;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// The add and edit forms are modals on the list page. On errors the list page is shown again
// and the modal reopens with the errors. The ship page (/office/ships/{id}, see ShipTab) has the same Edit modal
// in its header; its forms send "tab", the key of the tab they were on, and come back to that tab. Without
// JavaScript, Edit opens /office/ships/{id}/edit, a full page with the same form, and Add vessel /office/ships/new.
//
// P45, VesselAPI (only while VesselApiSettings.configured()): "Look up by IMO" on Add vessel and "Refresh from
// VesselAPI" on Edit vessel are submit buttons with their own action (formaction), so they work without JavaScript;
// both show the full page with the results. The lookup is kept by the server (VesselLookupStore) under a token the
// form carries; Save takes the provider's values from there, only for that user and that IMO number. The forms keep
// what the user typed on every error.
@Controller
public class ShipController {

    private final ShipService shipService;
    private final ShipFrame shipFrame;
    private final DashboardService dashboardService;
    private final ConfirmPages confirmPages;
    private final VettingService vettingService;
    private final VesselDataService vesselDataService;
    private final VesselLookupStore lookupStore;
    private final SpringValidatorAdapter validator;

    private static final String LIST = "/office/ships";
    private static final ListSort[] SORTS = {ListSort.asc("name"), ListSort.asc("score")};

    public ShipController(ShipService shipService, ShipFrame shipFrame, DashboardService dashboardService,
                          ConfirmPages confirmPages, VettingService vettingService,
                          VesselDataService vesselDataService, VesselLookupStore lookupStore, Validator validator) {
        this.shipService = shipService;
        this.shipFrame = shipFrame;
        this.dashboardService = dashboardService;
        this.confirmPages = confirmPages;
        this.vettingService = vettingService;
        this.vesselDataService = vesselDataService;
        this.lookupStore = lookupStore;
        this.validator = new SpringValidatorAdapter(validator);
    }

    // The ship page's Overview tab: open items, crew on board by department, particulars; P45: a line with the
    // stored position age and destination (no call to VesselAPI).
    @GetMapping("/office/ships/{id}")
    public String overview(@PathVariable Long id, Model model) {
        Ship ship = shipService.get(id);
        shipFrame.show(model, ship, ShipTab.OVERVIEW);
        model.addAttribute("overview", dashboardService.shipOverview(ship));
        model.addAttribute("vesselData", vesselDataService.view(ship));
        model.addAttribute("today", LocalDate.now(ZoneOffset.UTC));
        return "ship/overview";
    }

    // The Edit ship form as a full page: where Edit leads without JavaScript, and where a failed save from the ship
    // page or from this page shows its errors. tab: the ship page tab to return to, or none for the ships list.
    @GetMapping("/office/ships/{id}/edit")
    public String editPage(@PathVariable Long id, @RequestParam(required = false) String tab, Model model) {
        return showEditPage(shipService.get(id), tab, model, null);
    }

    // Sorted by name (the default) or by RightShip Safety Score (P40: lowest first; not rated last either way). The
    // fleet is small: no search, filters or pages.
    @GetMapping("/office/ships")
    public String list(@ModelAttribute ListRequest listRequest, Model model) {
        return showList(listRequest, model);
    }

    // Add vessel as a page: where Add vessel leads without JavaScript, and where a lookup shows its results (P45).
    @GetMapping("/office/ships/new")
    public String newPage(Model model) {
        model.addAttribute("newForm", new NewShipForm());
        return showNewPage(model, null);
    }

    // P45 "Look up by IMO": an invalid number or one already in the fleet is a field error and no call; otherwise the
    // three calls, recorded, and the form prefilled (still editable). Not validated otherwise: what was typed stays.
    @PostMapping("/office/ships/new/lookup")
    public String lookup(@ModelAttribute("newForm") NewShipForm form, BindingResult result,
                         @AuthenticationPrincipal CurrentUser user, Model model) {
        form.setLookupToken(null);
        form.setFlagChoice(null);
        String digits = ImoNumber.normalize(form.getImoNumber());
        if (!ImoNumber.isValid(digits)) {
            result.rejectValue("imoNumber", "ship.error.imoInvalid");
            return showNewPage(model, null);
        }
        if (inFleet(digits, result, model) || !vesselDataService.configured()) {
            return showNewPage(model, null);
        }
        VesselLookup lookup = vesselDataService.lookup(digits);
        vesselDataService.recordLookup(lookup);
        form.setImoNumber(digits);
        form.setLookupToken(lookupStore.put(user.getId(), lookup));
        VesselProposal proposal = VesselProposal.of(lookup.particulars());
        if (proposal != null) {
            if (proposal.name() != null) {
                form.setName(limit(proposal.name()));
            }
            if (proposal.type() != null) {
                form.setType(proposal.type());
            }
            if (proposal.flag() != null) {
                form.setFlag(countries().getOrDefault(proposal.flag(), proposal.flag()));
            } else if (proposal.flagConflict()) {
                form.setFlag(null); // the user chooses between the two (flagChoice)
            }
        }
        return showNewPage(model, lookup);
    }

    // page: posted from the full Add vessel page; errors are then shown there, not in the list's modal.
    @PostMapping("/office/ships/new")
    public String create(@ModelAttribute("newForm") NewShipForm form, BindingResult result,
                         @RequestParam(defaultValue = "false") boolean page,
                         @AuthenticationPrincipal CurrentUser user, Model model,
                         RedirectAttributes redirectAttributes) {
        VesselLookup lookup = null;
        if (hasText(form.getLookupToken())) {
            lookup = lookupStore.get(form.getLookupToken(), user.getId()).orElse(null);
            if (lookup == null) {
                form.setLookupToken(null);
                result.reject("vessel.lookup.expired");
            } else if (!lookup.imo().equals(ImoNumber.normalize(form.getImoNumber()))) {
                result.rejectValue("imoNumber", "vessel.lookup.imoMismatch", new Object[]{lookup.imo()}, null);
            }
        }
        VesselProposal proposal = lookup == null ? null : VesselProposal.of(lookup.particulars());
        if (proposal != null && proposal.flagConflict() && !hasText(form.getFlag())) {
            if (proposal.flagChoices().contains(form.getFlagChoice())) {
                form.setFlag(form.getFlagChoice());
            } else {
                result.rejectValue("flag", "vessel.flag.choose");
            }
        }
        validator.validate(form, result);
        if (result.hasErrors()) {
            return page || lookup != null ? showNewPage(model, lookup) : showList(model);
        }
        Ship ship;
        try {
            ship = shipService.create(form.getImoNumber(), form.getName(), form.getType(), form.getFlag());
        } catch (ImoNumberAlreadyExistsException e) {
            inFleet(ImoNumber.normalize(form.getImoNumber()), result, model);
            return page || lookup != null ? showNewPage(model, lookup) : showList(model);
        }
        if (lookup != null) {
            vesselDataService.recordAccepted(ship, lookup, String.join(", ", taken(ship, proposal)), true);
            lookupStore.remove(form.getLookupToken());
        }
        redirectAttributes.addFlashAttribute("successMessage", "ship.created");
        return "redirect:/office/ships";
    }

    // P45 "Refresh from VesselAPI" from Edit vessel (or the Vessel data tab, which posts no fields: the form is then
    // the saved vessel). The calls are recorded and the stored data updated now; the vessel's own fields change only
    // on Save, and only the ticked ones.
    @PostMapping("/office/ships/{id}/edit/lookup")
    public String refresh(@PathVariable Long id, @ModelAttribute("editForm") EditShipForm form, BindingResult result,
                          @RequestParam(required = false) String tab, @AuthenticationPrincipal CurrentUser user,
                          Model model) {
        Ship ship = shipService.get(id);
        EditShipForm shown = form.getName() == null ? EditShipForm.of(ship, countries()) : form;
        shown.setLookupToken(null);
        shown.setAccept(new ArrayList<>());
        shown.setFlagChoice(null);
        model.addAttribute("editForm", shown);
        if (!vesselDataService.configured()) {
            return showEditPage(ship, tab, model, null);
        }
        VesselLookup lookup = vesselDataService.lookup(ship.getImoNumber());
        vesselDataService.recordRefresh(ship, lookup);
        shown.setLookupToken(lookupStore.put(user.getId(), lookup));
        return showEditPage(ship, tab, model, lookup);
    }

    // page: posted from the full Edit page (no tab, from the ships list); errors are then shown there, not in the
    // list's modal. P45: with a lookup token, the ticked rows of the Saved / VesselAPI table take the lookup's values.
    @PostMapping("/office/ships/{id}/edit")
    public String update(@PathVariable Long id, @ModelAttribute("editForm") EditShipForm form,
                         BindingResult result, @RequestParam(required = false) String tab,
                         @RequestParam(defaultValue = "false") boolean page,
                         @AuthenticationPrincipal CurrentUser user, Model model,
                         RedirectAttributes redirectAttributes) {
        Ship ship = shipService.get(id);
        VesselLookup lookup = null;
        List<String> taken = List.of();
        if (hasText(form.getLookupToken())) {
            lookup = lookupStore.get(form.getLookupToken(), user.getId()).orElse(null);
            if (lookup == null) {
                form.setLookupToken(null);
                result.reject("vessel.lookup.expired");
            } else if (!lookup.imo().equals(ship.getImoNumber())) {
                form.setLookupToken(null);
                result.reject("vessel.lookup.imoMismatch", new Object[]{lookup.imo()}, null);
                lookup = null;
            } else {
                taken = accept(form, ship, VesselProposal.of(lookup.particulars()));
            }
        }
        validator.validate(form, result);
        if (result.hasErrors()) {
            if (page || lookup != null || ShipTab.of(tab).isPresent()) {
                return showEditPage(ship, tab, model, lookup);
            }
            model.addAttribute("editShip", ship);
            return showList(model);
        }
        shipService.update(id, form.getName(), form.getType(), form.getFlag());
        if (lookup != null) {
            if (!taken.isEmpty()) {
                vesselDataService.recordAccepted(ship, lookup, String.join(", ", taken), false);
            }
            lookupStore.remove(form.getLookupToken());
        }
        redirectAttributes.addFlashAttribute("successMessage", "ship.updated");
        return back(id, tab);
    }

    // The confirmation page: where Deactivate leads when js/confirm.js did not load (P10).
    @GetMapping("/office/ships/{id}/deactivate")
    public String confirmDeactivate(@PathVariable Long id, @RequestParam(required = false) String tab, Model model) {
        Ship ship = shipService.get(id);
        return confirmPages.neutral()
                .title("ship.deactivate.confirm.title", ship.getName())
                .body("ship.deactivate.confirm.body", ship.getName())
                .confirmLabel("ship.deactivate")
                .action("/office/ships/" + id + "/deactivate", ShipTab.of(tab).map(ShipTab::key).orElse(null))
                .cancelUrl(backUrl(id, tab))
                .crumb("nav.ships", "/office/ships")
                .crumbText(ship.getName(), ShipTab.OVERVIEW.url(id))
                .show(model);
    }

    @PostMapping("/office/ships/{id}/deactivate")
    public String deactivate(@PathVariable Long id, @RequestParam(required = false) String tab,
                             RedirectAttributes redirectAttributes) {
        shipService.setActive(id, false);
        redirectAttributes.addFlashAttribute("successMessage", "ship.deactivated");
        return back(id, tab);
    }

    @PostMapping("/office/ships/{id}/activate")
    public String activate(@PathVariable Long id, @RequestParam(required = false) String tab,
                           RedirectAttributes redirectAttributes) {
        shipService.setActive(id, true);
        redirectAttributes.addFlashAttribute("successMessage", "ship.activated");
        return back(id, tab);
    }

    // The IMO number belongs to a vessel of the fleet: "Already in the fleet: <name>" on the field, and a link to it.
    private boolean inFleet(String digits, BindingResult result, Model model) {
        return shipService.findByImoNumber(digits).map(existing -> {
            result.rejectValue("imoNumber", "vessel.lookup.inFleet", new Object[]{existing.getName()}, null);
            model.addAttribute("existingShip", existing);
            return true;
        }).orElse(false);
    }

    // The ticked rows that differ from the saved vessel take the lookup's values; the names of the fields taken.
    // A flag whose country and code disagree changes only to one of the two, picked with flagChoice.
    private static List<String> accept(EditShipForm form, Ship ship, VesselProposal proposal) {
        List<String> taken = new ArrayList<>();
        if (proposal == null) {
            return taken;
        }
        if (form.getAccept().contains("name") && proposal.nameDiffers(ship.getName())) {
            form.setName(limit(proposal.name()));
            taken.add("name");
        }
        if (form.getAccept().contains("type") && proposal.typeDiffers(ship.getType())) {
            form.setType(proposal.type());
            taken.add("type");
        }
        if (proposal.flagConflict()) {
            String choice = form.getFlagChoice();
            if (proposal.flagChoices().contains(choice) && !choice.equals(ship.getFlag())) {
                form.setFlag(choice);
                taken.add("flag");
            }
        } else if (form.getAccept().contains("flag") && proposal.flagDiffers(ship.getFlag())) {
            form.setFlag(proposal.flag());
            taken.add("flag");
        }
        return taken;
    }

    // A new vessel: the fields saved as VesselAPI proposed them (the others the user typed or changed).
    private static List<String> taken(Ship ship, VesselProposal proposal) {
        List<String> taken = new ArrayList<>();
        if (proposal == null) {
            return taken;
        }
        if (proposal.name() != null && !proposal.nameDiffers(ship.getName())) {
            taken.add("name");
        }
        if (proposal.type() != null && proposal.type() == ship.getType()) {
            taken.add("type");
        }
        if (ship.getFlag() != null && (ship.getFlag().equals(proposal.flag())
                || proposal.flagChoices().contains(ship.getFlag()))) {
            taken.add("flag");
        }
        return taken;
    }

    private static String limit(String name) {
        String value = name.strip();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, String> countries() {
        return Countries.labels(LocaleContextHolder.getLocale());
    }

    // To the ship page tab the action came from, or to the ships list.
    private static String back(Long id, String tab) {
        return "redirect:" + backUrl(id, tab);
    }

    private static String backUrl(Long id, String tab) {
        return ShipTab.of(tab).map(t -> t.url(id)).orElse("/office/ships");
    }

    // lookup: a "Refresh from VesselAPI" to compare (the Saved / VesselAPI table), or null.
    private String showEditPage(Ship ship, String tab, Model model, VesselLookup lookup) {
        ShipFrame.addEditForm(model, ship);
        model.addAttribute("ship", ship);
        model.addAttribute("returnTab", ShipTab.of(tab).orElse(null));
        model.addAttribute("lookup", lookup);
        model.addAttribute("proposal", lookup == null ? null : VesselProposal.of(lookup.particulars()));
        return "ship/edit";
    }

    // lookup: the results of "Look up by IMO" to show, or null.
    private String showNewPage(Model model, VesselLookup lookup) {
        model.addAttribute("lookup", lookup);
        model.addAttribute("proposal", lookup == null ? null : VesselProposal.of(lookup.particulars()));
        model.addAttribute("types", ShipType.values());
        model.addAttribute("countries", countries());
        return "ship/new";
    }

    private String showList(Model model) {
        return showList(ListRequest.EMPTY, model);
    }

    // Keeps a posted form (with its errors) and adds an empty one for the other modal.
    private String showList(ListRequest listRequest, Model model) {
        ListQuery list = ListQuery.of(LIST, listRequest, SORTS);
        Map<Long, RightShipView> vetting = vettingService.viewsByShip();
        List<Ship> ships = new ArrayList<>(shipService.findAll());
        if ("score".equals(list.getSort())) {
            Comparator<Integer> scores = list.isDescending() ? Comparator.<Integer>reverseOrder() : Comparator.naturalOrder();
            // A stable sort: equal scores stay in name order.
            ships.sort(Comparator.comparing((Ship s) -> vetting.containsKey(s.getId()) ? vetting.get(s.getId()).score() : null,
                    Comparator.nullsLast(scores)));
        } else if (list.isDescending()) {
            Collections.reverse(ships);
        }
        model.addAttribute("list", list);
        model.addAttribute("rightShip", vetting);
        model.addAttribute("noVetting", new RightShipView(null, VettingService.today()));
        if (!model.containsAttribute("newForm")) {
            model.addAttribute("newForm", new NewShipForm());
        }
        if (!model.containsAttribute("editForm")) {
            model.addAttribute("editForm", new EditShipForm());
        }
        model.addAttribute("ships", ships);
        model.addAttribute("types", ShipType.values());
        model.addAttribute("countries", countries());
        return "ship/list";
    }
}
