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
}
