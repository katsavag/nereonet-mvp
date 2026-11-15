package com.nereonix.nereonet_mvp.shiporder;

import java.util.List;

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

import com.nereonix.nereonet_mvp.core.order.OrderReceiptService;
import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Use;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.Change;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.Send;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;

// The Master's ship order (P35): the OPEN order of their own ship, its lines edited one at a time (each change is
// saved at once, nobody is notified), "Send to office", and the past orders ("All orders" since P59); P51: "Confirm
// receipt" on a past order whose delivery is scheduled (OrderReceiptController), and its receipt once received. P55:
// the Chief cook reads the same three pages (GET /ship/order, /ship/orders, /ship/orders/{id}; SecurityConfig), with
// nothing to act on: the
// model's canManage (the ship's enabled Master) decides what the templates offer, and the receipt's files are the
// Master's only. Every POST stays MASTER only (SecurityConfig), and the services refuse anyone else as well.
// P59: /ship/order also lists the orders in progress (SENT, CONFIRMED) above the OPEN one, the Master's with Confirm
// receipt. English only, as every crew screen.
@Controller
public class ShipOrderController {

    /** The model flag: true for the Master (edit, send, confirm receipt), false for the Chief cook (read only). */
    static final String CAN_MANAGE = "canManage";

    /** P61: true for the Chief cook, who counts a scheduled delivery (DeliveryCountController). */
    static final String CAN_COUNT = "canCount";

    private final ShipOrderPageService pageService;
    private final OrderReceiptService receiptService;
    private final QuantityRules quantityRules;

    public ShipOrderController(ShipOrderPageService pageService, OrderReceiptService receiptService,
                               QuantityRules quantityRules) {
        this.pageService = pageService;
        this.receiptService = receiptService;
        this.quantityRules = quantityRules;
    }

    // P59: above the OPEN order, the orders in progress (sent, delivery scheduled), most urgent first.
    @GetMapping("/ship/order")
    public String order(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        model.addAttribute("sendForm", new SendOrderForm());
        return showOrder(currentUser, model);
    }

    // P60: the rule of the item's unit (core/form/QuantityRules): blank, not a number, zero or less, too many decimals
    // or above the maximum (unless unchanged): back to the line with the error under it and what was typed, and the
    // error with the item's name on top; nothing saved.
    @PostMapping("/ship/order/lines/{id}/quantity")
    public String changeQuantity(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                 @RequestParam(required = false) String quantity, RedirectAttributes redirectAttributes) {
        ShipOrderLine line = pageService.line(currentUser.getUsername(), id);
        QuantityRules.Check check = quantityRules.forItem(line.getItem(), Use.REQUEST)
                .check(quantity, line.getQuantity());
        if (!check.ok()) {
            redirectAttributes.addFlashAttribute("errorMessage", "quantity.error.line");
            redirectAttributes.addFlashAttribute("errorArgs", new Object[] {line.getItem().getName(), check.message()});
            redirectAttributes.addFlashAttribute("quantityError", QuantityRules.LineError.of(id, quantity, check));
            return "redirect:/ship/order#line-" + id;
        }
        return afterChange(pageService.changeQuantity(currentUser.getUsername(), id, check.value()),
                "order.quantity.saved", redirectAttributes);
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
        if (bindingResult.hasErrors()) {
            return showOrder(currentUser, model);
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
        model.addAttribute(CAN_MANAGE, pageService.canManage(currentUser.getUsername()));
        return "shiporder/list";
    }

    // The OPEN order is edited on /ship/order.
    @GetMapping("/ship/orders/{id}")
    public String detail(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        ShipOrderPageService.OrderView view = pageService.detail(currentUser.getUsername(), id);
        if (view.order().getStatus() == ShipOrderStatus.OPEN) {
            return "redirect:/ship/order";
        }
        boolean canManage = pageService.canManage(currentUser.getUsername());
        model.addAttribute("view", view);
        model.addAttribute(CAN_MANAGE, canManage);
        // P61: the Chief cook counts the delivery of a CONFIRMED order.
        model.addAttribute(CAN_COUNT, pageService.canCount(currentUser.getUsername()));
        // P51: once received, the receipt's files; the Master's only (P55).
        model.addAttribute("receiptFiles", canManage && view.order().getStatus() == ShipOrderStatus.CLOSED
                ? receiptService.files(id) : List.of());
        return "shiporder/detail";
    }

    private String showOrder(CurrentUser currentUser, Model model) {
        model.addAttribute("view", pageService.open(currentUser.getUsername()));
        model.addAttribute("inProgress", pageService.inProgress(currentUser.getUsername()));
        model.addAttribute(CAN_MANAGE, pageService.canManage(currentUser.getUsername()));
        model.addAttribute(CAN_COUNT, pageService.canCount(currentUser.getUsername()));
        return "shiporder/order";
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

}
