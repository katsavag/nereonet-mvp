package com.nereonix.nereonet_mvp.ship;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipType;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// The add and edit forms are modals on the list page. On errors the list page is shown again
// and the modal reopens with the errors.
@Controller
public class ShipController {

    private final ShipService shipService;

    public ShipController(ShipService shipService) {
        this.shipService = shipService;
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

    @PostMapping("/office/ships/{id}/edit")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("editForm") EditShipForm form,
                         BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        Ship ship = shipService.get(id);
        if (result.hasErrors()) {
            model.addAttribute("editShip", ship);
            return showList(model);
        }
        shipService.update(id, form.getName(), form.getType(), form.getFlag());
        redirectAttributes.addFlashAttribute("successMessage", "ship.updated");
        return "redirect:/office/ships";
    }

    @PostMapping("/office/ships/{id}/deactivate")
    public String deactivate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        shipService.setActive(id, false);
        redirectAttributes.addFlashAttribute("successMessage", "ship.deactivated");
        return "redirect:/office/ships";
    }

    @PostMapping("/office/ships/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        shipService.setActive(id, true);
        redirectAttributes.addFlashAttribute("successMessage", "ship.activated");
        return "redirect:/office/ships";
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
