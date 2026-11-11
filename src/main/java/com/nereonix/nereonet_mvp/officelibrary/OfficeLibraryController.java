package com.nereonix.nereonet_mvp.officelibrary;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryFullException;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemForm;
import com.nereonix.nereonet_mvp.core.library.LibraryService;
import com.nereonix.nereonet_mvp.core.library.ParticularsForm;
import com.nereonix.nereonet_mvp.core.library.PublishVersionForm;
import com.nereonix.nereonet_mvp.core.library.VersionLabelUnchangedException;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;

import jakarta.validation.Valid;

// Any ship's library, for the office (/office/** is limited to OFFICE_USER in SecurityConfig).
@Controller
public class OfficeLibraryController {

    private final LibraryService libraryService;

    public OfficeLibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping("/office/ships/{id}/library")
    public String library(@PathVariable Long id, Model model) {
        Ship ship = libraryService.ship(id);
        model.addAttribute("ship", ship);
        model.addAttribute("itemsByCategory", libraryService.itemsByCategory(ship));
        model.addAttribute("acknowledgementCounts", libraryService.acknowledgementCounts(ship));
        return "officelibrary/list";
    }

    @GetMapping("/office/ships/{shipId}/library/{id}/acknowledgements")
    public String acknowledgements(@PathVariable Long shipId, @PathVariable Long id, Model model) {
        model.addAttribute("overview", libraryService.acknowledgementOverview(libraryService.shipItem(shipId, id)));
        return "officelibrary/acknowledgements";
    }

    @GetMapping("/office/ships/{id}/library/new")
    public String newForm(@PathVariable Long id, Model model) {
        model.addAttribute("form", new LibraryItemForm());
        return showForm(libraryService.ship(id), model);
    }

    @PostMapping("/office/ships/{id}/library/new")
    public String create(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         @Valid @ModelAttribute("form") LibraryItemForm form, BindingResult result, Model model,
                         RedirectAttributes redirectAttributes) {
        Ship ship = libraryService.ship(id);
        if (result.hasErrors()) {
            return showForm(ship, model);
        }
        try {
            libraryService.addItem(currentUser.getUsername(), ship, form);
        } catch (LibraryFullException e) {
            result.reject("library.error.full");
            return showForm(ship, model);
        }
        redirectAttributes.addFlashAttribute("successMessage", "library.added");
        return "redirect:/office/ships/" + id + "/library";
    }

    @GetMapping("/office/ships/{id}/library/particulars")
    public String particularsForm(@PathVariable Long id, Model model) {
        Ship ship = libraryService.ship(id);
        ParticularsForm form = new ParticularsForm();
        form.setText(ship.getParticularsText());
        model.addAttribute("form", form);
        model.addAttribute("ship", ship);
        return "officelibrary/particulars";
    }

    @PostMapping("/office/ships/{id}/library/particulars")
    public String updateParticulars(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                    @Valid @ModelAttribute("form") ParticularsForm form, BindingResult result,
                                    Model model, RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            model.addAttribute("ship", libraryService.ship(id));
            return "officelibrary/particulars";
        }
        libraryService.updateParticulars(currentUser.getUsername(), id, form);
        redirectAttributes.addFlashAttribute("successMessage", "library.particulars.saved");
        return "redirect:/office/ships/" + id + "/library";
    }

    @GetMapping("/office/ships/{shipId}/library/{id}/edit")
    public String editForm(@PathVariable Long shipId, @PathVariable Long id, Model model) {
        LibraryItem item = libraryService.shipItem(shipId, id);
        PublishVersionForm form = new PublishVersionForm();
        form.setText(item.getCurrentVersion().getText());
        model.addAttribute("form", form);
        model.addAttribute("item", item);
        return "officelibrary/edit";
    }

    @PostMapping("/office/ships/{shipId}/library/{id}/edit")
    public String publishNewVersion(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long shipId,
                                    @PathVariable Long id, @Valid @ModelAttribute("form") PublishVersionForm form,
                                    BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        LibraryItem item = libraryService.shipItem(shipId, id);
        model.addAttribute("item", item);
        if (result.hasErrors()) {
            return "officelibrary/edit";
        }
        try {
            libraryService.publishNewVersion(currentUser.getUsername(), item, form);
        } catch (VersionLabelUnchangedException e) {
            result.rejectValue("versionLabel", "library.error.versionLabelUnchanged");
            return "officelibrary/edit";
        }
        redirectAttributes.addFlashAttribute("successMessage", "library.published");
        return "redirect:/office/ships/" + shipId + "/library";
    }

    @PostMapping("/office/ships/{shipId}/library/{id}/retire")
    public String retire(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long shipId,
                         @PathVariable Long id, RedirectAttributes redirectAttributes) {
        libraryService.retire(currentUser.getUsername(), libraryService.shipItem(shipId, id));
        redirectAttributes.addFlashAttribute("successMessage", "library.retired");
        return "redirect:/office/ships/" + shipId + "/library";
    }

    private String showForm(Ship ship, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("categories", LibraryCategory.values());
        return "officelibrary/new";
    }
}
