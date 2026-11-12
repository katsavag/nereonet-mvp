package com.nereonix.nereonet_mvp.shiphome;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.masterhome.MasterHomeService;

// The crew home page (P26): every ship role lands here after sign-in (SecurityConfig: /ship/** is for ship roles).
// The Master gets their own home page (masterhome) at the same address; the Chief cook and Crew members this one.
// The office home page is in the dashboard package.
@Controller
public class ShipHomeController {

    private final ShipHomeService shipHomeService;
    private final MasterHomeService masterHomeService;

    public ShipHomeController(ShipHomeService shipHomeService, MasterHomeService masterHomeService) {
        this.shipHomeService = shipHomeService;
        this.masterHomeService = masterHomeService;
    }

    @GetMapping("/ship")
    public String home(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        if (currentUser.getRole() == Role.MASTER) {
            model.addAttribute("home", masterHomeService.load(currentUser.getUsername()));
            return "masterhome/home";
        }
        model.addAttribute("home", shipHomeService.load(currentUser.getUsername()));
        return "shiphome/home";
    }
}
