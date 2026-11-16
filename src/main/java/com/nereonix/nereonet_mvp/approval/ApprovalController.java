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
import org.springframework.web.servlet.view.RedirectView;

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.request.RequestService.Decision;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;

// The Master's approvals: the item requests of their own ship, and approving or declining one.
// SecurityConfig limits /ship/approvals/** to MASTER (the Chief cook and crew members get 403). English only, as
// every crew screen.
@Controller
public class ApprovalController {

    private static final String LIST = "/ship/approvals";

    // Each tab has its own order: there is nothing to sort, but ListQuery needs its default.
    private static final ListSort[] SORTS = {ListSort.asc("sent")};

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    // listRequest: the page and the page size (10 a page as on every list); the tab is kept in the pager's links.
    // back: what a pending row adds to its link, so the request knows the page it was opened from.
    @GetMapping(LIST)
    public String list(@AuthenticationPrincipal CurrentUser currentUser,
                       @RequestParam(required = false) String tab, @ModelAttribute ListRequest listRequest,
                       Model model) {
        ApprovalTab chosen = ApprovalTab.of(tab);
        ListQuery list = list(chosen, listRequest);
        model.addAttribute("list", list);
        model.addAttribute("approvals", approvalService.list(currentUser.getUsername(), chosen, list));
        model.addAttribute("back", back(listRequest));
        return "approval/list";
    }

    // ?tab=decided (old bookmarks and links): that tab held approved and declined requests, so it goes to Approved.
    @GetMapping(value = "/ship/approvals", params = "tab=decided")
    public RedirectView decided() {
        RedirectView redirect = new RedirectView("/ship/approvals?tab=approved", true);
        redirect.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        return redirect;
    }

    // listRequest: the page of Pending this request was opened from (?page=2), sent on with Approve and Decline.
    @GetMapping("/ship/approvals/{id}")
    public String detail(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         @ModelAttribute ListRequest listRequest, Model model) {
        model.addAttribute("back", back(listRequest));
        model.addAttribute("detail", approvalService.detail(currentUser.getUsername(), id));
        model.addAttribute("declineForm", new DeclineForm());
        return "approval/detail";
    }

    @PostMapping("/ship/approvals/{id}/approve")
    public String approve(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                          @ModelAttribute ListRequest listRequest, RedirectAttributes redirectAttributes) {
        return afterDecision(approvalService.approve(currentUser.getUsername(), id), id, "approval.approved",
                listRequest, redirectAttributes);
    }

    // An empty reason shows the request again with the form open and its error; nothing is saved.
    @PostMapping("/ship/approvals/{id}/decline")
    public String decline(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                          @Validated @ModelAttribute("declineForm") DeclineForm declineForm, BindingResult bindingResult,
                          @ModelAttribute ListRequest listRequest, Model model,
                          RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("back", back(listRequest));
            model.addAttribute("detail", approvalService.detail(currentUser.getUsername(), id));
            return "approval/detail";
        }
        return afterDecision(approvalService.decline(currentUser.getUsername(), id, declineForm.getReason()), id,
                "approval.declined", listRequest, redirectAttributes);
    }

    // Approved or declined: back to the page of Pending the request was opened from (the one before it when that
    // page is now empty) with a success message. Already approved, declined or cancelled (a second click, another tab):
    // nothing changed, back to the request with a message saying so.
    private static String afterDecision(Decision decision, Long id, String successKey, ListRequest listRequest,
                                        RedirectAttributes redirectAttributes) {
        return switch (decision) {
            case DECIDED -> {
                redirectAttributes.addFlashAttribute("successMessage", successKey);
                yield "redirect:" + list(ApprovalTab.PENDING, listRequest).getUrl();
            }
            case NOT_PENDING -> {
                redirectAttributes.addFlashAttribute("errorMessage", "approval.notPending");
                yield "redirect:/ship/approvals/" + id;
            }
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    // The list of one tab: of the request, only the page and the page size are kept.
    private static ListQuery list(ApprovalTab tab, ListRequest request) {
        ListRequest pageOnly = new ListRequest(null, null, null, request.page(), request.size());
        return ListQuery.of(LIST, pageOnly, SORTS)
                .filter("tab", tab == ApprovalTab.PENDING ? null : tab.key(), null, null);
    }

    // The query string of that page of Pending ("?size=20&page=2"; nothing on the first page).
    private static String back(ListRequest request) {
        return list(ApprovalTab.PENDING, request).getUrl().substring(LIST.length());
    }
}
