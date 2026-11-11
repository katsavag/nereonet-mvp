package com.nereonix.nereonet_mvp.library;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;
import com.nereonix.nereonet_mvp.core.library.LibraryService;
import com.nereonix.nereonet_mvp.core.library.ParticularsForm;
import com.nereonix.nereonet_mvp.core.library.PublishVersionForm;
import com.nereonix.nereonet_mvp.core.library.VersionLabelUnchangedException;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;

import jakarta.validation.Valid;

// The library of the logged-in ship user's own ship (SecurityConfig: /ship/** is for ship roles,
// /ship/library/new, /ship/library/particulars, /ship/library/{id}/edit, /ship/library/{id}/retire and
// /ship/library/{id}/acknowledgements for the MASTER only).
@Controller
public class LibraryController {

    private final LibraryService libraryService;

    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping("/ship/library")
    public String library(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        Ship ship = libraryService.shipOf(currentUser.getUsername());
        model.addAttribute("ship", ship);
        model.addAttribute("itemsByCategory", libraryService.itemsByCategory(ship));
        model.addAttribute("acknowledgedItemIds", libraryService.acknowledgedItemIds(currentUser.getUsername()));
        // Only the Master sees the acknowledgement counts.
        model.addAttribute("acknowledgementCounts",
                currentUser.getRole() == Role.MASTER ? libraryService.acknowledgementCounts(ship) : null);
        return "library/list";
    }

    @GetMapping("/ship/library/{id}/acknowledgements")
    public String acknowledgements(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                   Model model) {
        model.addAttribute("overview", libraryService.acknowledgementOverview(
                libraryService.visibleItem(currentUser.getUsername(), id)));
        return "library/acknowledgements";
    }

    @GetMapping("/ship/library/new")
    public String newForm(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        model.addAttribute("form", new LibraryItemForm());
        return showForm(libraryService.shipOf(currentUser.getUsername()), model);
    }

    @PostMapping("/ship/library/new")
    public String create(@AuthenticationPrincipal CurrentUser currentUser,
                         @Valid @ModelAttribute("form") LibraryItemForm form, BindingResult result, Model model,
                         RedirectAttributes redirectAttributes) {
        Ship ship = libraryService.shipOf(currentUser.getUsername());
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
        return "redirect:/ship/library";
    }

    @GetMapping("/ship/library/particulars")
    public String particularsForm(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        Ship ship = libraryService.shipOf(currentUser.getUsername());
        ParticularsForm form = new ParticularsForm();
        form.setText(ship.getParticularsText());
        model.addAttribute("form", form);
        model.addAttribute("ship", ship);
        return "library/particulars";
    }

    @PostMapping("/ship/library/particulars")
    public String updateParticulars(@AuthenticationPrincipal CurrentUser currentUser,
                                    @Valid @ModelAttribute("form") ParticularsForm form, BindingResult result,
                                    Model model, RedirectAttributes redirectAttributes) {
        Ship ship = libraryService.shipOf(currentUser.getUsername());
        if (result.hasErrors()) {
            model.addAttribute("ship", ship);
            return "library/particulars";
        }
        libraryService.updateParticulars(currentUser.getUsername(), ship.getId(), form);
        redirectAttributes.addFlashAttribute("successMessage", "library.particulars.saved");
        return "redirect:/ship/library";
    }

    @GetMapping("/ship/library/{id}")
    public String item(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        LibraryItem item = libraryService.visibleItem(currentUser.getUsername(), id);
        model.addAttribute("item", item);
        model.addAttribute("acknowledgement",
                libraryService.currentAcknowledgement(currentUser.getUsername(), item).orElse(null));
        return "library/item";
    }

    @PostMapping("/ship/library/{id}/acknowledge")
    public String acknowledge(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
        libraryService.acknowledge(currentUser.getUsername(), id);
        return "redirect:/ship/library/" + id;
    }

    @GetMapping("/ship/library/{id}/file")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
        LibraryItem item = libraryService.visibleItem(currentUser.getUsername(), id);
        Resource file = libraryService.currentFile(item);
        LibraryItemVersion version = item.getCurrentVersion();
        MediaType type = version.getFileContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(version.getFileContentType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(version.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(type)
                .body(file);
    }

    @GetMapping("/ship/library/{id}/edit")
    public String editForm(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        LibraryItem item = libraryService.visibleItem(currentUser.getUsername(), id);
        PublishVersionForm form = new PublishVersionForm();
        form.setText(item.getCurrentVersion().getText());
        model.addAttribute("form", form);
        model.addAttribute("item", item);
        return "library/edit";
    }

    @PostMapping("/ship/library/{id}/edit")
    public String publishNewVersion(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                    @Valid @ModelAttribute("form") PublishVersionForm form, BindingResult result,
                                    Model model, RedirectAttributes redirectAttributes) {
        LibraryItem item = libraryService.visibleItem(currentUser.getUsername(), id);
        model.addAttribute("item", item);
        if (result.hasErrors()) {
            return "library/edit";
        }
        try {
            libraryService.publishNewVersion(currentUser.getUsername(), item, form);
        } catch (VersionLabelUnchangedException e) {
            result.rejectValue("versionLabel", "library.error.versionLabelUnchanged");
            return "library/edit";
        }
        redirectAttributes.addFlashAttribute("successMessage", "library.published");
        return "redirect:/ship/library/" + id;
    }

    @PostMapping("/ship/library/{id}/retire")
    public String retire(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         RedirectAttributes redirectAttributes) {
        libraryService.retire(currentUser.getUsername(), libraryService.visibleItem(currentUser.getUsername(), id));
        redirectAttributes.addFlashAttribute("successMessage", "library.retired");
        return "redirect:/ship/library";
    }

    private String showForm(Ship ship, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("categories", LibraryCategory.values());
        return "library/new";
    }
}
