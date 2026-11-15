package com.nereonix.nereonet_mvp.officeorder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceLine;
import com.nereonix.nereonet_mvp.core.order.InvoiceComparison;
import com.nereonix.nereonet_mvp.core.order.InvoiceComparisonService;
import com.nereonix.nereonet_mvp.core.order.InvoiceComparisonService.Correction;
import com.nereonix.nereonet_mvp.core.order.InvoiceComparisonService.Outcome;
import com.nereonix.nereonet_mvp.core.order.InvoiceDecision;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptFile;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * The office compares order, delivery and invoice (InvoiceComparisonService). The comparison itself is a card of
 * the order page (OfficeOrderController); here are its actions, each a plain POST form that comes back to the card
 * (#compare) with a message, and two pages: View invoice (the photographed pages beside what was read, one page at a
 * time, a line read wrong corrected there) and Change the product of an invoice line. Office users only (/office/**);
 * an order without a read invoice, or not received, has none of them (404).
 */
@Controller
public class OfficeInvoiceController {

    private static final String BASE = OfficeOrderController.LIST + "/{id}/invoice";
    private static final Pattern NUMBER = Pattern.compile("\\d{1,9}([.,]\\d{1,6})?");

    private final ShipOrderService orderService;
    private final InvoiceComparisonService comparisonService;
    private final OrderReceiptService receiptService;
    private final UserRepository userRepository;

    public OfficeInvoiceController(ShipOrderService orderService, InvoiceComparisonService comparisonService,
                                   OrderReceiptService receiptService, UserRepository userRepository) {
        this.orderService = orderService;
        this.comparisonService = comparisonService;
        this.receiptService = receiptService;
        this.userRepository = userRepository;
    }

    /** View invoice: page (1, 2, ...) of the photographed pages, beside the lines read from it and the checks. */
    @GetMapping(BASE)
    public String invoice(@PathVariable Long id, @RequestParam(defaultValue = "1") int page, Model model) {
        ShipOrder order = order(id);
        InvoiceComparison comparison = comparison(order);
        List<OrderReceiptFile> pages = receiptService.invoicePages(order.getId());
        Map<Integer, List<DeliveryInvoiceLine>> byPage = comparison.linesByPage();
        int last = Math.max(pages.size(), byPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(1));
        int current = Math.min(Math.max(page, 1), last);
        model.addAttribute("order", order);
        model.addAttribute("comparison", comparison);
        model.addAttribute("pageCount", last);
        model.addAttribute("page", current);
        model.addAttribute("pageFile", pages.stream().filter(f -> f.getInvoicePage() == current).findFirst().orElse(null));
        model.addAttribute("lines", byPage.getOrDefault(current, List.of()));
        String prefix = "Page " + current + ":";
        List<String> checks = comparison.getReview().stream()
                .filter(item -> item.kind() == InvoiceComparison.ReviewKind.CHECK)
                .map(InvoiceComparison.ReviewItem::text)
                .filter(text -> text.startsWith(prefix) || !text.startsWith("Page ")).toList();
        model.addAttribute("checks", checks);
        model.addAttribute("editable", order.isInvoiceToMatch());
        model.addAttribute("descriptionMax", DeliveryInvoiceLine.DESCRIPTION_MAX_LENGTH);
        return "officeorder/invoice";
    }

    /** Correct a line read wrong (empty fields: unchanged). Back to its page with the line. */
    @PostMapping(BASE + "/lines/{lineId}")
    public String correctLine(@PathVariable Long id, @PathVariable Long lineId,
                              @RequestParam(required = false) String quantity,
                              @RequestParam(required = false) String unitPrice,
                              @RequestParam(required = false) String amount,
                              @RequestParam(required = false) String description,
                              @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        ShipOrder order = order(id);
        DeliveryInvoiceLine line = comparison(order).getInvoiceLines().stream().filter(l -> l.getId().equals(lineId))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String back = "redirect:" + OfficeOrderController.LIST + "/" + id + "/invoice?page=" + line.getPage()
                + "#line-" + lineId;
        Correction correction;
        try {
            correction = new Correction(number(quantity), number(unitPrice), number(amount),
                    description == null || description.isBlank() ? null : description);
            InvoiceComparisonService.check(correction);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "officeorder.invoice.correct.invalid");
            return back;
        }
        Outcome outcome = comparisonService.correctLine(user(currentUser), id, lineId, correction);
        redirectAttributes.addFlashAttribute(outcome == Outcome.NOT_OPEN ? "errorMessage" : "successMessage",
                outcome == Outcome.NOT_OPEN ? "officeorder.compare.notOpen" : "officeorder.invoice.correct.saved");
        return back;
    }

    /** Accept a difference, with a note (required, at most 500 characters). */
    @PostMapping(BASE + "/accept")
    public String accept(@PathVariable Long id, @RequestParam String key,
                         @RequestParam(required = false) String note,
                         @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        order(id);
        String text = note == null ? "" : note.strip();
        if (text.isEmpty()) {
            return back(id, "errorMessage", "officeorder.compare.accept.noteRequired", key, redirectAttributes);
        }
        if (text.length() > InvoiceDecision.NOTE_MAX_LENGTH) {
            return back(id, "errorMessage", "officeorder.compare.accept.noteTooLong", key, redirectAttributes);
        }
        return after(id, comparisonService.accept(user(currentUser), id, key, text), "officeorder.compare.accepted",
                null, redirectAttributes);
    }

    /** Correct: a point to review is right as it is. */
    @PostMapping(BASE + "/confirm")
    public String confirm(@PathVariable Long id, @RequestParam String key,
                          @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        order(id);
        return after(id, comparisonService.confirm(user(currentUser), id, key), "officeorder.compare.confirmed", null,
                redirectAttributes);
    }

    /** Change the product of an invoice line: the order's lines, the vessel's other items, Not ordered. */
    @GetMapping(BASE + "/lines/{lineId}/match")
    public String chooseMatch(@PathVariable Long id, @PathVariable Long lineId, Model model) {
        ShipOrder order = order(id);
        InvoiceComparison comparison = comparison(order);
        if (!order.isInvoiceToMatch()) {
            return "redirect:" + OfficeOrderController.LIST + "/" + id + "#compare";
        }
        DeliveryInvoiceLine line = comparison.getInvoiceLines().stream().filter(l -> l.getId().equals(lineId))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("order", order);
        model.addAttribute("line", line);
        model.addAttribute("choices", comparisonService.choices(order));
        return "officeorder/invoice-match";
    }

    @PostMapping(BASE + "/lines/{lineId}/match")
    public String changeMatch(@PathVariable Long id, @PathVariable Long lineId,
                              @RequestParam(required = false) String choice,
                              @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        order(id);
        if (choice == null || choice.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "officeorder.compare.match.choose");
            return "redirect:" + OfficeOrderController.LIST + "/" + id + "/invoice/lines/" + lineId + "/match";
        }
        Long orderLineId = null;
        Long itemId = null;
        try {
            if (choice.startsWith("o")) {
                orderLineId = Long.parseLong(choice.substring(1));
            } else if (choice.startsWith("i")) {
                itemId = Long.parseLong(choice.substring(1));
            } else if (!choice.equals("none")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return after(id, comparisonService.changeMatch(user(currentUser), id, lineId, orderLineId, itemId),
                    "officeorder.compare.match.changed", null, redirectAttributes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /** Change a pack conversion: one invoice unit is factor of the item's unit. */
    @PostMapping(BASE + "/lines/{lineId}/factor")
    public String changeFactor(@PathVariable Long id, @PathVariable Long lineId,
                               @RequestParam(required = false) String factor,
                               @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        order(id);
        BigDecimal value;
        try {
            value = number(factor);
        } catch (IllegalArgumentException e) {
            value = null;
        }
        if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("100000")) > 0) {
            return back(id, "errorMessage", "officeorder.compare.factor.invalid", "conv:" + lineId, redirectAttributes);
        }
        try {
            return after(id, comparisonService.changeFactor(user(currentUser), id, lineId, value),
                    "officeorder.compare.factor.changed", null, redirectAttributes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /** Mark as matched: nothing left to decide. */
    @PostMapping(BASE + "/matched")
    public String markMatched(@PathVariable Long id, @AuthenticationPrincipal CurrentUser currentUser,
                              RedirectAttributes redirectAttributes) {
        order(id);
        return switch (comparisonService.markMatched(user(currentUser), id)) {
            case OPEN_LEFT -> back(id, "errorMessage", "officeorder.compare.openLeft", null, redirectAttributes);
            case NOT_OPEN -> back(id, "errorMessage", "officeorder.compare.notOpen", null, redirectAttributes);
            default -> back(id, "successMessage", "officeorder.compare.matched", null, redirectAttributes);
        };
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static String after(Long id, Outcome outcome, String successKey, String anchor,
                                RedirectAttributes redirectAttributes) {
        return switch (outcome) {
            case DONE -> back(id, "successMessage", successKey, anchor, redirectAttributes);
            // The store was updated with the old match or conversion: the message says to ask the vessel to count.
            case DONE_STOCK -> back(id, "successMessage", successKey + "Stock", anchor, redirectAttributes);
            case NOT_OPEN, OPEN_LEFT -> back(id, "errorMessage", "officeorder.compare.notOpen", anchor,
                    redirectAttributes);
        };
    }

    private static String back(Long id, String kind, String key, String anchor, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute(kind, key);
        return "redirect:" + OfficeOrderController.LIST + "/" + id + "#compare";
    }

    // A number the office typed: digits, one decimal point or comma; empty: null. IllegalArgumentException otherwise.
    static BigDecimal number(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.strip().replace(" ", "");
        if (!NUMBER.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a number: " + text);
        }
        return new BigDecimal(value.replace(',', '.'));
    }

    private ShipOrder order(Long id) {
        return orderService.findSent(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private InvoiceComparison comparison(ShipOrder order) {
        return comparisonService.comparison(order).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private User user(CurrentUser currentUser) {
        return userRepository.findById(currentUser.getId()).orElseThrow();
    }
}
