package com.nereonix.nereonet_mvp.approval;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.request.RequestService.Decision;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;

// The Master's approvals (P33): the item requests of their own ship, and approving or declining one (P34).
// SecurityConfig limits /ship/approvals/** to MASTER (the Chief cook and crew members get 403). English only, as
// every crew screen.
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
        model.addAttribute("declineForm", new DeclineForm());
        return "approval/detail";
    }

    @PostMapping("/ship/approvals/{id}/approve")
    public String approve(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                          RedirectAttributes redirectAttributes) {
        return afterDecision(approvalService.approve(currentUser.getUsername(), id), id, "approval.approved",
                redirectAttributes);
    }

    // An empty reason shows the request again with the form open and its error; nothing is saved.
    @PostMapping("/ship/approvals/{id}/decline")
    public String decline(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                          @Validated @ModelAttribute("declineForm") DeclineForm declineForm, BindingResult bindingResult,
                          Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("detail", approvalService.detail(currentUser.getUsername(), id));
            return "approval/detail";
        }
        return afterDecision(approvalService.decline(currentUser.getUsername(), id, declineForm.getReason()), id,
                "approval.declined", redirectAttributes);
    }

    // Decided: back to the list with a success message. Already decided or cancelled (a second click, another tab):
    // nothing changed, back to the request with a message saying so.
    private static String afterDecision(Decision decision, Long id, String successKey,
                                        RedirectAttributes redirectAttributes) {
        return switch (decision) {
            case DECIDED -> {
                redirectAttributes.addFlashAttribute("successMessage", successKey);
                yield "redirect:/ship/approvals";
            }
            case NOT_PENDING -> {
                redirectAttributes.addFlashAttribute("errorMessage", "approval.notPending");
                yield "redirect:/ship/approvals/" + id;
            }
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }
}
