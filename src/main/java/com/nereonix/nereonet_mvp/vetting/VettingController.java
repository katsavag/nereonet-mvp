package com.nereonix.nereonet_mvp.vetting;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
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

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.vetting.GhgRating;
import com.nereonix.nereonet_mvp.core.vetting.InspectionOutcome;
import com.nereonix.nereonet_mvp.core.vetting.RightShipReport;
import com.nereonix.nereonet_mvp.core.vetting.RightShipSyncService;
import com.nereonix.nereonet_mvp.core.vetting.RightShipView;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.Subscore;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;

/**
 * Vetting (P40), office only (/office/** in SecurityConfig): how charterers vet a vessel. RightShip (P40) and SIRE
 * 2.0 (P41, SireController).
 *   /office/vetting                              every active vessel's RightShip and SIRE 2.0 standing (P19 list)
 *   /office/vetting/{shipId}                     one vessel: a card per vetting regime (RightShip, SIRE 2.0)
 *   /office/vetting/{shipId}/rightship           the RightShip dashboard and the official document
 *   /office/vetting/{shipId}/rightship/edit      Edit RightShip data
 *   /office/vetting/{shipId}/rightship/reports/new          Upload report
 *   /office/vetting/{shipId}/rightship/reports/{id}/file    a report's file (?download=true to save it)
 *   /office/vetting/{shipId}/rightship/refresh   Refresh from RightShip (POST; only when the API is configured)
 * An inactive vessel or an unknown id is a 404 on every page below a vessel.
 */
@Controller
public class VettingController {

    static final String LIST = "/office/vetting";

    // The list's sorts (P19), the default first: most urgent of both regimes (expired, expiring, lowest score), then
    // name; P41 adds the latest SIRE 2.0 inspection.
    private static final ListSort[] SORTS = {ListSort.asc(VettingService.SORT_URGENCY), ListSort.asc("name"),
            ListSort.asc("type"), ListSort.asc("score"), ListSort.asc("ghg"), ListSort.asc("inspection"),
            ListSort.desc("sire")};

    private final VettingService vettingService;
    private final RightShipSyncService syncService;
    private final UserRepository userRepository;
    private final SireService sireService;

    public VettingController(VettingService vettingService, RightShipSyncService syncService,
                             UserRepository userRepository, SireService sireService) {
        this.sireService = sireService;
        this.vettingService = vettingService;
        this.syncService = syncService;
        this.userRepository = userRepository;
    }

    // type: a ShipType (?type=BULK_CARRIER); regime: rightship or sire (P41); status: attention, valid or notrated.
    // Anything else filters nothing.
    @GetMapping(LIST)
    public String list(@RequestParam(required = false) String type, @RequestParam(required = false) String regime,
                       @RequestParam(required = false) String status,
                       @ModelAttribute ListRequest listRequest, Model model) {
        ShipType shipType = Arrays.stream(ShipType.values()).filter(t -> t.name().equals(type)).findFirst().orElse(null);
        String chosen = status != null && RightShipView.STATUSES.contains(status) ? status : null;
        String chosenRegime = regime != null && VettingService.REGIMES.contains(regime) ? regime : null;
        ListQuery list = ListQuery.of(LIST, listRequest, SORTS);
        if (shipType != null) {
            list.filterKey("type", shipType.name(), "vetting.filter.type", "ship.type." + shipType.name());
        }
        if (chosenRegime != null) {
            list.filterKey("regime", chosenRegime, "vetting.filter.regime", "vetting.regime." + chosenRegime);
        }
        if (chosen != null) {
            list.filterKey("status", chosen, "vetting.filter.status", "vetting.status." + chosen);
        }
        Page<VettingService.VettingRow> rows = vettingService.list(list, shipType, chosenRegime, chosen);
        model.addAttribute("list", list);
        model.addAttribute("rows", rows);
        model.addAttribute("types", ShipType.values());
        model.addAttribute("type", shipType == null ? null : shipType.name());
        model.addAttribute("statuses", RightShipView.STATUSES);
        model.addAttribute("status", chosen);
        model.addAttribute("regimes", VettingService.REGIMES);
        model.addAttribute("regime", chosenRegime);
        model.addAttribute("syncFailedAt", vettingService.syncFailedAt());
        return "vetting/list";
    }

    @GetMapping(LIST + "/{shipId}")
    public String vessel(@PathVariable Long shipId, Model model) {
        Ship ship = vettingService.activeShip(shipId);
        model.addAttribute("ship", ship);
        model.addAttribute("rightShip", vettingService.view(ship));
        // P41: tankers show SIRE 2.0 first; elsewhere it collapses to one line until the vessel has an inspection.
        model.addAttribute("sire", sireService.latest(ship));
        model.addAttribute("sireApplies", VettingService.sireApplies(ship));
        return "vetting/vessel";
    }

