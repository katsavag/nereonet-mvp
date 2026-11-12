package com.nereonix.nereonet_mvp.officelibrary;

import org.springframework.core.io.Resource;
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
import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryFileResponse;
import com.nereonix.nereonet_mvp.core.library.LibraryFullException;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemForm;
import com.nereonix.nereonet_mvp.core.library.LibraryReminders;
import com.nereonix.nereonet_mvp.core.library.LibraryService;
import com.nereonix.nereonet_mvp.core.library.ParticularsForm;
import com.nereonix.nereonet_mvp.core.library.PublishVersionForm;
import com.nereonix.nereonet_mvp.core.library.VersionLabelUnchangedException;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// Any ship's library, for the office (/office/** is limited to OFFICE_USER in SecurityConfig).
@Controller
public class OfficeLibraryController {

    private final LibraryService libraryService;
    private final ShipFrame shipFrame;
    private final ConfirmPages confirmPages;

    public OfficeLibraryController(LibraryService libraryService, ShipFrame shipFrame, ConfirmPages confirmPages) {
        this.libraryService = libraryService;
        this.shipFrame = shipFrame;
        this.confirmPages = confirmPages;
    }

    // The ship page's Library tab.
    @GetMapping("/office/ships/{id}/library")
    public String library(@PathVariable Long id, Model model) {
        Ship ship = libraryService.ship(id);
        shipFrame.show(model, ship, ShipTab.LIBRARY);
        model.addAttribute("itemsByCategory", libraryService.itemsByCategory(ship));
        model.addAttribute("acknowledgementCounts", libraryService.acknowledgementCounts(ship));
        return "officelibrary/list";
    }

    // The read view of an item (P22): what the crew see, without "I have read this", plus the version history,
    // with New version and Retire as its actions.
    @GetMapping("/office/ships/{shipId}/library/{id}")
    public String item(@PathVariable Long shipId, @PathVariable Long id, Model model) {
        LibraryItem item = libraryService.shipItem(shipId, id);
        model.addAttribute("item", item);
        model.addAttribute("versions", libraryService.versions(item));
        return "officelibrary/item";
    }

    // A PDF opens in the browser; ?download=true (and every DOCX or XLSX) downloads it (P22).
    @GetMapping("/office/ships/{shipId}/library/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable Long shipId, @PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean download) {
        LibraryItem item = libraryService.shipItem(shipId, id);
        return LibraryFileResponse.of(item.getCurrentVersion(), libraryService.currentFile(item), download);
    }

    @GetMapping("/office/ships/{shipId}/library/{id}/acknowledgements")
    public String acknowledgements(@PathVariable Long shipId, @PathVariable Long id, Model model) {
        model.addAttribute("overview", libraryService.acknowledgementOverview(libraryService.shipItem(shipId, id)));
        return "officelibrary/acknowledgements";
    }

    // The confirmation page of Send reminder: where it leads when js/confirm.js did not load (P10). 404 when the item
    // is not Required, as the overview.
    @GetMapping("/office/ships/{shipId}/library/{id}/acknowledgements/remind")
    public String confirmRemind(@PathVariable Long shipId, @PathVariable Long id, Model model) {
        Ship ship = libraryService.ship(shipId);
        LibraryItem item = libraryService.acknowledgementOverview(libraryService.shipItem(shipId, id)).item();
        String itemUrl = itemUrl(shipId, id);
        return confirmPages.neutral()
                .title("library.reminder.confirm.title", item.getTitle())
                .body("library.reminder.confirm.body")
                .confirmLabel("library.reminder.send")
                .action(itemUrl + "/acknowledgements/remind", null)
                .cancelUrl(itemUrl + "/acknowledgements")
                .crumb("nav.ships", "/office/ships")
                .crumbText(ship.getName(), ShipTab.OVERVIEW.url(shipId))
                .crumb("ship.tab.library", ShipTab.LIBRARY.url(shipId))
                .crumbText(item.getTitle(), itemUrl)
                .crumb("library.acknowledgements.heading", itemUrl + "/acknowledgements")
                .show(model);
    }

    // "Send reminder" on the acknowledgement overview (P22).
    @PostMapping("/office/ships/{shipId}/library/{id}/acknowledgements/remind")
    public String remind(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long shipId,
                         @PathVariable Long id, RedirectAttributes redirectAttributes) {
        LibraryReminders.send(libraryService, currentUser.getUsername(), libraryService.shipItem(shipId, id),
                redirectAttributes);
        return "redirect:" + itemUrl(shipId, id) + "/acknowledgements";
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

    // "New version" (P22; was "Edit this item"): publish a new version. Cancel and Publish go back to the read view.
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
        return "redirect:" + itemUrl(shipId, id);
    }

    // The confirmation page: where Retire leads when js/confirm.js did not load (P10). Cancel goes back to the item.
    @GetMapping("/office/ships/{shipId}/library/{id}/retire")
    public String confirmRetire(@PathVariable Long shipId, @PathVariable Long id, Model model) {
        Ship ship = libraryService.ship(shipId);
        LibraryItem item = libraryService.shipItem(shipId, id);
        return confirmPages.danger()
                .title("library.retire.confirm.title", item.getTitle())
                .body("library.retire.confirm.body", item.getTitle())
                .confirmLabel("library.retire.submit")
                .action(itemUrl(shipId, id) + "/retire", null)
                .cancelUrl(itemUrl(shipId, id))
                .crumb("nav.ships", "/office/ships")
                .crumbText(ship.getName(), ShipTab.OVERVIEW.url(shipId))
                .crumb("ship.tab.library", ShipTab.LIBRARY.url(shipId))
                .crumbText(item.getTitle(), itemUrl(shipId, id))
                .show(model);
    }

    @PostMapping("/office/ships/{shipId}/library/{id}/retire")
    public String retire(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long shipId,
                         @PathVariable Long id, RedirectAttributes redirectAttributes) {
        libraryService.retire(currentUser.getUsername(), libraryService.shipItem(shipId, id));
        redirectAttributes.addFlashAttribute("successMessage", "library.retired");
        return "redirect:/office/ships/" + shipId + "/library";
    }

    /** The item's read view, /office/ships/{shipId}/library/{id}. */
    private static String itemUrl(Long shipId, Long id) {
        return ShipTab.LIBRARY.url(shipId) + "/" + id;
    }

    private String showForm(Ship ship, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("categories", LibraryCategory.values());
        return "officelibrary/new";
    }
}
