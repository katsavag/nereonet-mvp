package com.nereonix.nereonet_mvp.shiphome;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;

// The crew home page (P26): every ship role lands here after sign-in (SecurityConfig: /ship/** is for ship roles).
// The office home page is in the dashboard package.
@Controller
public class ShipHomeController {

    private final ShipHomeService shipHomeService;

    public ShipHomeController(ShipHomeService shipHomeService) {
        this.shipHomeService = shipHomeService;
    }

    @GetMapping("/ship")
    public String home(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        model.addAttribute("home", shipHomeService.load(currentUser.getUsername()));
        return "shiphome/home";
    }
}
