package com.nereonix.nereonet_mvp.dashboard;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

// The office home page. /office/** is limited to OFFICE_USER in SecurityConfig.
// type and shipId filter the "Needs attention" list (the full list of one kind, or of one ship's open items):
// /office?type=acknowledgements&shipId=3. Values: AttentionType.Group.param().
@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/office")
    public String dashboard(@RequestParam(required = false) String type,
                            @RequestParam(required = false) Long shipId, Model model) {
        AttentionType.Group group = AttentionType.Group.fromParam(type);
        DashboardService.Dashboard dashboard = dashboardService.load(group, shipId);
        model.addAttribute("dashboard", dashboard);
        // This page with its filter, for the certificate form to come back to (P20).
        UriComponentsBuilder here = UriComponentsBuilder.fromPath("/office");
        if (group != null) {
            here.queryParam("type", group.param());
        }
        if (dashboard.ship() != null) {
            here.queryParam("shipId", dashboard.ship().id());
        }
        model.addAttribute("overviewUrl", here.build().toUriString());
        return "dashboard/dashboard";
    }
}
