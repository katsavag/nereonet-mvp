package com.nereonix.nereonet_mvp.passwordreset;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.passwordreset.PasswordReset;
import com.nereonix.nereonet_mvp.core.passwordreset.PasswordResetService;
import com.nereonix.nereonet_mvp.core.security.SignIn;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Forgot password (P14). Public pages (see SecurityConfig):
 *   /password/forgot       one email field; always answered with a redirect to /password/forgot/sent
 *   /password/forgot/sent  "If an account exists for this address, we sent a link", the same page for every address
 *   /password/reset        the new password, from the emailed link; afterwards the user is signed in
 */
@Controller
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final SignIn signIn;

    public PasswordResetController(PasswordResetService passwordResetService, SignIn signIn) {
        this.passwordResetService = passwordResetService;
        this.signIn = signIn;
    }

    // Phone keyboards often add a space after an address; it is not part of it.
    @InitBinder("form")
    void trimEmail(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, "email", new StringTrimmerEditor(false));
    }

    @GetMapping("/password/forgot")
    public String forgotForm(Model model) {
        model.addAttribute("form", new ForgotPasswordForm());
        return "passwordreset/forgot";
    }

    // The same redirect whether or not the address has an account; the page behind it holds no address.
    @PostMapping("/password/forgot")
    public String forgot(@Valid @ModelAttribute("form") ForgotPasswordForm form, BindingResult result, Locale locale) {
        if (result.hasErrors()) {
            return "passwordreset/forgot";
        }
        passwordResetService.request(form.getEmail(), locale);
        return "redirect:/password/forgot/sent";
    }

    @GetMapping("/password/forgot/sent")
    public String sent(Model model) {
        model.addAttribute("validMinutes", PasswordResetService.VALID_FOR.toMinutes());
        return "passwordreset/sent";
    }

    @GetMapping("/password/reset")
    public String resetForm(@RequestParam(required = false) String token, Model model) {
        Optional<PasswordReset> reset = passwordResetService.findValid(token);
        if (reset.isEmpty()) {
            return invalid(model);
        }
        ResetPasswordForm form = new ResetPasswordForm();
        form.setToken(token);
        model.addAttribute("form", form);
        // The account the link is for, as plain text on the page.
        model.addAttribute("email", reset.get().getEmail());
        return "passwordreset/reset";
    }

    // Reset: signed in at once and sent to the home page, as after registration (P13).
    @PostMapping("/password/reset")
    public String reset(@Valid @ModelAttribute("form") ResetPasswordForm form, BindingResult result, Model model,
                        RedirectAttributes redirectAttributes,
                        HttpServletRequest request, HttpServletResponse response) {
        Optional<PasswordReset> reset = passwordResetService.findValid(form.getToken());
        if (reset.isEmpty()) {
            return invalid(model);
        }
        if (result.hasErrors()) {
            model.addAttribute("email", reset.get().getEmail());
            return "passwordreset/reset";
        }
        Optional<User> user = passwordResetService.reset(form.getToken(), form.getPassword());
        if (user.isEmpty()) {
            return invalid(model);
        }
        String home = signIn.signIn(user.get().getEmail(), request, response);
        redirectAttributes.addFlashAttribute("successMessage", "passwordReset.success");
        return "redirect:" + home;
    }

    // Used, expired, or not a reset link at all: say so, with the way to a new link.
    private String invalid(Model model) {
        model.addAttribute("validMinutes", PasswordResetService.VALID_FOR.toMinutes());
        return "passwordreset/invalid";
    }
}
