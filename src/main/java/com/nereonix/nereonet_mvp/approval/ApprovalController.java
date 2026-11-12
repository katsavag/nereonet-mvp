package com.nereonix.nereonet_mvp.approval;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;

// The Master's approvals (P33): the item requests of their own ship. SecurityConfig limits /ship/approvals/** to
// MASTER (the Chief cook and crew members get 403). English only, as every crew screen.
@Controller
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/ship/approvals")
    public String list(@AuthenticationPrincipal CurrentUser currentUser,
                       @RequestParam(required = false) String tab, Model model) {
        model.addAttribute("approvals", approvalService.list(currentUser.getUsername(), ApprovalTab.of(tab)));
        return "approval/list";
    }

    @GetMapping("/ship/approvals/{id}")
    public String detail(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        model.addAttribute("detail", approvalService.detail(currentUser.getUsername(), id));
        return "approval/detail";
    }
}
