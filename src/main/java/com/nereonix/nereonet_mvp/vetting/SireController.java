package com.nereonix.nereonet_mvp.vetting;

import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.vetting.ObservationType;
import com.nereonix.nereonet_mvp.core.vetting.SireInspection;
import com.nereonix.nereonet_mvp.core.vetting.SireObservation;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.SireStatus;
import com.nereonix.nereonet_mvp.core.vetting.SireView;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;

/**
 * SIRE 2.0 in Vetting (P41), office only (/office/** in SecurityConfig). Below /office/vetting/{shipId}/sire:
 *   (the page)                                    the latest inspection (?inspection={id}: an earlier one;
 *                                                 ?show=all: closed observations too)
 *   /inspections/new                              Add inspection
 *   /inspections/{id}/edit                        Edit inspection
 *   /inspections/{id}/report                      Upload report (a PDF)
 *   /inspections/{id}/report/file                 the report (?download=true to save it)
 *   /inspections/{id}/observations/new            Add observation
 *   /inspections/{id}/observations/{oid}/edit     an observation's edit form
 * An inactive or unknown vessel, another vessel's inspection or another inspection's observation is a 404.
 */
@Controller
public class SireController {

    static final String BASE = VettingController.LIST + "/{shipId}/sire";
    private static final String INSPECTION = BASE + "/inspections/{inspectionId}";

    private final VettingService vettingService;
    private final SireService sireService;
    private final UserRepository userRepository;

    public SireController(VettingService vettingService, SireService sireService, UserRepository userRepository) {
        this.vettingService = vettingService;
        this.sireService = sireService;
        this.userRepository = userRepository;
    }

