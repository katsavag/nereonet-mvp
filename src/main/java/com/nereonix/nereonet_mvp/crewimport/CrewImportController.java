package com.nereonix.nereonet_mvp.crewimport;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import com.nereonix.nereonet_mvp.core.user.Rank;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// The import in progress lives in the HTTP session as "crewImport" from the upload until it is done or cancelled; the
// steps are ImportSteps (P24): upload, sheet, map, values, review, done. The review form binds the edited values onto
// its rows.
@Controller
@SessionAttributes("crewImport")
public class CrewImportController {

    private static final String BASE = "/office/crew/import";

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final ImportFlow FLOW = new ImportFlow("crewImport", BASE, "/office/crew", BASE + "/template",
            null, List.of(ImportFlow.Crumb.of("nav.crew", "/office/crew")));

    private final CrewImportService crewImportService;
    private final ImportSteps steps;

    public CrewImportController(CrewImportService crewImportService, ImportSteps steps) {
        this.crewImportService = crewImportService;
        this.steps = steps;
    }

    // Only the editable values; the row number, action and errors come from the server.
    @InitBinder("crewImport")
    void allowEditableFields(WebDataBinder binder) {
        binder.setAllowedFields("rows[*].fullName", "rows[*].email", "rows[*].rank", "rows[*].shipImo",
                "rows[*].excluded");
    }

    // Used only when there is no import in the session (none started, it expired or it was done): every step then
    // leads back to the upload page.
    @ModelAttribute("crewImport")
    CrewImportForm noImport() {
        return new CrewImportForm();
    }

    @GetMapping(BASE)
    public String importPage(Model model) {
        return steps.uploadPage(FLOW, model);
    }

    @GetMapping(BASE + "/template")
    public ResponseEntity<byte[]> template(Locale locale) throws IOException {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("crew-template.xlsx").build().toString())
                .body(crewImportService.template(locale));
    }

    @PostMapping(BASE)
    public String upload(@RequestParam("file") MultipartFile file, Model model) {
        // A new import, stored in the session by @SessionAttributes, replacing an earlier one.
        CrewImportForm form = new CrewImportForm();
        model.addAttribute("crewImport", form);
        return steps.upload(FLOW, crewImportService, form, file, model);
    }

    @GetMapping(BASE + "/sheet")
    public String sheetPage(@ModelAttribute("crewImport") CrewImportForm form, Model model) {
        return steps.sheetPage(FLOW, form, model);
    }

    @PostMapping(BASE + "/sheet")
    public String sheet(@ModelAttribute("crewImport") CrewImportForm form, @RequestParam int sheet) {
        return steps.sheet(FLOW, crewImportService, form, sheet);
    }

    @GetMapping(BASE + "/map")
    public String mapPage(@ModelAttribute("crewImport") CrewImportForm form, Model model) {
        return steps.mapPage(FLOW, crewImportService, form, model);
    }

    @PostMapping(BASE + "/map/header")
    public String header(@ModelAttribute("crewImport") CrewImportForm form, @RequestParam int header) {
        return steps.header(FLOW, crewImportService, form, header);
    }

    @PostMapping(BASE + "/map")
    public String map(@ModelAttribute("crewImport") CrewImportForm form, @RequestParam Map<String, String> params,
                      Model model) {
        return steps.map(FLOW, crewImportService, form, params, model);
    }

    @GetMapping(BASE + "/values")
    public String valuesPage(@ModelAttribute("crewImport") CrewImportForm form, Model model) {
        return steps.valuesPage(FLOW, crewImportService, form, model);
    }

    @PostMapping(BASE + "/values")
    public String values(@ModelAttribute("crewImport") CrewImportForm form, @RequestParam Map<String, String> params) {
        return steps.values(FLOW, crewImportService, form, params);
    }

    @GetMapping(BASE + "/review")
    public String review(@ModelAttribute("crewImport") CrewImportForm form,
                         @RequestParam(required = false) String show, Model model) {
        String redirect = steps.reviewRedirect(FLOW, form);
        if (redirect != null) {
            return redirect;
        }
        crewImportService.validate(form);
        steps.reviewModel(FLOW, form, show, model);
        List<Ship> ships = crewImportService.findActiveShips();
        model.addAttribute("ships", ships);
        model.addAttribute("shipImos", ships.stream().map(Ship::getImoNumber).toList());
        model.addAttribute("rankGroups", Rank.groups());
        model.addAttribute("rankNames", Arrays.stream(Rank.values()).map(Enum::name).toList());
        return "crewimport/review";
    }

    // "Check again" and the row filters: the edited values are now in the session; the review page validates them.
    @PostMapping(BASE + "/check")
    public String check(@ModelAttribute("crewImport") CrewImportForm form,
                        @RequestParam(required = false) String show) {
        return steps.check(FLOW, form, show);
    }

    @PostMapping(BASE + "/exclude-errors")
    public String excludeErrors(@ModelAttribute("crewImport") CrewImportForm form,
                                @RequestParam(required = false) String show) {
        return steps.excludeErrors(FLOW, crewImportService, form, show);
    }

    @PostMapping(BASE + "/submit")
    public String submit(@ModelAttribute("crewImport") CrewImportForm form,
                         @AuthenticationPrincipal CurrentUser currentUser, SessionStatus sessionStatus,
                         RedirectAttributes redirectAttributes) {
        return steps.submit(FLOW, crewImportService, form, currentUser.getUsername(), sessionStatus,
                redirectAttributes);
    }

    @GetMapping(BASE + "/done")
    public String done(Model model) {
        return steps.donePage(FLOW, model);
    }

    @PostMapping(BASE + "/cancel")
    public String cancel(SessionStatus sessionStatus, RedirectAttributes redirectAttributes) {
        return steps.cancel(FLOW, sessionStatus, redirectAttributes);
    }
}
