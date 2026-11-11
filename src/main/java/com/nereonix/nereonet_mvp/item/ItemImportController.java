package com.nereonix.nereonet_mvp.item;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// The uploaded rows live in the HTTP session as "itemImport" from the upload until the import is
// submitted; the review form binds the edited values onto that object.
@Controller
@SessionAttributes("itemImport")
public class ItemImportController {

    private static final Logger log = LoggerFactory.getLogger(ItemImportController.class);

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ItemImportService itemImportService;
    private final ItemService itemService;

    public ItemImportController(ItemImportService itemImportService, ItemService itemService) {
        this.itemImportService = itemImportService;
        this.itemService = itemService;
    }

    // Only the editable values; the row number, ship and errors come from the server.
    @InitBinder("itemImport")
    void allowEditableFields(WebDataBinder binder) {
        binder.setAllowedFields("rows[*].name", "rows[*].unit", "rows[*].category", "rows[*].excluded");
    }

    // Used only when there is no upload in the session (it expired or was already submitted).
    // Its ship is null, so the review pages send the user back to the upload page.
    @ModelAttribute("itemImport")
    ItemImportForm noUpload() {
        return new ItemImportForm();
    }

    @GetMapping("/office/ships/{id}/items/import")
    public String importPage(@PathVariable Long id, Model model) {
        model.addAttribute("ship", itemService.getShip(id));
        return "item/import";
    }

    @GetMapping("/office/items/import/template")
    public ResponseEntity<byte[]> template() throws IOException {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("items-template.xlsx").build().toString())
                .body(itemImportService.template());
    }

    @PostMapping("/office/ships/{id}/items/import")
    public String upload(@PathVariable Long id, @RequestParam("file") MultipartFile file, Model model) {
        model.addAttribute("ship", itemService.getShip(id));
        Optional<ItemImportForm> form;
        try (InputStream in = file.getInputStream()) {
            form = itemImportService.read(id, file.getOriginalFilename(), in);
        } catch (IOException e) {
            // The reason only, never file contents.
            log.info("Item import file {} could not be read: {}", file.getOriginalFilename(), e.getMessage());
            model.addAttribute("unreadable", true);
            return "item/import";
        }
        if (form.isEmpty()) {
            model.addAttribute("notTemplate", true);
            return "item/import";
        }
        // Stored in the session by @SessionAttributes, replacing an earlier upload.
        model.addAttribute("itemImport", form.get());
        return "redirect:/office/ships/" + id + "/items/import/review";
    }

    @GetMapping("/office/ships/{id}/items/import/review")
    public String review(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form, Model model) {
        if (!id.equals(form.getShipId())) {
            return "redirect:/office/ships/" + id + "/items/import";
        }
        itemImportService.validate(form);
        model.addAttribute("ship", itemService.getShip(id));
        return "item/import-review";
    }

    // "Check again": the edited values are now in the session; the review page validates them.
    @PostMapping("/office/ships/{id}/items/import/check")
    public String check(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form) {
        return "redirect:/office/ships/" + id + "/items/import/review";
    }

    @PostMapping("/office/ships/{id}/items/import/submit")
    public String submit(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form,
                         SessionStatus sessionStatus, RedirectAttributes redirectAttributes) {
        if (!id.equals(form.getShipId())) {
            return "redirect:/office/ships/" + id + "/items/import";
        }
        itemImportService.validate(form);
        if (!form.isReady()) {
            // The button is disabled then; this happens only if values were changed without "Check again".
            return "redirect:/office/ships/" + id + "/items/import/review";
        }
        ItemImportService.Result result = itemImportService.importItems(form);
        sessionStatus.setComplete();
        redirectAttributes.addFlashAttribute("importedCount", result.added());
        redirectAttributes.addFlashAttribute("skippedNames", result.skippedNames());
        return "redirect:/office/ships/" + id + "/items";
    }
}
