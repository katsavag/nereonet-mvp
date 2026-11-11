package com.nereonix.nereonet_mvp.dashboard;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// The office home page. /office/** is limited to OFFICE_USER in SecurityConfig.
@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/office")
    public String dashboard(Model model) {
        model.addAttribute("dashboard", dashboardService.load());
        return "dashboard/dashboard";
    }
}
