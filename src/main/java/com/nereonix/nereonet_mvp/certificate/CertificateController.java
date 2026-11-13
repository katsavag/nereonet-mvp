package com.nereonix.nereonet_mvp.certificate;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.confirm.ConfirmPages;
import com.nereonix.nereonet_mvp.core.export.XlsExport;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// Certificates (P20): every certificate, filtered by status (?status=), ship and "active ships only", with an Excel
// export of the list as filtered (.xls since P40; .xlsx in P39, CSV before). The ship page's Certificates tab is a matrix of the crew on board and the types
// they hold (/office/ships/{id}/certificates), or the list for that ship (/certificates/list), each with its export.
// A crew member's certificates are listed on the crew edit page. A certificate can have a file, its scan (P39):
// added, replaced or removed on its form, opened from the lists. Add and edit return to the page they were opened
// from (returnTo, CertificateReturn), else to the crew member's page or the ship's tab; delete returns to the crew
// member's page.
@Controller
public class CertificateController {

    private final CertificateService certificateService;
    private final ShipFrame shipFrame;
    private final ConfirmPages confirmPages;
    private final MessageSource messageSource;

    public CertificateController(CertificateService certificateService, ShipFrame shipFrame,
                                 ConfirmPages confirmPages, MessageSource messageSource) {
        this.certificateService = certificateService;
        this.shipFrame = shipFrame;
        this.confirmPages = confirmPages;
        this.messageSource = messageSource;
    }

    // The columns that sort (P19), the default first; the ship's list has no Ship column.
    private static final ListSort[] FLEET_SORTS = {ListSort.asc("expiresOn"), ListSort.asc("type"),
            ListSort.asc("crewMember"), ListSort.asc("ship"), ListSort.asc("number")};
    private static final ListSort[] TAB_SORTS = {ListSort.asc("expiresOn"), ListSort.asc("type"),
            ListSort.asc("crewMember"), ListSort.asc("number")};

    // ---------------------------------------------------------------------------------------------------------------
    // The fleet list
    // ---------------------------------------------------------------------------------------------------------------

    // status: the Status filter (CertificateStatus); activeShips: crew of active ships only; listRequest: search,
    // sort and page (P19).
    @GetMapping("/office/certificates")
    public String list(@RequestParam(required = false) Long shipId, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean activeShips,
                       @ModelAttribute ListRequest listRequest, Model model) {
        List<Ship> ships = certificateService.findAllShips();
        CertificateStatus filter = CertificateStatus.of(status);
        ListQuery list = fleetList(shipId, filter, activeShips, listRequest, ships);
        model.addAttribute("ships", ships);
        model.addAttribute("shipId", shipId);
        model.addAttribute("activeShips", activeShips);
        return showList(new CertificateService.Filter(shipId, activeShips, filter), list, model);
    }

