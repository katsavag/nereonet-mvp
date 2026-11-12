package com.nereonix.nereonet_mvp.item;

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
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
@Controller
public class ItemController {

    private final ItemService itemService;
    private final ShipFrame shipFrame;
    private final ConfirmPages confirmPages;

    public ItemController(ItemService itemService, ShipFrame shipFrame, ConfirmPages confirmPages) {
        this.itemService = itemService;
        this.shipFrame = shipFrame;
        this.confirmPages = confirmPages;
    }

    // The columns that sort (P19), the default first.
    private static final ListSort[] SORTS =
            {ListSort.asc("name"), ListSort.asc("unit"), ListSort.asc("category"), ListSort.asc("status")};

    // The ship page's Items tab. status: active or inactive (anything else: both); category: one category;
    // listRequest: search, sort and page (P19).
    @GetMapping("/office/ships/{id}/items")
    public String list(@PathVariable Long id, @RequestParam(required = false) String status,
                       @RequestParam(required = false) String category, @ModelAttribute ListRequest listRequest,
                       Model model) {
        shipFrame.show(model, itemService.getShip(id), ShipTab.ITEMS);
        Boolean active = "active".equals(status) ? Boolean.TRUE : "inactive".equals(status) ? Boolean.FALSE : null;
        String statusKey = active == null ? null : status;
        String chosenCategory = category == null || category.isBlank() ? null : category;
        ListQuery list = ListQuery.of(ShipTab.ITEMS.url(id), listRequest, SORTS)
                .filterKey("status", statusKey, "item.status", statusKey == null ? null : "item.status." + statusKey)
                .filter("category", chosenCategory, "item.category", chosenCategory);
        model.addAttribute("list", list);
        model.addAttribute("items", itemService.findItems(id, active, chosenCategory, list));
        model.addAttribute("status", statusKey);
        model.addAttribute("category", chosenCategory);
        model.addAttribute("categories", itemService.findCategories(id));
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

    // The confirmation page: where Deactivate leads when js/confirm.js did not load (P10).
    @GetMapping("/office/ships/{id}/items/{itemId}/deactivate")
    public String confirmDeactivate(@PathVariable Long id, @PathVariable Long itemId, Model model) {
        Ship ship = itemService.getShip(id);
        Item item = itemService.getItem(id, itemId);
        return confirmPages.neutral()
                .title("item.deactivate.confirm.title", item.getName())
                .body("item.deactivate.confirm.body", item.getName(), ship.getName())
                .confirmLabel("item.deactivate")
                .action("/office/ships/" + id + "/items/" + itemId + "/deactivate", null)
                .cancelUrl(ShipTab.ITEMS.url(id))
                .crumb("nav.ships", "/office/ships")
                .crumbText(ship.getName(), ShipTab.OVERVIEW.url(id))
                .crumb("ship.tab.items", ShipTab.ITEMS.url(id))
                .show(model);
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
