package com.nereonix.nereonet_mvp.certificate;

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

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig.
// A crew member's certificates are listed on the crew edit page; add, edit and delete return there.
@Controller
public class CertificateController {

    private final CertificateService certificateService;

    public CertificateController(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @GetMapping("/office/certificates")
    public String expiring(@RequestParam(required = false) Long shipId, Model model) {
        model.addAttribute("certificates", certificateService.findExpiring(shipId));
        model.addAttribute("ships", certificateService.findAllShips());
        model.addAttribute("shipId", shipId);
        model.addAttribute("today", certificateService.today());
        return "certificate/list";
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

    @PostMapping("/office/crew/{userId}/certificates/{id}/delete")
    public String delete(@PathVariable Long userId, @PathVariable Long id, RedirectAttributes redirectAttributes) {
        certificateService.delete(userId, id);
        redirectAttributes.addFlashAttribute("successMessage", "certificate.deleted");
        return "redirect:/office/crew/" + userId + "/edit";
    }

    // certificate is null when adding.
    private String showForm(Long userId, Certificate certificate, Model model) {
        model.addAttribute("member", certificateService.getCrewMember(userId));
        model.addAttribute("certificate", certificate);
        return "certificate/form";
    }
}
