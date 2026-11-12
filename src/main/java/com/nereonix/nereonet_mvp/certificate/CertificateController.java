package com.nereonix.nereonet_mvp.certificate;

import java.util.List;

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
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// A crew member's certificates are listed on the crew edit page; add, edit and delete return there.
// The ship page's Certificates tab lists one ship's expiring certificates; Add certificate there picks the crew
// member from that ship and returns to the tab.
@Controller
public class CertificateController {

    private final CertificateService certificateService;
    private final ShipFrame shipFrame;
    private final ConfirmPages confirmPages;

    public CertificateController(CertificateService certificateService, ShipFrame shipFrame,
                                 ConfirmPages confirmPages) {
        this.certificateService = certificateService;
        this.shipFrame = shipFrame;
        this.confirmPages = confirmPages;
    }

    // The columns that sort (P19), the default first; the ship's tab has no Ship column.
    private static final ListSort[] FLEET_SORTS = {ListSort.asc("expiresOn"), ListSort.asc("type"),
            ListSort.asc("crewMember"), ListSort.asc("ship"), ListSort.asc("number")};
    private static final ListSort[] TAB_SORTS = {ListSort.asc("expiresOn"), ListSort.asc("type"),
            ListSort.asc("crewMember"), ListSort.asc("number")};

    // due: the Status filter (CertificateDue key); listRequest: search, sort and page (P19).
    @GetMapping("/office/certificates")
    public String expiring(@RequestParam(required = false) Long shipId, @RequestParam(required = false) String due,
                           @ModelAttribute ListRequest listRequest, Model model) {
        List<Ship> ships = certificateService.findAllShips();
        CertificateDue step = CertificateDue.of(due).orElse(null);
        ListQuery list = withDue(ListQuery.of("/office/certificates", listRequest, FLEET_SORTS)
                .filterShip(shipId, ships), step);
        model.addAttribute("ships", ships);
        model.addAttribute("shipId", shipId);
        return showList(shipId, step, list, model);
    }

    // The same list for one ship, without the ship filter and column.
    @GetMapping("/office/ships/{id}/certificates")
    public String shipCertificates(@PathVariable Long id, @RequestParam(required = false) String due,
                                   @ModelAttribute ListRequest listRequest, Model model) {
        shipFrame.show(model, shipFrame.ship(id), ShipTab.CERTIFICATES);
        CertificateDue step = CertificateDue.of(due).orElse(null);
        return showList(id, step, withDue(ListQuery.of(ShipTab.CERTIFICATES.url(id), listRequest, TAB_SORTS), step),
                model);
    }

    private String showList(Long shipId, CertificateDue due, ListQuery list, Model model) {
        model.addAttribute("list", list);
        model.addAttribute("certificates", certificateService.findExpiring(shipId, due, list));
        model.addAttribute("due", due == null ? null : due.key());
        model.addAttribute("dues", CertificateDue.values());
        model.addAttribute("today", certificateService.today());
        return "certificate/list";
    }

    private static ListQuery withDue(ListQuery list, CertificateDue due) {
        return due == null ? list
                : list.filterKey("due", due.key(), "certificate.status", "certificate.filter.due." + due.key());
    }

    @GetMapping("/office/ships/{id}/certificates/new")
    public String newShipForm(@PathVariable Long id, Model model) {
        model.addAttribute("form", new ShipCertificateForm());
        return showShipForm(shipFrame.ship(id), model);
    }

    @PostMapping("/office/ships/{id}/certificates/new")
    public String createForShip(@PathVariable Long id, @Valid @ModelAttribute("form") ShipCertificateForm form,
                                BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        Ship ship = shipFrame.ship(id);
        // Only a crew member of this ship: a posted id of someone elsewhere is refused like an empty choice.
        if (form.getUserId() != null && certificateService.findShipCrew(ship).stream()
                .map(User::getId).noneMatch(form.getUserId()::equals)) {
            result.rejectValue("userId", "certificate.error.crewMemberRequired");
        }
        if (result.hasErrors()) {
            return showShipForm(ship, model);
        }
        certificateService.create(form.getUserId(), form.getType(), form.getNumber(), form.getExpiresOn());
        redirectAttributes.addFlashAttribute("successMessage", "certificate.created");
        return "redirect:" + ShipTab.CERTIFICATES.url(id);
    }

    @GetMapping("/office/crew/{userId}/certificates/new")
    public String newForm(@PathVariable Long userId, Model model) {
        model.addAttribute("form", new CertificateForm());
        return showForm(userId, null, model);
    }

    @PostMapping("/office/crew/{userId}/certificates/new")
    public String create(@PathVariable Long userId, @Valid @ModelAttribute("form") CertificateForm form,
                         BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showForm(userId, null, model);
        }
        certificateService.create(userId, form.getType(), form.getNumber(), form.getExpiresOn());
        redirectAttributes.addFlashAttribute("successMessage", "certificate.created");
        return "redirect:/office/crew/" + userId + "/edit";
    }

    @GetMapping("/office/crew/{userId}/certificates/{id}/edit")
    public String editForm(@PathVariable Long userId, @PathVariable Long id, Model model) {
        Certificate certificate = certificateService.getCertificate(userId, id);
        model.addAttribute("form", CertificateForm.of(certificate));
        return showForm(userId, certificate, model);
    }

    @PostMapping("/office/crew/{userId}/certificates/{id}/edit")
    public String update(@PathVariable Long userId, @PathVariable Long id,
                         @Valid @ModelAttribute("form") CertificateForm form, BindingResult result, Model model,
                         RedirectAttributes redirectAttributes) {
        Certificate certificate = certificateService.getCertificate(userId, id);
        if (result.hasErrors()) {
            return showForm(userId, certificate, model);
        }
        certificateService.update(userId, id, form.getType(), form.getNumber(), form.getExpiresOn());
        redirectAttributes.addFlashAttribute("successMessage", "certificate.updated");
        return "redirect:/office/crew/" + userId + "/edit";
    }

    // The confirmation page: where Delete leads when js/confirm.js did not load (P10).
    @GetMapping("/office/crew/{userId}/certificates/{id}/delete")
    public String confirmDelete(@PathVariable Long userId, @PathVariable Long id, Model model) {
        Certificate certificate = certificateService.getCertificate(userId, id);
        User member = certificateService.getCrewMember(userId);
        String memberUrl = "/office/crew/" + userId + "/edit";
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
        return "redirect:/office/crew/" + userId + "/edit";
    }

    // Add certificate from the ship page: no member yet, the ship's crew to choose from.
    private String showShipForm(Ship ship, Model model) {
        model.addAttribute("ship", ship);
        model.addAttribute("shipCrew", certificateService.findShipCrew(ship));
        model.addAttribute("certificate", null);
        return "certificate/form";
    }

    // certificate is null when adding.
    private String showForm(Long userId, Certificate certificate, Model model) {
        model.addAttribute("member", certificateService.getCrewMember(userId));
        model.addAttribute("certificate", certificate);
        return "certificate/form";
    }
}