    @GetMapping("/office/certificates/export")
    public Object export(@RequestParam(required = false) Long shipId,
                         @RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "false") boolean activeShips,
                         @ModelAttribute ListRequest listRequest, RedirectAttributes redirectAttributes) {
        CertificateStatus filter = CertificateStatus.of(status);
        ListQuery list = fleetList(shipId, filter, activeShips, listRequest, certificateService.findAllShips());
        List<Certificate> rows = certificateService.findAllCertificates(
                new CertificateService.Filter(shipId, activeShips, filter), list);
        return download(() -> certificateService.listXls("certificates", rows), list.getUrl(), redirectAttributes);
    }

    private ListQuery fleetList(Long shipId, CertificateStatus status, boolean activeShips, ListRequest listRequest,
                                List<Ship> ships) {
        ListQuery list = withStatus(ListQuery.of("/office/certificates", listRequest, FLEET_SORTS)
                .filterShip(shipId, ships), status);
        return list.filter("activeShips", activeShips ? "true" : null, "certificate.filter.activeShips", null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The ship page's Certificates tab: the matrix, and the list for this ship
    // ---------------------------------------------------------------------------------------------------------------

    @GetMapping("/office/ships/{id}/certificates")
    public String matrix(@PathVariable Long id, Model model) {
        Ship ship = shipFrame.ship(id);
        shipFrame.show(model, ship, ShipTab.CERTIFICATES);
        model.addAttribute("matrix", certificateService.matrix(ship));
        model.addAttribute("today", certificateService.today());
        model.addAttribute("returnTo", ShipTab.CERTIFICATES.url(id));
        return "certificate/matrix";
    }

    @GetMapping("/office/ships/{id}/certificates/export")
    public Object exportMatrix(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Ship ship = shipFrame.ship(id);
        return download(() -> certificateService.matrixXls("certificate-matrix-" + ship.getImoNumber(),
                certificateService.matrix(ship)), ShipTab.CERTIFICATES.url(id), redirectAttributes);
    }

    // The fleet list for one ship, without the ship filter and column.
    @GetMapping("/office/ships/{id}/certificates/list")
    public String shipList(@PathVariable Long id, @RequestParam(required = false) String status,
                           @ModelAttribute ListRequest listRequest, Model model) {
        Ship ship = shipFrame.ship(id);
        shipFrame.show(model, ship, ShipTab.CERTIFICATES);
        // Whom a certificate can be added for: without crew on board the empty list leads to the Crew tab (P25).
        model.addAttribute("crewOnBoard", !certificateService.findShipCrew(ship).isEmpty());
        CertificateStatus filter = CertificateStatus.of(status);
        ListQuery list = withStatus(ListQuery.of(shipListPath(id), listRequest, TAB_SORTS), filter);
        return showList(new CertificateService.Filter(id, false, filter), list, model);
    }

    @GetMapping("/office/ships/{id}/certificates/list/export")
    public Object exportShipList(@PathVariable Long id, @RequestParam(required = false) String status,
                                 @ModelAttribute ListRequest listRequest, RedirectAttributes redirectAttributes) {
        Ship ship = shipFrame.ship(id);
        CertificateStatus filter = CertificateStatus.of(status);
        ListQuery list = withStatus(ListQuery.of(shipListPath(id), listRequest, TAB_SORTS), filter);
        List<Certificate> rows = certificateService.findAllCertificates(
                new CertificateService.Filter(id, false, filter), list);
        return download(() -> certificateService.listXls("certificates-" + ship.getImoNumber(), rows), list.getUrl(),
                redirectAttributes);
    }

    // The workbook as a download or, when it has more rows than one .xls sheet holds (P40), back to the list it was
    // exported from with "Too many rows for one file: narrow the filters".
    private static Object download(Supplier<XlsExport.File> file, String back, RedirectAttributes redirectAttributes) {
        try {
            return file.get().response();
        } catch (XlsExport.TooManyRowsException e) {
            redirectAttributes.addFlashAttribute("errorMessage", XlsExport.TooManyRowsException.MESSAGE);
            return "redirect:" + back;
        }
    }

    private static String shipListPath(Long shipId) {
        return ShipTab.CERTIFICATES.url(shipId) + "/list";
    }

    private String showList(CertificateService.Filter filter, ListQuery list, Model model) {
        CertificateStatus status = filter.status();
        model.addAttribute("list", list);
        Page<Certificate> certificates = certificateService.findCertificates(filter, list);
        model.addAttribute("certificates", certificates);
        // P43: the snoozed rows of the page (muted, with Undo); the others get Snooze.
        model.addAttribute("snoozes", certificateService.snoozesOf(certificates.getContent()));
        // Empty without a filter (P25): no certificate yet at all, or none expiring within 90 days.
        model.addAttribute("anyCertificates", certificateService.anyCertificates(filter.shipId()));
        model.addAttribute("status", status.key());
        model.addAttribute("statusOption", status.isOption());
        model.addAttribute("statusLabel", label(status));
        model.addAttribute("statusOptions", CertificateStatus.OPTIONS);
        model.addAttribute("exportUrl", list.urlAt(list.getPath() + "/export"));
        model.addAttribute("returnTo", list.getUrl());
        model.addAttribute("today", certificateService.today());
        return "certificate/list";
    }

    // The Status chip; none for the default (the list without a status).
    private ListQuery withStatus(ListQuery list, CertificateStatus status) {
        return status.isDefault() ? list : list.filter("status", status.key(), "certificate.status", label(status));
    }

    // "Expired", "Within 30 days", "Expired or within 30 days"; a combination without a label of its own lists its
    // states.
    private String label(CertificateStatus status) {
        var locale = LocaleContextHolder.getLocale();
        String joined = String.join(", ", status.stateLabelKeys().stream()
                .map(key -> messageSource.getMessage(key, null, locale)).toList());
        return messageSource.getMessage(status.labelKey(), null, joined, locale);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Add and edit
    // ---------------------------------------------------------------------------------------------------------------

    // userId and type: prefilled from a matrix cell (P20); a userId not of this ship's crew is ignored.
    @GetMapping("/office/ships/{id}/certificates/new")
    public String newShipForm(@PathVariable Long id, @RequestParam(required = false) Long userId,
                              @RequestParam(required = false) String type,
                              @RequestParam(required = false) String returnTo, Model model) {
        Ship ship = shipFrame.ship(id);
        ShipCertificateForm form = new ShipCertificateForm();
        if (userId != null && isShipCrew(ship, userId)) {
            form.setUserId(userId);
        }
        form.setType(type);
        model.addAttribute("form", form);
        return showShipForm(ship, returnTo, model);
    }

    @PostMapping("/office/ships/{id}/certificates/new")
    public String createForShip(@PathVariable Long id, @Valid @ModelAttribute("form") ShipCertificateForm form,
                                BindingResult result, @RequestParam(required = false) String returnTo, Model model,
                                RedirectAttributes redirectAttributes) {
        Ship ship = shipFrame.ship(id);
        // Only a crew member of this ship: a posted id of someone elsewhere is refused like an empty choice.
        if (form.getUserId() != null && !isShipCrew(ship, form.getUserId())) {
            result.rejectValue("userId", "certificate.error.crewMemberRequired");
        }
        if (result.hasErrors()) {
            return showShipForm(ship, returnTo, model);
        }
        certificateService.create(form.getUserId(), form);
        redirectAttributes.addFlashAttribute("successMessage", "certificate.created");
        return "redirect:" + CertificateReturn.or(returnTo, ShipTab.CERTIFICATES.url(id));
    }

    private boolean isShipCrew(Ship ship, Long userId) {
        return certificateService.findShipCrew(ship).stream().map(User::getId).anyMatch(userId::equals);
    }

    @GetMapping("/office/crew/{userId}/certificates/new")
    public String newForm(@PathVariable Long userId, @RequestParam(required = false) String type,
                          @RequestParam(required = false) String returnTo, Model model) {
        CertificateForm form = new CertificateForm();
        form.setType(type);
        model.addAttribute("form", form);
        return showForm(userId, null, returnTo, model);
    }

    @PostMapping("/office/crew/{userId}/certificates/new")
    public String create(@PathVariable Long userId, @Valid @ModelAttribute("form") CertificateForm form,
                         BindingResult result, @RequestParam(required = false) String returnTo, Model model,
                         RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showForm(userId, null, returnTo, model);
        }
        certificateService.create(userId, form);
        redirectAttributes.addFlashAttribute("successMessage", "certificate.created");
        return "redirect:" + CertificateReturn.or(returnTo, crewUrl(userId));
    }

    @GetMapping("/office/crew/{userId}/certificates/{id}/edit")
    public String editForm(@PathVariable Long userId, @PathVariable Long id,
                           @RequestParam(required = false) String returnTo, Model model) {
        Certificate certificate = certificateService.getCertificate(userId, id);
        model.addAttribute("form", CertificateForm.of(certificate));
        return showForm(userId, certificate, returnTo, model);
    }

    @PostMapping("/office/crew/{userId}/certificates/{id}/edit")
    public String update(@PathVariable Long userId, @PathVariable Long id,
                         @Valid @ModelAttribute("form") CertificateForm form, BindingResult result,
                         @RequestParam(required = false) String returnTo, Model model,
                         RedirectAttributes redirectAttributes) {
        Certificate certificate = certificateService.getCertificate(userId, id);
        if (result.hasErrors()) {
            return showForm(userId, certificate, returnTo, model);
        }
        certificateService.update(userId, id, form);
        redirectAttributes.addFlashAttribute("successMessage", "certificate.updated");
        return "redirect:" + CertificateReturn.or(returnTo, crewUrl(userId));
    }

    // The certificate's file (P39), opened in the browser; 404 without one. The crew member opens their own from My
    // profile (ProfileController).
    @GetMapping("/office/crew/{userId}/certificates/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable Long userId, @PathVariable Long id) {
        return certificateService.file(userId, id).response();
    }

    private static String crewUrl(Long userId) {
        return "/office/crew/" + userId + "/edit";
    }

    // The confirmation page: where Delete leads when js/confirm.js did not load (P10).
    @GetMapping("/office/crew/{userId}/certificates/{id}/delete")
    public String confirmDelete(@PathVariable Long userId, @PathVariable Long id, Model model) {
        Certificate certificate = certificateService.getCertificate(userId, id);
        User member = certificateService.getCrewMember(userId);
        String memberUrl = crewUrl(userId);
        return confirmPages.danger()
                .title("certificate.delete.confirm.title", certificate.getType())
                .body("certificate.delete.confirm.body", certificate.getType(), member.getFullName())
                .confirmLabel("certificate.delete")
                .action("/office/crew/" + userId + "/certificates/" + id + "/delete", null)
                .cancelUrl(memberUrl)
                .crumb("nav.crew", "/office/crew")
                .crumbText(member.getFullName(), memberUrl)
                .show(model);
    }

    @PostMapping("/office/crew/{userId}/certificates/{id}/delete")
    public String delete(@PathVariable Long userId, @PathVariable Long id, RedirectAttributes redirectAttributes) {
        certificateService.delete(userId, id);
        redirectAttributes.addFlashAttribute("successMessage", "certificate.deleted");
        return "redirect:" + crewUrl(userId);
    }

    // Add certificate from the ship page: the ship's crew to choose from. backUrl: Cancel, and returnTo for the form.
    private String showShipForm(Ship ship, String returnTo, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("shipCrew", certificateService.findShipCrew(ship));
        model.addAttribute("certificate", null);
        return showFormCommon(CertificateReturn.clean(returnTo), ShipTab.CERTIFICATES.url(ship.getId()), model);
    }

    // certificate is null when adding.
    private String showForm(Long userId, Certificate certificate, String returnTo, Model model) {
        model.addAttribute("member", certificateService.getCrewMember(userId));
        model.addAttribute("certificate", certificate);
        return showFormCommon(CertificateReturn.clean(returnTo), crewUrl(userId), model);
    }

    private String showFormCommon(String returnTo, String fallback, Model model) {
        model.addAttribute("returnTo", returnTo);
        model.addAttribute("backUrl", returnTo == null ? fallback : returnTo);
        model.addAttribute("typeSuggestions", certificateService.typeSuggestions());
        return "certificate/form";
    }
}
