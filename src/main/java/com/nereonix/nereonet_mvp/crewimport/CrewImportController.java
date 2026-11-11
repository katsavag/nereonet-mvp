package com.nereonix.nereonet_mvp.crewimport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// The uploaded rows live in the HTTP session as "crewImport" from the upload until the import is
// submitted; the review form binds the edited values onto that object.
@Controller
@SessionAttributes("crewImport")
public class CrewImportController {

    private static final Logger log = LoggerFactory.getLogger(CrewImportController.class);

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CrewImportService crewImportService;

    public CrewImportController(CrewImportService crewImportService) {
        this.crewImportService = crewImportService;
    }

    // Only the editable values; the row number and errors come from the server.
    @InitBinder("crewImport")
    void allowEditableFields(WebDataBinder binder) {
        binder.setAllowedFields("rows[*].fullName", "rows[*].email", "rows[*].rank", "rows[*].role",
                "rows[*].shipImo", "rows[*].excluded");
    }

    @GetMapping("/office/crew/import")
    public String importPage() {
        return "crewimport/import";
    }

    @GetMapping("/office/crew/import/template")
    public ResponseEntity<byte[]> template(Locale locale) throws IOException {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("crew-template.xlsx").build().toString())
                .body(crewImportService.template(locale));
    }

    @PostMapping("/office/crew/import")
    public String upload(@RequestParam("file") MultipartFile file, Model model) {
        Optional<CrewImportForm> form;
        try (InputStream in = file.getInputStream()) {
            form = crewImportService.read(file.getOriginalFilename(), in);
        } catch (IOException e) {
            // The reason only, never file contents.
            log.info("Crew import file {} could not be read: {}", file.getOriginalFilename(), e.getMessage());
            model.addAttribute("unreadable", true);
            return "crewimport/import";
        }
        if (form.isEmpty()) {
            model.addAttribute("notTemplate", true);
            return "crewimport/import";
        }
        // Stored in the session by @SessionAttributes, replacing an earlier upload.
        model.addAttribute("crewImport", form.get());
        return "redirect:/office/crew/import/review";
    }

    @GetMapping("/office/crew/import/review")
    public String review(@ModelAttribute("crewImport") CrewImportForm form, Model model) {
        crewImportService.validate(form);
        model.addAttribute("ships", crewImportService.findActiveShips());
        model.addAttribute("shipImos", crewImportService.findActiveShips().stream().map(Ship::getImoNumber).toList());
        model.addAttribute("roles", CrewTemplate.SHIP_ROLES);
        model.addAttribute("roleNames", CrewTemplate.SHIP_ROLES.stream().map(Enum::name).toList());
        model.addAttribute("ranks", Rank.values());
        model.addAttribute("rankNames", Arrays.stream(Rank.values()).map(Enum::name).toList());
        return "crewimport/review";
    }

    // "Check again": the edited values are now in the session; the review page validates them.
    @PostMapping("/office/crew/import/check")
    public String check(@ModelAttribute("crewImport") CrewImportForm form) {
        return "redirect:/office/crew/import/review";
    }

    @PostMapping("/office/crew/import/submit")
    public String submit(@ModelAttribute("crewImport") CrewImportForm form,
                         @AuthenticationPrincipal CurrentUser currentUser, SessionStatus sessionStatus,
                         RedirectAttributes redirectAttributes) {
        crewImportService.validate(form);
        if (!form.isReady()) {
            // The button is disabled then; this happens only if values were changed without "Check again".
            return "redirect:/office/crew/import/review";
        }
        int sent = crewImportService.importAndInvite(form, currentUser.getUsername());
        sessionStatus.setComplete();
        redirectAttributes.addFlashAttribute("sentCount", sent);
        return "redirect:/office/crew/import";
    }

    // No upload in this session (e.g. it expired or the import was already submitted): start again.
    @ExceptionHandler(HttpSessionRequiredException.class)
    public String noImportInSession() {
        return "redirect:/office/crew/import";
    }
}
