package com.nereonix.nereonet_mvp.shiporder;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

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

import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.order.CountSheet;
import com.nereonix.nereonet_mvp.core.order.DeliveryCountService;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceLine;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatchService;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;

/**
 * "Count what arrived" (P61, P63): the vessel's Chief cook counts a CONFIRMED order (delivery scheduled) row by row
 * against the invoice read from its photos (P62), then sends the count to the Master, who approves it
 * (OrderReceiptController). Nothing here touches the store. SecurityConfig gives /ship/orders/{id}/count/** to
 * CHIEF_COOK only; DeliveryCountService refuses anyone but the ship's enabled Chief cook as well; another ship's order
 * is a 404. English only, as every crew page. Everything works without JavaScript (js/count.js only names the button
 * after the number typed, says the difference and opens the note in place).
 * <pre>
 *   GET  /ship/orders/{id}/count                   To do (?tab=done: Done; ?change=o12: that row's input in Done;
 *                                                  ?note=o12: that row's note open); once sent, what was sent
 *   POST /ship/orders/{id}/count/row               one row confirmed: key, quantity (empty: the reference), note
 *   GET  /ship/orders/{id}/count/choose/{lineId}   Choose the product of an invoice line (?q= narrows the list)
 *   POST /ship/orders/{id}/count/choose/{lineId}   choice: o12 (an order line), i34 (a vessel item) or none
 *   GET  /ship/orders/{id}/count/send              Finish: the rows still to do (?anyway: on regardless), or the
 *                                                  summary with Send to Master
 *   POST /ship/orders/{id}/count/send              Send to Master, with an optional note
 * </pre>
 * The count is kept on the order as he goes (the server's, not the browser's): each row is saved when confirmed.
 */
@Controller
public class DeliveryCountController {

    static final String BASE = "/ship/orders/{id}/count";

    private final ShipOrderPageService pageService;
    private final DeliveryCountService countService;
    private final InvoiceMatchService matchService;
    private final OrderReceiptService receiptService;
    private final QuantityRules quantityRules;

    public DeliveryCountController(ShipOrderPageService pageService, DeliveryCountService countService,
                                   InvoiceMatchService matchService, OrderReceiptService receiptService,
                                   QuantityRules quantityRules) {
        this.pageService = pageService;
        this.countService = countService;
        this.matchService = matchService;
        this.receiptService = receiptService;
        this.quantityRules = quantityRules;
    }

    /** A refused quantity: the row, what was typed and the error in words. */
    public record RowError(String key, String value, String message) {
    }

    @GetMapping(BASE)
    public String count(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                        @RequestParam(required = false) String tab, @RequestParam(required = false) String change,
                        @RequestParam(required = false) String note, Model model,
                        RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = prepare(email, id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        CountSheet sheet = matchService.sheet(order);
        if (!order.getDeliveryCountStatus().editable()) {
            return summary(order, sheet, true, model);
        }
        return show(order, sheet, "done".equals(tab), change, note, null, model);
    }

    /**
     * One row confirmed: the number typed (in the row's unit) or, with nothing typed, the row's reference ("12
     * arrived"); the note optional. Back to the tab, at the next row still to do; a refused number comes back with
     * its error under the row.
     */
    @PostMapping(BASE + "/row")
    public String row(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                      @RequestParam(required = false) String key, @RequestParam(required = false) String quantity,
                      @RequestParam(required = false) String note, @RequestParam(required = false) String tab,
                      Model model, RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = prepare(email, id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        if (!order.getDeliveryCountStatus().editable()) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.alreadySent");
            return "redirect:/ship/orders/" + id + "/count";
        }
        CountSheet sheet = matchService.sheet(order);
        CountSheet.Row row = sheet.row(key == null ? "" : key);
        boolean done = "done".equals(tab);
        if (row == null) {
            // A row gone meanwhile (the invoice read again): the count as it is now.
            return "redirect:/ship/orders/" + id + "/count";
        }
        QuantityRules.Check check = CountQuantities.read(quantityRules, row, quantity);
        String text = note == null ? null : note.strip();
        if (check.ok() && text != null && text.length() > ShipOrderLine.NOTE_MAX_LENGTH) {
            check = new QuantityRules.Check(null, "order.receipt.error.noteTooLong", null);
        }
        if (!check.ok()) {
            // A row already counted is on Done (sent from another page or tab): shown there, its input open.
            done = done || row.hasCount();
            return show(order, sheet, done, done ? key : null, text != null && !text.isEmpty() ? key : null,
                    new RowError(key, quantity, pageService.message(check.code(), check.args())), model);
        }
        // The next row still to do, to come back to.
        List<CountSheet.Row> toDo = sheet.getToDo();
        int at = toDo.indexOf(row);
        String next = at >= 0 && at + 1 < toDo.size() ? toDo.get(at + 1).getAnchor() : null;
        DeliveryCountService.Result result = countService.saveRow(pageService.cook(email), id, key, check.value(), text);
        if (result != DeliveryCountService.Result.DONE) {
            return refused(order, result, redirectAttributes);
        }
        redirectAttributes.addFlashAttribute("successMessage", "order.count.row.saved");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {row.getName(),
                plain(row.toRow(check.value())), row.unit(row.toRow(check.value()))});
        return "redirect:/ship/orders/" + id + "/count" + (done ? "?tab=done#" + row.getAnchor()
                : next != null ? "#" + next : "#countFinish");
    }

