package com.nereonix.nereonet_mvp.audit;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

// /office/** is limited to OFFICE_USER in SecurityConfig. Read-only: there is no edit or delete.
@Controller
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/office/audit")
    public String list(@ModelAttribute("filter") AuditFilterForm filter, Model model) {
        model.addAttribute("entries",
                auditLogService.search(filter.getShipId(), filter.getFrom(), filter.getTo(), filter.getPage()));
        model.addAttribute("ships", auditLogService.findShips());
        return "audit/list";
    }
}