    @GetMapping(LIST + "/{shipId}/rightship")
    public String rightShip(@PathVariable Long shipId, Model model) {
        Ship ship = vettingService.activeShip(shipId);
        List<RightShipReport> reports = vettingService.reports(shipId);
        model.addAttribute("ship", ship);
        model.addAttribute("rightShip", vettingService.view(ship));
        model.addAttribute("report", reports.isEmpty() ? null : reports.getFirst());
        model.addAttribute("earlierReports", reports.size() < 2 ? List.of() : reports.subList(1, reports.size()));
        model.addAttribute("apiConfigured", vettingService.apiConfigured());
        model.addAttribute("syncFailedAt", vettingService.syncFailedAt());
        model.addAttribute("impacts", Subscore.Impact.values());
        return "vetting/rightship";
    }

    @GetMapping(LIST + "/{shipId}/rightship/edit")
    public String editForm(@PathVariable Long shipId, Model model) {
        Ship ship = vettingService.activeShip(shipId);
        return showEdit(ship, RightShipForm.of(vettingService.view(ship).status()), model);
    }

    @PostMapping(LIST + "/{shipId}/rightship/edit")
    public String save(@PathVariable Long shipId, @Validated @ModelAttribute("form") RightShipForm form,
                       BindingResult result, @AuthenticationPrincipal CurrentUser currentUser, Model model,
                       RedirectAttributes redirectAttributes) {
        Ship ship = vettingService.activeShip(shipId);
        if (result.hasErrors()) {
            return showEdit(ship, form, model);
        }
        vettingService.saveManual(ship, form.toStatus(), user(currentUser));
        redirectAttributes.addFlashAttribute("successMessage", "vetting.saved");
        return "redirect:" + rightShipUrl(shipId);
    }

    @GetMapping(LIST + "/{shipId}/rightship/reports/new")
    public String uploadForm(@PathVariable Long shipId, Model model) {
        return showUpload(vettingService.activeShip(shipId), new RightShipReportForm(), model);
    }

    @PostMapping(LIST + "/{shipId}/rightship/reports/new")
    public String upload(@PathVariable Long shipId, @Validated @ModelAttribute("form") RightShipReportForm form,
                         BindingResult result, @AuthenticationPrincipal CurrentUser currentUser, Model model,
                         RedirectAttributes redirectAttributes) {
        Ship ship = vettingService.activeShip(shipId);
        if (result.hasErrors()) {
            return showUpload(ship, form, model);
        }
        vettingService.upload(ship, form.getFile(), form.contentType(), form.getDocumentDate(), user(currentUser));
        redirectAttributes.addFlashAttribute("successMessage", "vetting.report.uploaded");
        return "redirect:" + rightShipUrl(shipId);
    }

    @GetMapping(LIST + "/{shipId}/rightship/reports/{reportId}/file")
    public ResponseEntity<Resource> reportFile(@PathVariable Long shipId, @PathVariable Long reportId,
                                               @RequestParam(defaultValue = "false") boolean download) {
        vettingService.activeShip(shipId);
        return vettingService.reportFile(shipId, reportId, download);
    }

    @PostMapping(LIST + "/{shipId}/rightship/refresh")
    public String refresh(@PathVariable Long shipId, @AuthenticationPrincipal CurrentUser currentUser,
                          RedirectAttributes redirectAttributes) {
        Ship ship = vettingService.activeShip(shipId);
        switch (syncService.refresh(ship, user(currentUser))) {
            case UPDATED -> redirectAttributes.addFlashAttribute("successMessage", "vetting.refresh.updated");
            case UNCHANGED -> redirectAttributes.addFlashAttribute("successMessage", "vetting.refresh.unchanged");
            case NOT_FOUND -> redirectAttributes.addFlashAttribute("errorMessage", "vetting.refresh.notFound");
            case FAILED -> redirectAttributes.addFlashAttribute("errorMessage", "vetting.refresh.failed");
            case NOT_CONFIGURED -> redirectAttributes.addFlashAttribute("errorMessage", "vetting.refresh.notConfigured");
        }
        return "redirect:" + rightShipUrl(shipId);
    }

    private String showEdit(Ship ship, RightShipForm form, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("form", form);
        model.addAttribute("impacts", Subscore.Impact.values());
        model.addAttribute("ghgRatings", GhgRating.values());
        model.addAttribute("outcomes", InspectionOutcome.values());
        return "vetting/edit";
    }

    private String showUpload(Ship ship, RightShipReportForm form, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("form", form);
        return "vetting/upload";
    }

    static String rightShipUrl(Long shipId) {
        return LIST + "/" + shipId + "/rightship";
    }

    private User user(CurrentUser currentUser) {
        return userRepository.findById(currentUser.getId()).orElseThrow();
    }
}
