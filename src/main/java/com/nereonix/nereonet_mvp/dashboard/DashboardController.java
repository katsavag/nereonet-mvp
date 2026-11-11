package com.nereonix.nereonet_mvp.dashboard;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
        model.addAttribute("dashboard", dashboardService.load(AttentionType.Group.fromParam(type), shipId));
        return "dashboard/dashboard";
    }
}
