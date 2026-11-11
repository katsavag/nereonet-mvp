package com.nereonix.nereonet_mvp.registration;

import java.util.Optional;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;

import jakarta.validation.Valid;

// Public pages (see SecurityConfig): the invitee opens the link from the invitation email.
@Controller
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping("/register")
    public String form(@RequestParam(required = false) String token, Model model) {
        RegistrationForm form = new RegistrationForm();
        form.setToken(token);
        model.addAttribute("form", form);
        return showForm(registrationService.findValidInvitation(token), model);
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult result, Model model,
                           RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showForm(registrationService.findValidInvitation(form.getToken()), model);
        }
        if (!registrationService.register(form.getToken(), form.getPassword())) {
            return "registration/invalid";
        }
        redirectAttributes.addFlashAttribute("successMessage", "registration.success");
        return "redirect:/login";
    }

    private String showForm(Optional<Invitation> invitation, Model model) {
        if (invitation.isEmpty()) {
            return "registration/invalid";
        }
        model.addAttribute("invitation", invitation.get());
        return "registration/register";
    }
}