    @GetMapping(BASE)
    public String page(@PathVariable Long shipId, @RequestParam(required = false) Long inspection,
                       @RequestParam(required = false) String show, Model model) {
        Ship ship = vettingService.activeShip(shipId);
        List<SireInspection> inspections = sireService.inspections(shipId);
        SireInspection latest = inspections.isEmpty() ? null : inspections.getFirst();
        SireInspection shown = inspection == null ? latest : sireService.inspection(ship, inspection);
        model.addAttribute("ship", ship);
        model.addAttribute("sire", sireService.view(shown));
        boolean isLatest = shown == null || shown.getId().equals(latest.getId());
        model.addAttribute("latest", isLatest);
        model.addAttribute("latestInspection", latest);
        model.addAttribute("otherInspections", inspections.stream()
                .filter(i -> shown == null || !i.getId().equals(shown.getId())).toList());
        model.addAttribute("showAll", "all".equals(show));
        model.addAttribute("settings", sireService.settings());
        model.addAttribute("types", ObservationType.values());
        return "vetting/sire";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Inspections
    // ---------------------------------------------------------------------------------------------------------------

    @GetMapping(BASE + "/inspections/new")
    public String newInspection(@PathVariable Long shipId, Model model) {
        return showInspection(vettingService.activeShip(shipId), null, SireInspectionForm.empty(), model);
    }

    @PostMapping(BASE + "/inspections/new")
    public String addInspection(@PathVariable Long shipId, @Validated @ModelAttribute("form") SireInspectionForm form,
                                BindingResult result, @AuthenticationPrincipal CurrentUser currentUser, Model model,
                                RedirectAttributes redirectAttributes) {
        Ship ship = vettingService.activeShip(shipId);
        if (result.hasErrors()) {
            return showInspection(ship, null, form, model);
        }
        SireInspection added = sireService.addInspection(ship, form.toInspection(), user(currentUser));
        redirectAttributes.addFlashAttribute("successMessage", "sire.inspection.added");
        return "redirect:" + pageUrl(ship, added);
    }

    @GetMapping(INSPECTION + "/edit")
    public String editInspection(@PathVariable Long shipId, @PathVariable Long inspectionId, Model model) {
        Ship ship = vettingService.activeShip(shipId);
        SireInspection inspection = sireService.inspection(ship, inspectionId);
        return showInspection(ship, inspection, SireInspectionForm.of(inspection), model);
    }

    @PostMapping(INSPECTION + "/edit")
    public String updateInspection(@PathVariable Long shipId, @PathVariable Long inspectionId,
                                   @Validated @ModelAttribute("form") SireInspectionForm form, BindingResult result,
                                   @AuthenticationPrincipal CurrentUser currentUser, Model model,
                                   RedirectAttributes redirectAttributes) {
        Ship ship = vettingService.activeShip(shipId);
        SireInspection inspection = sireService.inspection(ship, inspectionId);
        if (result.hasErrors()) {
            return showInspection(ship, inspection, form, model);
        }
        sireService.updateInspection(inspection, form.toInspection(), user(currentUser));
        redirectAttributes.addFlashAttribute("successMessage", "sire.inspection.saved");
        return "redirect:" + pageUrl(ship, inspection);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The report
    // ---------------------------------------------------------------------------------------------------------------

    @GetMapping(INSPECTION + "/report")
    public String reportForm(@PathVariable Long shipId, @PathVariable Long inspectionId, Model model) {
        Ship ship = vettingService.activeShip(shipId);
        return showReport(ship, sireService.inspection(ship, inspectionId), new SireReportForm(), model);
    }

    @PostMapping(INSPECTION + "/report")
    public String uploadReport(@PathVariable Long shipId, @PathVariable Long inspectionId,
                               @Validated @ModelAttribute("form") SireReportForm form, BindingResult result,
                               @AuthenticationPrincipal CurrentUser currentUser, Model model,
                               RedirectAttributes redirectAttributes) {
        Ship ship = vettingService.activeShip(shipId);
        SireInspection inspection = sireService.inspection(ship, inspectionId);
        if (result.hasErrors()) {
            return showReport(ship, inspection, form, model);
        }
        sireService.uploadReport(inspection, form.getFile(), user(currentUser));
        redirectAttributes.addFlashAttribute("successMessage", "sire.report.uploaded");
        return "redirect:" + pageUrl(ship, inspection);
    }

    @GetMapping(INSPECTION + "/report/file")
    public ResponseEntity<Resource> reportFile(@PathVariable Long shipId, @PathVariable Long inspectionId,
                                               @RequestParam(defaultValue = "false") boolean download) {
        Ship ship = vettingService.activeShip(shipId);
        return sireService.reportFile(sireService.inspection(ship, inspectionId), download);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Observations
    // ---------------------------------------------------------------------------------------------------------------

    @GetMapping(INSPECTION + "/observations/new")
    public String newObservation(@PathVariable Long shipId, @PathVariable Long inspectionId, Model model) {
        Ship ship = vettingService.activeShip(shipId);
        return showObservation(ship, sireService.inspection(ship, inspectionId), null, new SireObservationForm(), model);
    }

    @PostMapping(INSPECTION + "/observations/new")
    public String addObservation(@PathVariable Long shipId, @PathVariable Long inspectionId,
                                 @Validated @ModelAttribute("form") SireObservationForm form, BindingResult result,
                                 @AuthenticationPrincipal CurrentUser currentUser, Model model,
                                 RedirectAttributes redirectAttributes) {
        Ship ship = vettingService.activeShip(shipId);
        SireInspection inspection = sireService.inspection(ship, inspectionId);
        User owner = checkOwner(form, result);
        if (result.hasErrors()) {
            return showObservation(ship, inspection, null, form, model);
        }
        SireObservation added = sireService.addObservation(inspection, form.toObservation(owner), user(currentUser));
        redirectAttributes.addFlashAttribute("successMessage", "sire.observation.added");
        return "redirect:" + pageUrl(ship, inspection) + "#observation-" + added.getId();
    }

    @GetMapping(INSPECTION + "/observations/{observationId}/edit")
    public String editObservation(@PathVariable Long shipId, @PathVariable Long inspectionId,
                                  @PathVariable Long observationId, Model model) {
        Ship ship = vettingService.activeShip(shipId);
        SireInspection inspection = sireService.inspection(ship, inspectionId);
        SireObservation observation = sireService.observation(inspection, observationId);
        return showObservation(ship, inspection, observation, SireObservationForm.of(observation), model);
    }

    @PostMapping(INSPECTION + "/observations/{observationId}/edit")
    public String updateObservation(@PathVariable Long shipId, @PathVariable Long inspectionId,
                                    @PathVariable Long observationId,
                                    @Validated @ModelAttribute("form") SireObservationForm form, BindingResult result,
                                    @AuthenticationPrincipal CurrentUser currentUser, Model model,
                                    RedirectAttributes redirectAttributes) {
        Ship ship = vettingService.activeShip(shipId);
        SireInspection inspection = sireService.inspection(ship, inspectionId);
        SireObservation observation = sireService.observation(inspection, observationId);
        User owner = checkOwner(form, result);
        if (result.hasErrors()) {
            return showObservation(ship, inspection, observation, form, model);
        }
        sireService.updateObservation(observation, form.toObservation(owner), user(currentUser));
        redirectAttributes.addFlashAttribute("successMessage", "sire.observation.saved");
        return "redirect:" + pageUrl(ship, inspection) + "#observation-" + observation.getId();
    }

    // The owner: none, or an office user (a crafted id of anyone else is refused under the field).
    private User checkOwner(SireObservationForm form, BindingResult result) {
        if (form.getOwnerId() == null) {
            return null;
        }
        User owner = sireService.owner(form.getOwnerId());
        if (owner == null) {
            result.rejectValue("ownerId", "sire.error.owner", "Choose an office user.");
        }
        return owner;
    }

    // ---------------------------------------------------------------------------------------------------------------

    private String showInspection(Ship ship, SireInspection inspection, SireInspectionForm form, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("inspection", inspection);
        model.addAttribute("form", form);
        model.addAttribute("statuses", SireStatus.values());
        model.addAttribute("settings", sireService.settings());
        return "vetting/sire-inspection";
    }

    private String showReport(Ship ship, SireInspection inspection, SireReportForm form, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("inspection", inspection);
        model.addAttribute("form", form);
        return "vetting/sire-report";
    }

    private String showObservation(Ship ship, SireInspection inspection, SireObservation observation,
                                   SireObservationForm form, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("inspection", inspection);
        model.addAttribute("observation", observation);
        model.addAttribute("form", form);
        model.addAttribute("chapters", SireObservation.CHAPTERS);
        model.addAttribute("types", ObservationType.values());
        model.addAttribute("owners", owners(observation));
        return "vetting/sire-observation";
    }

    // The office users, and the observation's owner when that user is disabled now (so the form keeps them).
    private List<User> owners(SireObservation observation) {
        List<User> owners = sireService.owners();
        User current = observation == null ? null : observation.getOwner();
        if (current != null && owners.stream().noneMatch(u -> u.getId().equals(current.getId()))) {
            return java.util.stream.Stream.concat(owners.stream(), java.util.stream.Stream.of(current)).toList();
        }
        return owners;
    }

    /** The SIRE page: the latest inspection plainly, an earlier one with ?inspection=. */
    private String pageUrl(Ship ship, SireInspection inspection) {
        List<SireInspection> all = sireService.inspections(ship.getId());
        String url = sireUrl(ship.getId());
        return !all.isEmpty() && all.getFirst().getId().equals(inspection.getId()) ? url
                : url + "?inspection=" + inspection.getId();
    }

    static String sireUrl(Long shipId) {
        return VettingController.LIST + "/" + shipId + "/sire";
    }

    private User user(CurrentUser currentUser) {
        return userRepository.findById(currentUser.getId()).orElseThrow();
    }
}
