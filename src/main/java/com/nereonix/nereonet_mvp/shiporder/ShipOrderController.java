package com.nereonix.nereonet_mvp.shiporder;

import java.math.BigDecimal;

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

import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.Change;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.Send;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.time.Dates;

// The Master's ship order (P35): the OPEN order of their own ship, its lines edited one at a time (each change is
// saved at once, nobody is notified), "Send to office", and the past orders. SecurityConfig limits /ship/order/** and
// /ship/orders/** to MASTER. English only, as every crew screen.
@Controller
public class ShipOrderController {

    private final ShipOrderPageService pageService;
    private final Dates dates;

    public ShipOrderController(ShipOrderPageService pageService, Dates dates) {
        this.pageService = pageService;
        this.dates = dates;
    }

    @GetMapping("/ship/order")
    public String order(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        model.addAttribute("view", pageService.open(currentUser.getUsername()));
        model.addAttribute("sendForm", new SendOrderForm());
        model.addAttribute("today", dates.today());
        return "shiporder/order";
    }

    // Blank, not a number, zero or less, or more than 3 decimals: back to the order with an error, nothing saved.
    @PostMapping("/ship/order/lines/{id}/quantity")
    public String changeQuantity(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                 @RequestParam(required = false) String quantity, RedirectAttributes redirectAttributes) {
        BigDecimal value = parse(quantity);
        if (!ShipOrderService.isValidQuantity(value)) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.quantity.error");
            return "redirect:/ship/order";
        }
        return afterChange(pageService.changeQuantity(currentUser.getUsername(), id, value), "order.quantity.saved",
                redirectAttributes);
    }

    @PostMapping("/ship/order/lines/{id}/remove")
    public String removeLine(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                             RedirectAttributes redirectAttributes) {
        return afterChange(pageService.removeLine(currentUser.getUsername(), id), "order.line.removed",
                redirectAttributes);
    }

    // Errors show the order again with the form's errors; nothing is saved. Sent: to the sent order, now read-only.
    @PostMapping("/ship/order/send")
    public String send(@AuthenticationPrincipal CurrentUser currentUser,
                       @Validated @ModelAttribute("sendForm") SendOrderForm sendForm, BindingResult bindingResult,
                       Model model, RedirectAttributes redirectAttributes) {
        if (sendForm.getNeededBy() != null && sendForm.getNeededBy().isBefore(dates.today())) {
            bindingResult.rejectValue("neededBy", "order.send.error.neededByPast");
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("view", pageService.open(currentUser.getUsername()));
            model.addAttribute("today", dates.today());
            return "shiporder/order";
        }
        Send send = pageService.send(currentUser.getUsername(), sendForm);
        if (!send.sent()) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.nothingToSend");
            return "redirect:/ship/order";
        }
        redirectAttributes.addFlashAttribute("successMessage", "order.sent");
        return "redirect:/ship/orders/" + send.order().getId();
    }

    @GetMapping("/ship/orders")
    public String past(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        model.addAttribute("past", pageService.past(currentUser.getUsername()));
        return "shiporder/list";
    }

    // The OPEN order is edited on /ship/order.
    @GetMapping("/ship/orders/{id}")
    public String detail(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        ShipOrderPageService.OrderView view = pageService.detail(currentUser.getUsername(), id);
        if (view.order().getStatus() == ShipOrderStatus.OPEN) {
            return "redirect:/ship/order";
        }
        model.addAttribute("view", view);
        return "shiporder/detail";
    }

    // Saved: back to the order with a message. Already sent (another tab): nothing changed, and the page says so.
    private static String afterChange(Change change, String successKey, RedirectAttributes redirectAttributes) {
        return switch (change) {
            case DONE -> {
                redirectAttributes.addFlashAttribute("successMessage", successKey);
                yield "redirect:/ship/order";
            }
            case NOT_OPEN -> {
                redirectAttributes.addFlashAttribute("errorMessage", "order.notOpen");
                yield "redirect:/ship/order";
            }
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    // "6", "2.5" or "2,5"; null for anything else.
    private static BigDecimal parse(String quantity) {
        if (quantity == null || quantity.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(quantity.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
