package com.nereonix.nereonet_mvp.shiphome;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.masterhome.MasterHomeService;
import com.nereonix.nereonet_mvp.requirement.ComplianceService;
import com.nereonix.nereonet_mvp.stores.StoresService;

// The crew home page (P26): every ship role lands here after sign-in (SecurityConfig: /ship/** is for ship roles).
// The Master gets their own home page (masterhome) at the same address; the Chief cook and Crew members this one.
// The office home page is in the dashboard package.
@Controller
public class ShipHomeController {

    private final ShipHomeService shipHomeService;
    private final MasterHomeService masterHomeService;
    private final ComplianceService complianceService;
    private final UserRepository userRepository;
    private final StoresService storesService;

    public ShipHomeController(ShipHomeService shipHomeService, MasterHomeService masterHomeService,
                              ComplianceService complianceService, UserRepository userRepository,
                              StoresService storesService) {
        this.storesService = storesService;
        this.shipHomeService = shipHomeService;
        this.masterHomeService = masterHomeService;
        this.complianceService = complianceService;
        this.userRepository = userRepository;
    }

    @GetMapping("/ship")
    public String home(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        // P42: "2 required documents missing" (expired included), linking to My profile; nothing when none is.
        model.addAttribute("documentsMissing", userRepository.findByEmail(currentUser.getUsername())
                .map(user -> complianceService.of(user).missing()).orElse(0L));
        if (currentUser.getRole() == Role.MASTER) {
            model.addAttribute("home", masterHomeService.load(currentUser.getUsername()));
            return "masterhome/home";
        }
        model.addAttribute("home", shipHomeService.load(currentUser.getUsername()));
        // P52: the Chief cook's Stores card, Low and Out of stock; null for crew members.
        model.addAttribute("stores", currentUser.getRole() == Role.CHIEF_COOK
                ? storesService.counts(currentUser.getUsername()) : null);
        return "shiphome/home";
    }
}
