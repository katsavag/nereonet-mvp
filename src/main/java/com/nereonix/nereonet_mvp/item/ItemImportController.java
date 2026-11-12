package com.nereonix.nereonet_mvp.item;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

import com.nereonix.nereonet_mvp.core.importfile.ImportFlow;
import com.nereonix.nereonet_mvp.core.importfile.ImportSteps;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.ship.ShipTab;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// The import in progress lives in the HTTP session as "itemImport" from the upload until it is done or cancelled; the
// steps are ImportSteps (P24): upload, sheet, map, values, review, done. The review form binds the edited values onto
// its rows. An import belongs to the ship it was uploaded for: under another ship's URL every step leads back to that
// ship's upload page, so rows never land on the wrong ship.
@Controller
@SessionAttributes("itemImport")
public class ItemImportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ItemImportService itemImportService;
    private final ItemService itemService;
    private final ImportSteps steps;

    public ItemImportController(ItemImportService itemImportService, ItemService itemService, ImportSteps steps) {
        this.itemImportService = itemImportService;
        this.itemService = itemService;
        this.steps = steps;
    }

    // Only the editable values; the row number, ship, action and errors come from the server.
    @InitBinder("itemImport")
    void allowEditableFields(WebDataBinder binder) {
        binder.setAllowedFields("rows[*].name", "rows[*].unit", "rows[*].category", "rows[*].excluded");
    }

    // Used only when there is no import in the session (none started, it expired or it was done).
    // Its ship is null, so every step leads back to the upload page.
    @ModelAttribute("itemImport")
    ItemImportForm noImport() {
        return new ItemImportForm();
    }

    @GetMapping("/office/ships/{id}/items/import")
    public String importPage(@PathVariable Long id, Model model) {
        return steps.uploadPage(flow(id), model);
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
        ImportFlow flow = flow(id);
        // A new import, stored in the session by @SessionAttributes, replacing an earlier one.
        ItemImportForm form = new ItemImportForm();
        form.setShipId(id);
        model.addAttribute("itemImport", form);
        return steps.upload(flow, itemImportService, form, file, model);
    }

    @GetMapping("/office/ships/{id}/items/import/sheet")
    public String sheetPage(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form, Model model) {
        return forShip(id, form) ? steps.sheetPage(flow(id), form, model) : uploadAgain(id);
    }

    @PostMapping("/office/ships/{id}/items/import/sheet")
    public String sheet(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form,
                        @RequestParam int sheet) {
        return forShip(id, form) ? steps.sheet(flow(id), itemImportService, form, sheet) : uploadAgain(id);
    }

    @GetMapping("/office/ships/{id}/items/import/map")
    public String mapPage(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form, Model model) {
        return forShip(id, form) ? steps.mapPage(flow(id), itemImportService, form, model) : uploadAgain(id);
    }

    @PostMapping("/office/ships/{id}/items/import/map/header")
    public String header(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form,
                         @RequestParam int header) {
        return forShip(id, form) ? steps.header(flow(id), itemImportService, form, header) : uploadAgain(id);
    }

    @PostMapping("/office/ships/{id}/items/import/map")
    public String map(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form,
                      @RequestParam Map<String, String> params, Model model) {
        return forShip(id, form) ? steps.map(flow(id), itemImportService, form, params, model) : uploadAgain(id);
    }

    @GetMapping("/office/ships/{id}/items/import/values")
    public String valuesPage(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form, Model model) {
        return forShip(id, form) ? steps.valuesPage(flow(id), itemImportService, form, model) : uploadAgain(id);
    }

    @PostMapping("/office/ships/{id}/items/import/values")
    public String values(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form,
                         @RequestParam Map<String, String> params) {
        return forShip(id, form) ? steps.values(flow(id), itemImportService, form, params) : uploadAgain(id);
    }

    @GetMapping("/office/ships/{id}/items/import/review")
    public String review(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form,
                         @RequestParam(required = false) String show, Model model) {
        if (!forShip(id, form)) {
            return uploadAgain(id);
        }
        ImportFlow flow = flow(id);
        String redirect = steps.reviewRedirect(flow, form);
        if (redirect != null) {
            return redirect;
        }
        itemImportService.validate(form);
        steps.reviewModel(flow, form, show, model);
        model.addAttribute("ship", flow.ship());
        return "item/import-review";
    }

    // "Check again" and the row filters: the edited values are now in the session; the review page validates them.
    @PostMapping("/office/ships/{id}/items/import/check")
    public String check(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form,
                        @RequestParam(required = false) String show) {
        return forShip(id, form) ? steps.check(flow(id), form, show) : uploadAgain(id);
    }

    @PostMapping("/office/ships/{id}/items/import/exclude-errors")
    public String excludeErrors(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form,
                                @RequestParam(required = false) String show) {
        return forShip(id, form) ? steps.excludeErrors(flow(id), itemImportService, form, show) : uploadAgain(id);
    }

    @PostMapping("/office/ships/{id}/items/import/submit")
    public String submit(@PathVariable Long id, @ModelAttribute("itemImport") ItemImportForm form,
                         @AuthenticationPrincipal CurrentUser currentUser, SessionStatus sessionStatus,
                         RedirectAttributes redirectAttributes) {
        if (!forShip(id, form)) {
            return uploadAgain(id);
        }
        return steps.submit(flow(id), itemImportService, form, currentUser.getUsername(), sessionStatus,
                redirectAttributes);
    }

    @GetMapping("/office/ships/{id}/items/import/done")
    public String done(@PathVariable Long id, Model model) {
        return steps.donePage(flow(id), model);
    }

    @PostMapping("/office/ships/{id}/items/import/cancel")
    public String cancel(@PathVariable Long id, SessionStatus sessionStatus, RedirectAttributes redirectAttributes) {
        return steps.cancel(flow(id), sessionStatus, redirectAttributes);
    }

    // 404 if there is no such ship.
    private ImportFlow flow(Long id) {
        Ship ship = itemService.getShip(id);
        String base = "/office/ships/" + id + "/items/import";
        return new ImportFlow("itemImport", base, ShipTab.ITEMS.url(id), "/office/items/import/template", ship,
                List.of(ImportFlow.Crumb.of("nav.ships", "/office/ships"),
                        ImportFlow.Crumb.text(ship.getName(), ShipTab.OVERVIEW.url(id)),
                        ImportFlow.Crumb.of("ship.tab.items", ShipTab.ITEMS.url(id))));
    }

    private static boolean forShip(Long id, ItemImportForm form) {
        return id.equals(form.getShipId());
    }

    private static String uploadAgain(Long id) {
        return "redirect:/office/ships/" + id + "/items/import";
    }
}
