package com.nereonix.nereonet_mvp.home;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;

// "/" requires login (see SecurityConfig), so signed-out users are sent to /login first.
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal CurrentUser user) {
        return "redirect:" + user.getHomeUrl();
    }
}
