package com.nereonix.nereonet_mvp.dashboard;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.ship.Countries;

// The office home page. /office/** is limited to OFFICE_USER in SecurityConfig.
// type and shipId filter the "Needs attention" list (one kind, or one ship's open items):
// /office?type=acknowledgements&shipId=3. Values: AttentionType.Group.param(). P43: the list is paged (P19, 10 a
// page) and sorted by urgency (the default) or by date (?sort=date); its state travels as returnTo with the actions.
@Controller
public class DashboardController {

    static final String PATH = "/office";

    private static final ListSort[] SORTS = {ListSort.asc(DashboardService.SORT_URGENCY),
            ListSort.asc(DashboardService.SORT_DATE)};

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping(PATH)
    public String dashboard(@RequestParam(required = false) String type,
                            @RequestParam(required = false) Long shipId, @ModelAttribute ListRequest listRequest,
                            Model model) {
        AttentionType.Group group = AttentionType.Group.fromParam(type);
        ListQuery list = ListQuery.of(PATH, listRequest, SORTS);
        if (group != null) {
            list.filterKey("type", group.param(), "dashboard.attention.kind", "dashboard.chip." + group.name());
        }
        DashboardService.Dashboard dashboard = dashboardService.load(group, shipId, list);
        if (dashboard.ship() != null) {
            list.filter("shipId", dashboard.ship().id(), "list.filter.ship", dashboard.ship().name());
        }
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("list", list);
        model.addAttribute("groups", AttentionType.Group.values());
        // First run (P25): the setup checklist, until every step is done.
        model.addAttribute("setup", dashboardService.setup());
        // This page as it is (filter, sort, page), for the forms and links to come back to (P20, P43).
        model.addAttribute("overviewUrl", list.getUrl());
        // P43: the fleet card's flags.
        model.addAttribute("countries", Countries.labels(LocaleContextHolder.getLocale()));
        return "dashboard/dashboard";
    }
}