    @GetMapping({BASE + "/row"})
    public String toCount(@PathVariable Long id) {
        return "redirect:/ship/orders/" + id + "/count";
    }

    /** Choose the product: the order's lines nothing is matched to yet, then the vessel's items, or Not ordered. */
    @GetMapping(BASE + "/choose/{lineId}")
    public String choices(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                          @PathVariable Long lineId, @RequestParam(required = false) String q, Model model,
                          RedirectAttributes redirectAttributes) {
        ShipOrder order = prepare(currentUser.getUsername(), id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        if (!order.getDeliveryCountStatus().editable()) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.alreadySent");
            return "redirect:/ship/orders/" + id + "/count";
        }
        DeliveryInvoiceLine line = invoiceLine(order, lineId);
        model.addAttribute("order", order);
        model.addAttribute("line", line);
        model.addAttribute("q", q == null ? "" : q.strip());
        model.addAttribute("choices", matchService.choices(order, q));
        return "shiporder/count-choose";
    }

    @PostMapping(BASE + "/choose/{lineId}")
    public String choose(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         @PathVariable Long lineId, @RequestParam(required = false) String choice,
                         RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = prepare(email, id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        invoiceLine(order, lineId);
        Long orderLineId = null;
        Long itemId = null;
        String value = choice == null ? "" : choice.strip();
        try {
            if (value.startsWith("o")) {
                orderLineId = Long.valueOf(value.substring(1));
            } else if (value.startsWith("i")) {
                itemId = Long.valueOf(value.substring(1));
            } else if (!value.equals("none")) {
                redirectAttributes.addFlashAttribute("errorMessage", "order.count.choose.required");
                return "redirect:/ship/orders/" + id + "/count/choose/" + lineId;
            }
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        DeliveryCountService.Result result;
        try {
            result = countService.choose(pageService.cook(email), id, lineId, orderLineId, itemId);
        } catch (IllegalArgumentException e) {
            // A product gone meanwhile (matched to another line, the office's change): choose again.
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.choose.gone");
            return "redirect:/ship/orders/" + id + "/count/choose/" + lineId;
        }
        if (result != DeliveryCountService.Result.DONE) {
            return refused(order, result, redirectAttributes);
        }
        redirectAttributes.addFlashAttribute("successMessage", value.equals("none") ? "order.count.choose.notOrdered"
                : "order.count.choose.saved");
        if (orderLineId != null || itemId != null) {
            // The row it now is, to count.
            CountSheet.Row row = matchService.sheet(order).getRows().stream()
                    .filter(r -> r.getInvoiceLines().stream().anyMatch(l -> l.getId().equals(lineId)))
                    .findFirst().orElse(null);
            if (row != null) {
                return "redirect:/ship/orders/" + id + "/count" + (row.hasCount() ? "?tab=done" : "") + "#"
                        + row.getAnchor();
            }
        }
        return "redirect:/ship/orders/" + id + "/count";
    }

    /**
     * Finish: while rows are still to do, what is left (Count them, Send anyway); otherwise, or with ?anyway, the
     * summary with Send to Master.
     */
    @GetMapping(BASE + "/send")
    public String finish(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         @RequestParam(required = false) String anyway, Model model,
                         RedirectAttributes redirectAttributes) {
        ShipOrder order = prepare(currentUser.getUsername(), id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        if (!order.getDeliveryCountStatus().editable()) {
            return "redirect:/ship/orders/" + id + "/count";
        }
        CountSheet sheet = matchService.sheet(order);
        if (sheet.getToDoTotal() > 0 && anyway == null) {
            model.addAttribute("order", order);
            model.addAttribute("sheet", sheet);
            return "shiporder/count-left";
        }
        return summary(order, sheet, false, model);
    }

    @PostMapping(BASE + "/send")
    public String send(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                       @RequestParam(required = false) String note, Model model,
                       RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = prepare(email, id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        String text = note == null ? null : note.strip();
        if (text != null && text.length() > ShipOrder.COUNT_NOTE_MAX_LENGTH) {
            model.addAttribute("noteError", true);
            model.addAttribute("noteValue", text);
            return summary(order, matchService.sheet(order), false, model);
        }
        DeliveryCountService.Result result = countService.send(pageService.cook(email), id, text);
        if (result != DeliveryCountService.Result.DONE) {
            return refused(order, result, redirectAttributes);
        }
        redirectAttributes.addFlashAttribute("successMessage", "order.count.sent");
        return "redirect:/ship/orders/" + id;
    }

    // ---------------------------------------------------------------------------------------------------------------

    // The order of the cook's ship, its new invoice lines matched first.
    private ShipOrder prepare(String email, Long id) {
        pageService.countView(email, id);
        countService.prepare(pageService.cook(email), id);
        return pageService.countView(email, id).order();
    }

    private DeliveryInvoiceLine invoiceLine(ShipOrder order, Long lineId) {
        CountSheet sheet = matchService.sheet(order);
        return Stream.concat(sheet.getPicks().stream(), Stream.concat(
                        sheet.getNotOrdered().stream(),
                        sheet.getRows().stream().flatMap(row -> row.getInvoiceLines().stream())))
                .filter(line -> line.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String show(ShipOrder order, CountSheet sheet, boolean done, String change, String note,
                        RowError rowError, Model model) {
        model.addAttribute("order", order);
        model.addAttribute("sheet", sheet);
        model.addAttribute("done", done);
        model.addAttribute("groups", CountSheet.groups(done ? sheet.getDone() : sheet.getToDo()));
        model.addAttribute("change", change);
        model.addAttribute("openNote", note);
        model.addAttribute("rowError", rowError);
        model.addAttribute("noteMax", ShipOrderLine.NOTE_MAX_LENGTH);
        model.addAttribute("invoicePages", receiptService.invoicePages(order.getId()).size());
        return "shiporder/count";
    }

    // The summary before sending (readOnly false) or once sent (true).
    private String summary(ShipOrder order, CountSheet sheet, boolean readOnly, Model model) {
        model.addAttribute("order", order);
        model.addAttribute("sheet", sheet);
        model.addAttribute("readOnly", readOnly);
        model.addAttribute("countNoteMax", ShipOrder.COUNT_NOTE_MAX_LENGTH);
        return "shiporder/count-send";
    }

    private static String refused(ShipOrder order, DeliveryCountService.Result result,
                                  RedirectAttributes redirectAttributes) {
        if (result == DeliveryCountService.Result.WRONG_STATE) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.alreadySent");
            return "redirect:/ship/orders/" + order.getId() + "/count";
        }
        return notCountable(order, redirectAttributes);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String notCountable(ShipOrder order, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", order.getStatus() == ShipOrderStatus.CLOSED
                ? "order.count.received" : "order.count.notConfirmed");
        return "redirect:/ship/orders/" + order.getId();
    }
}
