package com.nereonix.nereonet_mvp.shiporder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.nereonix.nereonet_mvp.core.item.Item;
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
 * "Count what arrived": the vessel's Chief cook counts a CONFIRMED order (delivery scheduled) on one list
 * against the invoice read from its photos, saves it once, then sends the count to the Master, who approves it
 * (OrderReceiptController). Nothing here touches the store. SecurityConfig gives /ship/orders/{id}/count/** to
 * CHIEF_COOK only; DeliveryCountService refuses anyone but the ship's enabled Chief cook as well; another ship's order
 * is a 404. English only, as every crew page. Everything works without JavaScript (js/count.js selects the number
 * on focus, says the difference as it is typed, follows Did not arrive and writes the Save bar's sentence).
 * <pre>
 *   GET  /ship/orders/{id}/count                   the list, the saved numbers in its inputs; once sent, what was sent
 *   POST /ship/orders/{id}/count                   Save count: every row's q-{key}, none-{key}, note-{key}
 *   POST /ship/orders/{id}/count/invoice           Read wrong? what the paper invoice says of one row: key, quantity
 *   POST /ship/orders/{id}/count/lines             Add a line from the paper invoice: item (its id), quantity
 *   POST /ship/orders/{id}/count/lines/{lineId}/remove    Not on the invoice: remove (an invoice line left out)
 *   POST /ship/orders/{id}/count/lines/{lineId}/restore   Put back
 *   GET  /ship/orders/{id}/count/choose/{lineId}   "It is an item we have": the item of an invoice line (?q= narrows)
 *   POST /ship/orders/{id}/count/choose/{lineId}   choice: o12 (an order line), i34 (a vessel item) or none
 *   GET  /ship/orders/{id}/count/send              the summary with Send to Master; back to the list while rows are
 *                                                  still to do
 *   POST /ship/orders/{id}/count/send              Send to Master, with an optional note; refused while rows are
 *                                                  still to do
 * </pre>
 * The count is kept on the order (the server's); what is typed and not saved yet is the browser's draft (js/save.js).
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

    /** A line added by hand refused: the item chosen, the quantity typed, and the error of each in words (or null). */
    public record AddError(Long item, String quantity, String itemMessage, String quantityMessage) {
    }

    /**
     * The header of one group's card as the server renders it: how many of its rows are not what the invoice says,
     * how many have no number and how many have a refused one. With a row of the last two the card cannot be closed.
     */
    public record GroupHead(long different, long empty, long refused) {

        public boolean locked() {
            return empty > 0 || refused > 0;
        }
    }

    /** What the page shows instead of the saved count: what was typed per row (key: text), the notes, the errors. */
    public record Typed(Map<String, String> quantities, Map<String, String> notes, Map<String, String> errors) {
    }

    @GetMapping(BASE)
    public String count(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model,
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
        return show(order, sheet, null, model);
    }

    /**
     * Save count: every row at once. Per row q-{key} (the number, in the row's unit), none-{key} (Did not arrive:
     * 0 whatever the number says) and note-{key}. A row with a number is counted, one left empty is not. Any value
     * refused: nothing is saved and the page comes back with everything typed and the message at its row. Rows still
     * without a number: the rest is saved and the page comes back at the first of them. Everything counted: on to
     * the page that sends the count to the Master.
     */
    @PostMapping(BASE)
    public String save(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                       @RequestParam Map<String, String> params, Model model, RedirectAttributes redirectAttributes) {
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
        Typed typed = new Typed(new HashMap<>(), new HashMap<>(), new HashMap<>());
        Map<String, DeliveryCountService.Counted> counts = new LinkedHashMap<>();
        for (CountSheet.Row row : sheet.getRows()) {
            String key = row.getKey();
            boolean none = params.containsKey("none-" + key);
            if (!none && !params.containsKey("q-" + key)) {
                continue;   // a row that joined the count after the page was opened
            }
            String quantity = none ? "0" : params.get("q-" + key).strip();
            String note = params.getOrDefault("note-" + key, "").strip();
            typed.quantities().put(key, quantity);
            typed.notes().put(key, note);
            if (quantity.isEmpty()) {
                counts.put(key, new DeliveryCountService.Counted(null, note));
                continue;
            }
            QuantityRules.Check check = CountQuantities.read(quantityRules, row, quantity);
            if (check.ok() && note.length() > ShipOrderLine.NOTE_MAX_LENGTH) {
                check = new QuantityRules.Check(null, "order.receipt.error.noteTooLong", null);
            }
            if (check.ok()) {
                counts.put(key, new DeliveryCountService.Counted(check.value(), note));
            } else {
                typed.errors().put(key, pageService.message(check.code(), check.args()));
            }
        }
        if (!typed.errors().isEmpty()) {
            return show(order, sheet, typed, model);
        }
        DeliveryCountService.Result result = countService.saveRows(pageService.cook(email), id, counts);
        if (result != DeliveryCountService.Result.DONE) {
            return refused(order, result, redirectAttributes);
        }
        List<CountSheet.Row> empty = sheet.getToDo();
        if (!empty.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.save.left");
            redirectAttributes.addFlashAttribute("errorArgs", new Object[] {empty.size()});
            redirectAttributes.addFlashAttribute("leftEmpty", empty.stream().map(CountSheet.Row::getKey).toList());
            return "redirect:/ship/orders/" + id + "/count#" + empty.getFirst().getAnchor();
        }
        redirectAttributes.addFlashAttribute("successMessage", "order.count.saved");
        return "redirect:/ship/orders/" + id + "/count/send";
    }

    /**
     * Read wrong? The cook says what the paper invoice says of one row (in the row's unit; a line not read: Enter
     * it). Back to the same row; a refused number comes back with its error in the open form.
     */
    @PostMapping(BASE + "/invoice")
    public String correctInvoice(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                                 @RequestParam(required = false) String key,
                                 @RequestParam(required = false) String quantity, Model model,
                                 RedirectAttributes redirectAttributes) {
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
        if (row == null || row.getInvoiceLine() == null) {
            // A row gone meanwhile (the invoice read again): the count as it is now.
            return "redirect:/ship/orders/" + id + "/count";
        }
        QuantityRules.Check check = quantity == null || quantity.isBlank()
                ? new QuantityRules.Check(null, "order.count.fix.required", null)
                : CountQuantities.rule(quantityRules, row).check(quantity, row.getInvoiced());
        if (check.ok() && !OrderReceiptService.isValidReceivedQuantity(row.toInvoice(check.value()))) {
            check = new QuantityRules.Check(null, "order.count.fix.required", null);
        }
        if (!check.ok()) {
            model.addAttribute("fixError", new RowError(key, quantity, pageService.message(check.code(), check.args())));
            return show(order, sheet, null, model);
        }
        DeliveryCountService.Result result = countService.correctInvoice(pageService.cook(email), id, key, check.value());
        if (result != DeliveryCountService.Result.DONE) {
            return refused(order, result, redirectAttributes);
        }
        redirectAttributes.addFlashAttribute("successMessage", "order.count.fix.saved");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {row.getName(), plain(check.value()),
                row.unit(check.value())});
        return "redirect:/ship/orders/" + id + "/count#" + row.getAnchor();
    }

    @GetMapping({BASE + "/invoice", BASE + "/lines", BASE + "/lines/{lineId}/remove", BASE + "/lines/{lineId}/restore"})
    public String toCount(@PathVariable Long id) {
        return "redirect:/ship/orders/" + id + "/count";
    }

    /**
     * Not on the invoice: remove. An invoice line the photo read wrong (or one added by hand) is left out of the
     * count; it stays listed under "Removed invoice lines" with Put back. Back to the row it was part of.
     */
    @PostMapping(BASE + "/lines/{lineId}/remove")
    public String removeLine(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                             @PathVariable Long lineId, RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = prepare(email, id);
        String refused = notEditable(order, redirectAttributes);
        if (refused != null) {
            return refused;
        }
        CountSheet sheet = matchService.sheet(order);
        DeliveryInvoiceLine line = invoiceLine(order, lineId);
        CountSheet.Row row = sheet.rowOf(lineId);
        // A new item's row is gone with its line; an ordered row stays.
        DeliveryCountService.Result result = countService.removeLine(pageService.cook(email), id, lineId, true);
        if (result != DeliveryCountService.Result.DONE) {
            return refused(order, result, redirectAttributes);
        }
        redirectAttributes.addFlashAttribute("successMessage", "order.count.line.removed");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {line.getDescription()});
        return "redirect:/ship/orders/" + id + "/count#"
                + (row != null && row.getOrderLine() != null ? row.getAnchor() : "countRemovedTitle");
    }

    /** Put back: a removed invoice line is on the count again, as it was. Back to its row. */
    @PostMapping(BASE + "/lines/{lineId}/restore")
    public String restoreLine(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                              @PathVariable Long lineId, RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = prepare(email, id);
        String refused = notEditable(order, redirectAttributes);
        if (refused != null) {
            return refused;
        }
        DeliveryInvoiceLine line = matchService.sheet(order).getRemoved().stream()
                .filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        DeliveryCountService.Result result = countService.removeLine(pageService.cook(email), id, lineId, false);
        if (result != DeliveryCountService.Result.DONE) {
            return refused(order, result, redirectAttributes);
        }
        redirectAttributes.addFlashAttribute("successMessage", "order.count.line.restored");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {line.getDescription()});
        CountSheet.Row row = matchService.sheet(order).rowOf(lineId);
        return "redirect:/ship/orders/" + id + "/count" + (row == null ? "" : "#" + row.getAnchor());
    }

    /**
     * Add a line from the paper invoice: an item of the vessel the photo missed, with the quantity the paper says
     * (the item's unit and rule). An item that has an invoice line already is refused at the select. Back to the
     * item's row; a refused line comes back with its errors in the block.
     */
    @PostMapping(BASE + "/lines")
    public String addLine(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                          @RequestParam(required = false) String item,
                          @RequestParam(required = false) String quantity, Model model,
                          RedirectAttributes redirectAttributes) {
        String email = currentUser.getUsername();
        ShipOrder order = prepare(email, id);
        String refused = notEditable(order, redirectAttributes);
        if (refused != null) {
            return refused;
        }
        CountSheet sheet = matchService.sheet(order);
        if (!sheet.isInvoiceRead()) {
            return "redirect:/ship/orders/" + id + "/count";
        }
        InvoiceMatchService.Choices choices = matchService.addable(order);
        Item chosen = Stream.concat(choices.orderLines().stream().map(ShipOrderLine::getItem), choices.items().stream())
                .filter(i -> i.getId().toString().equals(item == null ? "" : item.strip())).findFirst().orElse(null);
        String typed = quantity == null ? "" : quantity.strip();
        String itemError = chosen == null ? "order.count.add.itemRequired"
                : InvoiceMatchService.onInvoice(sheet, chosen.getId()) ? "order.count.add.onInvoice" : null;
        QuantityRules.Check check = chosen == null ? null
                : quantityRules.forItem(chosen, QuantityRules.Use.RECEIPT).check(typed);
        if (itemError != null || !check.ok()) {
            model.addAttribute("addError", new AddError(chosen == null ? null : chosen.getId(), typed,
                    itemError == null ? null : pageService.message(itemError),
                    check == null || check.ok() ? null : pageService.message(check.code(), check.args())));
            return show(order, sheet, null, model);
        }
        DeliveryCountService.Result result = countService.addLine(pageService.cook(email), id, chosen.getId(),
                check.value());
        if (result != DeliveryCountService.Result.DONE) {
            return refused(order, result, redirectAttributes);
        }
        redirectAttributes.addFlashAttribute("successMessage", "order.count.add.saved");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {chosen.getName(), plain(check.value()),
                chosen.getUnit()});
        CountSheet.Row row = matchService.sheet(order).getRows().stream()
                .filter(r -> chosen.getId().equals(r.getItem().getId())).findFirst().orElse(null);
        return "redirect:/ship/orders/" + id + "/count" + (row == null ? "" : "#" + row.getAnchor());
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
        model.addAttribute("choices", matchService.choices(order, line, q));
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
                return "redirect:/ship/orders/" + id + "/count#" + row.getAnchor();
            }
        }
        return "redirect:/ship/orders/" + id + "/count";
    }

    /**
     * The summary with Send to Master; while rows are still to do, back to the list, which says how many.
     */
    @GetMapping(BASE + "/send")
    public String finish(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                         Model model, RedirectAttributes redirectAttributes) {
        ShipOrder order = prepare(currentUser.getUsername(), id);
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        if (!order.getDeliveryCountStatus().editable()) {
            return "redirect:/ship/orders/" + id + "/count";
        }
        CountSheet sheet = matchService.sheet(order);
        if (sheet.getToDoTotal() > 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.error.unfinished");
            redirectAttributes.addFlashAttribute("errorArgs", new Object[] {sheet.getToDoTotal()});
            return "redirect:/ship/orders/" + id + "/count";
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
        if (result == DeliveryCountService.Result.NOT_FINISHED) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.error.unfinished");
            redirectAttributes.addFlashAttribute("errorArgs",
                    new Object[] {matchService.sheet(order).getToDoTotal()});
            return "redirect:/ship/orders/" + id + "/count";
        }
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
        return Stream.concat(sheet.getNotOrdered().stream(),
                        sheet.getRows().stream().flatMap(row -> row.getInvoiceLines().stream()))
                .filter(line -> line.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // typed: what the refused Save sent, shown instead of the saved count (null: the saved count). addable: what a
    // line added by hand may be (null without a read invoice: no block).
    private String show(ShipOrder order, CountSheet sheet, Typed typed, Model model) {
        model.addAttribute("order", order);
        model.addAttribute("sheet", sheet);
        List<CountSheet.Group> groups = CountSheet.groups(sheet.getRows());
        Object left = model.getAttribute("leftEmpty");
        // The text of each row's input: what was typed after a refused Save; nothing for a row the last Save left
        // without a number; else the saved count, and for a row never saved the reference.
        Map<String, String> values = new HashMap<>();
        for (CountSheet.Row row : sheet.getRows()) {
            String key = row.getKey();
            values.put(key, typed != null && typed.quantities().containsKey(key) ? typed.quantities().get(key)
                    : left instanceof List<?> keys && keys.contains(key) ? ""
                    : row.text(row.hasCount() ? row.getCounted() : row.getReference()));
        }
        model.addAttribute("groups", groups);
        model.addAttribute("values", values);
        model.addAttribute("heads", groups.stream().map(group -> {
            long different = group.rows().stream().filter(r -> "different".equals(r.mark(values.get(r.getKey())))).count();
            long empty = group.rows().stream().filter(r -> "empty".equals(r.mark(values.get(r.getKey())))).count();
            long refused = typed == null ? 0
                    : group.rows().stream().filter(r -> typed.errors().containsKey(r.getKey())).count();
            return new GroupHead(different, empty, refused);
        }).toList());
        // The first refused row: its field has the focus, so it is in view when the page comes back.
        model.addAttribute("firstError", typed == null ? null : groups.stream().flatMap(group -> group.rows().stream())
                .map(CountSheet.Row::getKey).filter(typed.errors()::containsKey).findFirst().orElse(null));
        model.addAttribute("typed", typed);
        model.addAttribute("noteMax", ShipOrderLine.NOTE_MAX_LENGTH);
        model.addAttribute("invoicePages", receiptService.invoicePages(order.getId()).size());
        model.addAttribute("addable", sheet.isInvoiceRead() ? matchService.addable(order) : null);
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

    // Where to go when the cook cannot change the count now (not scheduled, received, with the Master); else null.
    private static String notEditable(ShipOrder order, RedirectAttributes redirectAttributes) {
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return notCountable(order, redirectAttributes);
        }
        if (!order.getDeliveryCountStatus().editable()) {
            redirectAttributes.addFlashAttribute("errorMessage", "order.count.alreadySent");
            return "redirect:/ship/orders/" + order.getId() + "/count";
        }
        return null;
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
