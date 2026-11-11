package com.nereonix.nereonet_mvp.item;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.item.Item;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
@Controller
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping("/office/ships/{id}/items")
    public String list(@PathVariable Long id, Model model) {
        model.addAttribute("ship", itemService.getShip(id));
        model.addAttribute("items", itemService.findItems(id));
        return "item/list";
    }

    @GetMapping("/office/ships/{id}/items/new")
    public String newForm(@PathVariable Long id, Model model) {
        model.addAttribute("form", new ItemForm());
        return showForm(id, null, model);
    }

    @PostMapping("/office/ships/{id}/items/new")
    public String create(@PathVariable Long id, @Valid @ModelAttribute("form") ItemForm form, BindingResult result,
                         Model model, RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showForm(id, null, model);
        }
        try {
            itemService.create(id, form.getName(), form.getUnit(), form.getCategory());
        } catch (ItemNameTakenException e) {
            result.rejectValue("name", "item.error.nameTaken");
            return showForm(id, null, model);
        }
        redirectAttributes.addFlashAttribute("successMessage", "item.created");
        return "redirect:/office/ships/" + id + "/items";
    }

    @GetMapping("/office/ships/{id}/items/{itemId}/edit")
    public String editForm(@PathVariable Long id, @PathVariable Long itemId, Model model) {
        Item item = itemService.getItem(id, itemId);
        model.addAttribute("form", ItemForm.of(item));
        return showForm(id, item, model);
    }

    @PostMapping("/office/ships/{id}/items/{itemId}/edit")
    public String update(@PathVariable Long id, @PathVariable Long itemId,
                         @Valid @ModelAttribute("form") ItemForm form, BindingResult result, Model model,
                         RedirectAttributes redirectAttributes) {
        Item item = itemService.getItem(id, itemId);
        if (result.hasErrors()) {
            return showForm(id, item, model);
        }
        try {
            itemService.update(id, itemId, form.getName(), form.getUnit(), form.getCategory());
        } catch (ItemNameTakenException e) {
            result.rejectValue("name", "item.error.nameTaken");
            return showForm(id, item, model);
        }
        redirectAttributes.addFlashAttribute("successMessage", "item.updated");
        return "redirect:/office/ships/" + id + "/items";
    }

    @PostMapping("/office/ships/{id}/items/{itemId}/deactivate")
    public String deactivate(@PathVariable Long id, @PathVariable Long itemId, RedirectAttributes redirectAttributes) {
        itemService.setActive(id, itemId, false);
        redirectAttributes.addFlashAttribute("successMessage", "item.deactivated");
        return "redirect:/office/ships/" + id + "/items";
    }

    @PostMapping("/office/ships/{id}/items/{itemId}/activate")
    public String activate(@PathVariable Long id, @PathVariable Long itemId, RedirectAttributes redirectAttributes) {
        itemService.setActive(id, itemId, true);
        redirectAttributes.addFlashAttribute("successMessage", "item.activated");
        return "redirect:/office/ships/" + id + "/items";
    }

    // item is null when adding.
    private String showForm(Long shipId, Item item, Model model) {
        model.addAttribute("ship", itemService.getShip(shipId));
        model.addAttribute("item", item);
        return "item/form";
    }
}
