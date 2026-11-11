package com.nereonix.nereonet_mvp.registration;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.security.SignIn;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

// Public pages (see SecurityConfig): the invitee opens the link from the invitation email.
@Controller
public class RegistrationController {

    private final RegistrationService registrationService;
    private final SignIn signIn;
    // Free text shown on the "link is invalid or has expired" page (P13); blank: not shown.
    private final String supportContact;
    private final int invitationValidDays;

    public RegistrationController(RegistrationService registrationService, SignIn signIn,
                                  @Value("${app.support-contact:}") String supportContact,
                                  @Value("${app.invitation.valid-days:7}") int invitationValidDays) {
        this.registrationService = registrationService;
        this.signIn = signIn;
        this.supportContact = supportContact == null || supportContact.isBlank() ? null : supportContact.strip();
        this.invitationValidDays = invitationValidDays;
    }

    @GetMapping("/register")
    public String form(@RequestParam(required = false) String token, Model model) {
        RegistrationForm form = new RegistrationForm();
        form.setToken(token);
        model.addAttribute("form", form);
        return showForm(registrationService.findValidInvitation(token), model);
    }

    // Registered: signed in at once and sent to their home page (P13), not to the sign-in form.
    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult result, Model model,
                           RedirectAttributes redirectAttributes,
                           HttpServletRequest request, HttpServletResponse response) {
        if (result.hasErrors()) {
            return showForm(registrationService.findValidInvitation(form.getToken()), model);
        }
        Optional<User> user = registrationService.register(form.getToken(), form.getPassword());
        if (user.isEmpty()) {
            return invalid(model);
        }
        String home = signIn.signIn(user.get().getEmail(), request, response);
        redirectAttributes.addFlashAttribute("successMessage", "registration.success");
        return "redirect:" + home;
    }

    private String showForm(Optional<Invitation> invitation, Model model) {
        if (invitation.isEmpty()) {
            return invalid(model);
        }
        model.addAttribute("invitation", invitation.get());
        return "registration/register";
    }

    private String invalid(Model model) {
        model.addAttribute("supportContact", supportContact);
        model.addAttribute("invitationValidDays", invitationValidDays);
        return "registration/invalid";
    }
}
