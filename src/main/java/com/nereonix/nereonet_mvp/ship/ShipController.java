package com.nereonix.nereonet_mvp.ship;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.context.i18n.LocaleContextHolder;
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
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.dashboard.DashboardService;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// The add and edit forms are modals on the list page. On errors the list page is shown again
// and the modal reopens with the errors. The ship page (/office/ships/{id}, see ShipTab) has the same Edit modal
// in its header; its forms send "tab", the key of the tab they were on, and come back to that tab. Without
// JavaScript, Edit opens /office/ships/{id}/edit, a full page with the same form.
@Controller
public class ShipController {

    private final ShipService shipService;
    private final ShipFrame shipFrame;
    private final DashboardService dashboardService;
    private final ConfirmPages confirmPages;

    public ShipController(ShipService shipService, ShipFrame shipFrame, DashboardService dashboardService,
                          ConfirmPages confirmPages) {
        this.shipService = shipService;
        this.shipFrame = shipFrame;
        this.dashboardService = dashboardService;
        this.confirmPages = confirmPages;
    }

    // The ship page's Overview tab: open items, crew on board by department, particulars.
    @GetMapping("/office/ships/{id}")
    public String overview(@PathVariable Long id, Model model) {
        Ship ship = shipService.get(id);
        shipFrame.show(model, ship, ShipTab.OVERVIEW);
        model.addAttribute("overview", dashboardService.shipOverview(ship));
        model.addAttribute("today", LocalDate.now(ZoneOffset.UTC));
        return "ship/overview";
    }

    // The Edit ship form as a full page: where Edit leads without JavaScript, and where a failed save from the ship
    // page or from this page shows its errors. tab: the ship page tab to return to, or none for the ships list.
    @GetMapping("/office/ships/{id}/edit")
    public String editPage(@PathVariable Long id, @RequestParam(required = false) String tab, Model model) {
        return showEditPage(shipService.get(id), tab, model);
    }

    @GetMapping("/office/ships")
    public String list(Model model) {
        return showList(model);
    }

    @PostMapping("/office/ships/new")
    public String create(@Valid @ModelAttribute("newForm") NewShipForm form, BindingResult result, Model model,
                         RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showList(model);
        }
        try {
            shipService.create(form.getImoNumber(), form.getName(), form.getType(), form.getFlag());
        } catch (ImoNumberAlreadyExistsException e) {
            result.rejectValue("imoNumber", "ship.error.imoExists");
            return showList(model);
        }
        redirectAttributes.addFlashAttribute("successMessage", "ship.created");
        return "redirect:/office/ships";
    }

    // page: posted from the full Edit page (no tab, from the ships list); errors are then shown there, not in the
    // list's modal.
    @PostMapping("/office/ships/{id}/edit")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("editForm") EditShipForm form,
                         BindingResult result, @RequestParam(required = false) String tab,
                         @RequestParam(defaultValue = "false") boolean page, Model model,
                         RedirectAttributes redirectAttributes) {
        Ship ship = shipService.get(id);
        if (result.hasErrors()) {
            if (page || ShipTab.of(tab).isPresent()) {
                return showEditPage(ship, tab, model);
            }
            model.addAttribute("editShip", ship);
            return showList(model);
        }
        shipService.update(id, form.getName(), form.getType(), form.getFlag());
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

    // To the ship page tab the action came from, or to the ships list.
    private static String back(Long id, String tab) {
        return "redirect:" + backUrl(id, tab);
    }

    private static String backUrl(Long id, String tab) {
        return ShipTab.of(tab).map(t -> t.url(id)).orElse("/office/ships");
    }

    private String showEditPage(Ship ship, String tab, Model model) {
        ShipFrame.addEditForm(model, ship);
        model.addAttribute("ship", ship);
        model.addAttribute("returnTab", ShipTab.of(tab).orElse(null));
        return "ship/edit";
    }

    // Keeps a posted form (with its errors) and adds an empty one for the other modal.
    private String showList(Model model) {
        if (!model.containsAttribute("newForm")) {
            model.addAttribute("newForm", new NewShipForm());
        }
        if (!model.containsAttribute("editForm")) {
            model.addAttribute("editForm", new EditShipForm());
        }
        model.addAttribute("ships", shipService.findAll());
        model.addAttribute("types", ShipType.values());
        model.addAttribute("countries", Countries.labels(LocaleContextHolder.getLocale()));
        return "ship/list";
    }
}
