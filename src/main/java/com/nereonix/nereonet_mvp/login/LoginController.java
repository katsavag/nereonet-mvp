package com.nereonix.nereonet_mvp.login;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// POST /login and POST /logout are handled by Spring Security (see SecurityConfig).
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login/login";
    }

    // Placeholder landing page after login for ship crew. The office home page is in the dashboard package.
    @GetMapping("/ship")
    public String ship() {
        return "login/ship";
    }
}
