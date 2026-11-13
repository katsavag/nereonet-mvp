package com.nereonix.nereonet_mvp.audit;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.time.Dates;

// /office/** is limited to OFFICE_USER in SecurityConfig. Read-only: there is no edit or delete.
@Controller
public class AuditLogController {

    // The columns that sort (P19), the default first.
    private static final ListSort[] SORTS =
            {ListSort.desc("when"), ListSort.asc("who"), ListSort.asc("action"), ListSort.asc("ship")};

    // P39: 20 entries a page by default, 50 or 100 on request; the other lists keep 25 and 50.
    static final List<Integer> PAGE_SIZES = List.of(20, 50, 100);

    private final AuditLogService auditLogService;
    private final Dates dates;

    public AuditLogController(AuditLogService auditLogService, Dates dates) {
        this.auditLogService = auditLogService;
        this.dates = dates;
    }

    // filter: ship and UTC days; listRequest: search, sort, page and page size (P19; 20 a page, P39). A day outside
    // the sensible range (P39: @PlausibleDate) or not a date shows its error under the field and filters nothing.
    @GetMapping("/office/audit")
    public String list(@Validated @ModelAttribute("filter") AuditFilterForm filter, BindingResult bindingResult,
                       @ModelAttribute ListRequest listRequest, Model model) {
        if (bindingResult.hasFieldErrors("from")) {
            filter.setFrom(null);
        }
        if (bindingResult.hasFieldErrors("to")) {
            filter.setTo(null);
        }
        List<Ship> ships = auditLogService.findShips();
        ListQuery list = ListQuery.withSizes("/office/audit", listRequest, PAGE_SIZES, SORTS)
                .filterShip(filter.getShipId(), ships)
                .filter("from", filter.getFrom(), "audit.filter.from", filter.getFrom() == null ? null : dates.date(filter.getFrom()))
                .filter("to", filter.getTo(), "audit.filter.to", filter.getTo() == null ? null : dates.date(filter.getTo()));
        model.addAttribute("list", list);
        model.addAttribute("entries",
                auditLogService.search(filter.getShipId(), filter.getFrom(), filter.getTo(), list));
        model.addAttribute("ships", ships);
        return "audit/list";
    }
}
