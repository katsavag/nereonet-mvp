package com.nereonix.nereonet_mvp.officerequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.CacheControl;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import com.nereonix.nereonet_mvp.core.confirm.ConfirmPages;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.request.RequestExport;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.time.Dates;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// The ship page's Requests tab lists one ship's approved requests, with the same filters and exports.
// Exporting (P21) is a POST that records the export and redirects to the list showing exactly the exported requests,
// with "3 requests exported · Download again"; the file comes from GET /office/requests/exports/{id}, which the page
// starts by itself (js/download.js).
@Controller
public class OfficeRequestController {

    private static final String LIST = "/office/requests";
    private static final String EXPORT = "/office/requests/export";

    private final OfficeRequestService requestService;
    private final ShipFrame shipFrame;
    private final ConfirmPages confirmPages;
    private final Dates dates;

    public OfficeRequestController(OfficeRequestService requestService, ShipFrame shipFrame,
                                   ConfirmPages confirmPages, Dates dates) {
        this.requestService = requestService;
        this.shipFrame = shipFrame;
        this.confirmPages = confirmPages;
        this.dates = dates;
    }

    // The columns that sort (P19), the default first; the ship's tab has no Ship column.
    private static final ListSort[] FLEET_SORTS = {ListSort.desc("approvedAt"), ListSort.desc("number"),
            ListSort.asc("ship"), ListSort.asc("requestedBy"), ListSort.asc("approvedBy")};
    private static final ListSort[] TAB_SORTS = {ListSort.desc("approvedAt"), ListSort.desc("number"),
            ListSort.asc("requestedBy"), ListSort.asc("approvedBy")};

    // exported: the Export status filter (ExportState, not exported yet by default); exportId: only the requests of
    // that export (where an export comes back to); listRequest: search, sort and page (P19).
    @GetMapping(LIST)
    public String list(@RequestParam(required = false) Long shipId,
                       @RequestParam(required = false) String exported,
                       @RequestParam(required = false) Long exportId,
                       @ModelAttribute ListRequest listRequest, Model model) {
        List<Ship> ships = requestService.findAllShips();
        ListQuery list = ListQuery.of(LIST, listRequest, FLEET_SORTS).filterShip(shipId, ships);
        model.addAttribute("ships", ships);
        model.addAttribute("shipId", shipId);
        return showList(shipId, ExportState.of(exported), exportId, list, model);
    }

    @GetMapping("/office/ships/{id}/requests")
    public String shipList(@PathVariable Long id, @RequestParam(required = false) String exported,
                           @RequestParam(required = false) Long exportId,
                           @ModelAttribute ListRequest listRequest, Model model) {
        shipFrame.show(model, shipFrame.ship(id), ShipTab.REQUESTS);
        return showList(id, ExportState.of(exported), exportId,
                ListQuery.of(ShipTab.REQUESTS.url(id), listRequest, TAB_SORTS), model);
    }

    private String showList(Long shipId, ExportState state, Long exportId, ListQuery list, Model model) {
        RequestExport export = requestService.findExport(exportId).orElse(null);
        if (!state.isDefault()) {
            list.filterKey("exported", state.key(), "request.filter.exported", state.labelKey());
        }
        if (export != null) {
            list.filter("exportId", export.getId(), "request.filter.export", dates.dateTime(export.getCreatedAt()));
        }
        model.addAttribute("list", list);
        model.addAttribute("requests", requestService.findApproved(shipId, state, export, list));
        model.addAttribute("exported", state.key());
        model.addAttribute("exportedOptions", ExportState.OPTIONS);
        model.addAttribute("emptyKey", state.isDefault() ? "request.list.empty.notExported" : "request.list.empty");
        model.addAttribute("notExportedCount", requestService.countNotExported(shipId));
        return "officerequest/list";
    }

    @GetMapping("/office/requests/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("request", requestService.getApproved(id));
        return "officerequest/detail";
    }

    // The file of an export. A GET that changes nothing, so the page an export comes back to can start it, and
    // "Download again" can repeat it.
    @GetMapping("/office/requests/exports/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        OfficeRequestService.CsvFile csv = requestService.csv(id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(csv.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(csv.content());
    }

    /**
     * Exports the checked requests. Where it comes back to: the Overview (returnTo=overview), the ship's Requests tab
     * (returnShipId) or the fleet list (with its ship filter, shipId). Nothing checked: back with a message. Some of
     * them exported before and not confirmed (no script, so no dialog): the plain confirmation page (P10), which
     * posts the same fields with confirmed=true.
     */
    @PostMapping(EXPORT)
    public String export(@Valid RequestExportForm form, BindingResult result,
                         @RequestParam(defaultValue = "false") boolean confirmed,
                         @RequestParam(required = false) Long shipId,
                         @RequestParam(required = false) Long returnShipId,
                         @RequestParam(required = false) String returnTo,
                         @AuthenticationPrincipal CurrentUser currentUser, Model model,
                         RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "request.export.noneSelected");
            return "redirect:" + back(shipId, returnShipId, returnTo, null);
        }
        if (!confirmed && requestService.countExported(form.ids()) > 0) {
            return confirmAgain(form.ids(), shipId, returnShipId, returnTo, model);
        }
        return exported(requestService.export(form.ids(), currentUser.getId()), shipId, returnShipId, returnTo,
                redirectAttributes);
    }

    // "Export all not exported (N)": every approved request not exported yet, of the list's ship (shipId on the
    // fleet list, returnShipId on the ship's tab) or of every ship. Nothing to export (another office user was
    // quicker): back with a message.
    @PostMapping("/office/requests/export-all")
    public String exportAll(@RequestParam(required = false) Long shipId,
                            @RequestParam(required = false) Long returnShipId,
                            @AuthenticationPrincipal CurrentUser currentUser,
                            RedirectAttributes redirectAttributes) {
        Long ship = returnShipId != null ? returnShipId : shipId;
        return exported(requestService.exportAllNotExported(ship, currentUser.getId()), shipId, returnShipId, null,
                redirectAttributes);
    }

    private String exported(Optional<RequestExport> export, Long shipId, Long returnShipId, String returnTo,
                            RedirectAttributes redirectAttributes) {
        if (export.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "request.export.nothing");
            return "redirect:" + back(shipId, returnShipId, returnTo, null);
        }
        RequestExport done = export.get();
        redirectAttributes.addFlashAttribute("successMessage", "request.export.done");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {done.getRequestIds().size()});
        redirectAttributes.addFlashAttribute("successDownloadUrl", "/office/requests/exports/" + done.getId());
        return "redirect:" + back(shipId, returnShipId, returnTo, done.getId());
    }

    // Where an export comes back to; with exportId, the list shows exactly that export's requests.
    private static String back(Long shipId, Long returnShipId, String returnTo, Long exportId) {
        if ("overview".equals(returnTo)) {
            return "/office";
        }
        UriComponentsBuilder url = UriComponentsBuilder.fromPath(
                returnShipId != null ? ShipTab.REQUESTS.url(returnShipId) : LIST);
        if (returnShipId == null && shipId != null) {
            url.queryParam("shipId", shipId);
        }
        if (exportId != null) {
            url.queryParam("exported", ExportState.EXPORTED.key()).queryParam("exportId", exportId);
        }
        return url.build().toUriString();
    }

    // The plain confirmation page for an export of requests exported before: the same texts as the dialog
    // (data-confirm-* on the list's form, js/confirm.js).
    private String confirmAgain(List<Long> ids, Long shipId, Long returnShipId, String returnTo, Model model) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ids", ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        fields.put("confirmed", "true");
        if (shipId != null) {
            fields.put("shipId", shipId.toString());
        }
        if (returnShipId != null) {
            fields.put("returnShipId", returnShipId.toString());
        }
        if (returnTo != null) {
            fields.put("returnTo", returnTo);
        }
        String cancelUrl = back(shipId, returnShipId, returnTo, null);
        ConfirmPages.Builder page = confirmPages.neutral()
                .title("request.export.again.title")
                .body("request.export.again.body")
                .confirmLabel("request.export.again")
                .action(EXPORT, null)
                .fields(fields)
                .cancelUrl(cancelUrl);
        if (returnShipId != null) {
            Ship ship = shipFrame.ship(returnShipId);
            page.crumb("nav.ships", "/office/ships").crumbText(ship.getName(), "/office/ships/" + ship.getId())
                    .crumb("ship.tab.requests", cancelUrl);
        } else if ("overview".equals(returnTo)) {
            page.crumb("nav.overview", cancelUrl);
        } else {
            page.crumb("nav.requests", cancelUrl);
        }
        return page.show(model);
    }
}
