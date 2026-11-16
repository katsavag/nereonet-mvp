package com.nereonix.nereonet_mvp.officeorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import com.nereonix.nereonet_mvp.core.confirm.ConfirmPages;
import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Use;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.order.InvoiceComparisonService;
import com.nereonix.nereonet_mvp.core.order.InvoiceDecision;
import com.nereonix.nereonet_mvp.core.order.OfficeOrderEditService;
import com.nereonix.nereonet_mvp.core.order.OfficeOrderEditService.Edit;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.Delivery;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService;
import com.nereonix.nereonet_mvp.core.export.XlsExport;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.rfq.RfqService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// The orders the ships have sent, as the office sees them: the list, one order, scheduling its delivery (port
// and date) and changing it until the Master confirms receipt, and the export (an Excel
// workbook). A received order shows the receipt (its files, ordered vs received per line) and, when
// lines were short, "Shortage handled" with a note. Received with a read invoice, the card "Order, delivery and
// invoice" (InvoiceComparisonService; its actions in OfficeInvoiceController) replaces "Shortage handled". The logic is ShipOrderService's. While an order is SENT
// the office can change its lines (quantity, remove, add an item; OfficeOrderEditService), each a plain POST form.
// /office/** is limited to OFFICE_USER in SecurityConfig. An OPEN order (still being put together on board) is a 404.
@Controller
public class OfficeOrderController {

    static final String LIST = "/office/orders";

    // The list has one order: newest sent first.
    private static final ListSort[] SORTS = {ListSort.desc("sentAt")};

    // The "Invoice" filter's value for "Lines differ" (?invoice=differ): received, the invoice not matched yet,
    // with lines that differ.
    static final String INVOICE_DIFFER = "differ";

    /** One category's lines, by item name. */
    public record LineGroup(ItemCategory category, List<ShipOrderLine> lines) {
    }

    /** One category's active items, by name: the choices of "Add an item" (only those not on the order). */
    public record ItemGroup(ItemCategory category, List<Item> items) {
    }

    private final ShipOrderService orderService;
    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final RfqService rfqService;
    private final OfficeOrderEditService editService;
    private final ItemRepository itemRepository;
    private final ConfirmPages confirmPages;
    private final OrderReceiptService receiptService;
    private final QuantityRules quantityRules;
    private final InvoiceComparisonService comparisonService;

    public OfficeOrderController(ShipOrderService orderService, ShipRepository shipRepository,
                                 UserRepository userRepository, RfqService rfqService,
                                 OfficeOrderEditService editService, ItemRepository itemRepository,
                                 ConfirmPages confirmPages, OrderReceiptService receiptService,
                                 QuantityRules quantityRules, InvoiceComparisonService comparisonService) {
        this.orderService = orderService;
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.rfqService = rfqService;
        this.editService = editService;
        this.itemRepository = itemRepository;
        this.confirmPages = confirmPages;
        this.receiptService = receiptService;
        this.quantityRules = quantityRules;
        this.comparisonService = comparisonService;
    }

    // shipId, status (sent, delivery_scheduled, closed; confirmed and delivery_pending, in saved links, mean
    // delivery_scheduled) and invoice=differ (the invoice's
    // lines differ, not matched yet) filter the list; anything else in status or invoice filters nothing.
    // listRequest: search and page.
    @GetMapping(LIST)
    public String list(@RequestParam(required = false) Long shipId,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String invoice,
                       @ModelAttribute ListRequest listRequest, Model model) {
        List<Ship> ships = shipRepository.findAllByOrderByNameAsc();
        ShipOrderStatus chosen = status(status);
        boolean invoiceDiffer = INVOICE_DIFFER.equals(invoice);
        ListQuery list = ListQuery.of(LIST, listRequest, SORTS).filterShip(shipId, ships);
        if (chosen != null) {
            list.filterKey("status", param(chosen), "officeorder.filter.status", chosen.labelKey());
        }
        if (invoiceDiffer) {
            list.filterKey("invoice", INVOICE_DIFFER, "officeorder.filter.invoice", "officeorder.filter.invoice.differ");
        }
        model.addAttribute("list", list);
        model.addAttribute("orders", orderService.findSent(shipId, chosen, invoiceDiffer, list));
        model.addAttribute("ships", ships);
        model.addAttribute("shipId", shipId);
        model.addAttribute("status", chosen == null ? null : param(chosen));
        model.addAttribute("statuses", ShipOrderService.SENT_TO_OFFICE);
        model.addAttribute("invoice", invoiceDiffer ? INVOICE_DIFFER : null);
        return "officeorder/list";
    }

    @GetMapping(LIST + "/{id}")
    public String detail(@PathVariable Long id, Model model) {
        ShipOrder order = sent(id);
        return showDetail(order, ConfirmOrderForm.of(order, rfqService.awarded(id).orElse(null)), model);
    }

    /**
     * "Schedule delivery": the order's port and delivery date while it is SENT, or "Change delivery" while it is
     * CONFIRMED. Errors: the order page again with them, nothing saved. Received meanwhile: back with a message,
     * nothing changed.
     */
    @PostMapping(LIST + "/{id}/confirm")
    public String confirm(@PathVariable Long id, @AuthenticationPrincipal CurrentUser currentUser,
                          @Validated @ModelAttribute("confirmForm") ConfirmOrderForm confirmForm,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return showDetail(sent(id), confirmForm, model);
        }
        ShipOrderService.Schedule result = orderService.scheduleDelivery(user(currentUser), id,
                new Delivery(confirmForm.getConfirmedPort(), confirmForm.getDeliveryDate()));
        switch (result) {
            case SCHEDULED -> redirectAttributes.addFlashAttribute("successMessage", "officeorder.scheduled");
            case CHANGED -> redirectAttributes.addFlashAttribute("successMessage", "officeorder.deliveryChanged");
            case RECEIVED -> redirectAttributes.addFlashAttribute("errorMessage", "officeorder.received");
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:" + LIST + "/" + id;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The receipt on board, as the office sees it once the Master confirmed it.
    // ---------------------------------------------------------------------------------------------------------------

    /** A file of the receipt: opened in the browser, or downloaded. 404 until the receipt is confirmed. */
    @GetMapping(LIST + "/{id}/receipt/{fileId}")
    public ResponseEntity<Resource> receiptFile(@PathVariable Long id, @PathVariable Long fileId,
                                                @RequestParam(defaultValue = "false") boolean download) {
        return receiptService.file(sent(id), fileId, false, download);
    }

    /** "Shortage handled", with a note (required). No note or a too long one: back with an error, nothing saved. */
    @PostMapping(LIST + "/{id}/shortage-handled")
    public String shortageHandled(@PathVariable Long id, @RequestParam(required = false) String note,
                                  @AuthenticationPrincipal CurrentUser currentUser,
                                  RedirectAttributes redirectAttributes) {
        String text = note == null ? "" : note.strip();
        if (text.isEmpty()) {
            return back(id, "errorMessage", "officeorder.shortage.noteRequired", redirectAttributes);
        }
        if (text.length() > ShipOrder.NOTE_MAX_LENGTH) {
            return back(id, "errorMessage", "officeorder.shortage.noteTooLong", redirectAttributes);
        }
        return switch (receiptService.markShortageHandled(user(currentUser), id, text)) {
            case DONE -> back(id, "successMessage", "officeorder.shortage.handled", redirectAttributes);
            case NOTHING_OPEN -> back(id, "errorMessage", "officeorder.shortage.nothingOpen", redirectAttributes);
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The office changes the lines of a SENT order. Each saves at once and comes back to the order page with a
    // message; a quantity or note that is not valid comes back with an error, nothing saved.
    // ---------------------------------------------------------------------------------------------------------------

    @PostMapping(LIST + "/{id}/lines/{lineId}/quantity")
    public String changeQuantity(@PathVariable Long id, @PathVariable Long lineId,
                                 @RequestParam(required = false) String quantity,
                                 @RequestParam(required = false) String note,
                                 @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        ShipOrder order = sent(id);
        ShipOrderLine line = order.getLines().stream().filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // The rule of the item's unit; the quantity already on the line may stay above the maximum.
        QuantityRules.Check check = quantityRules.forItem(line.getItem(), Use.OFFICE_ORDER)
                .check(quantity, line.getQuantity());
        if (!check.ok()) {
            return quantityError(id, lineId, null, line.getItem().getName(), quantity, check, redirectAttributes);
        }
        String error = noteError(note);
        if (error != null) {
            return back(id, "errorMessage", error, redirectAttributes);
        }
        return afterEdit(id, editService.changeQuantity(user(currentUser), id, lineId, check.value(), note),
                "officeorder.edit.changed", redirectAttributes);
    }

    // Without JavaScript the Remove link opens this plain confirmation page, with the optional note.
    @GetMapping(LIST + "/{id}/lines/{lineId}/remove")
    public String confirmRemove(@PathVariable Long id, @PathVariable Long lineId, Model model) {
        ShipOrder order = sent(id);
        ShipOrderLine line = order.getLines().stream().filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!OfficeOrderEditService.isEditable(order) || line.isRemoved()) {
            return "redirect:" + LIST + "/" + id;
        }
        return confirmPages.danger()
                .title("officeorder.edit.remove.confirm.title", line.getItem().getName())
                .body("officeorder.edit.remove.confirm.body")
                .confirmLabel("officeorder.edit.remove")
                .note("officeorder.edit.note", "note", ShipOrderLine.NOTE_MAX_LENGTH)
                .action(LIST + "/" + id + "/lines/" + lineId + "/remove", null)
                .cancelUrl(LIST + "/" + id)
                .crumb("nav.orders", LIST)
                .crumbText("#" + order.getId(), LIST + "/" + id)
                .show(model);
    }

    @PostMapping(LIST + "/{id}/lines/{lineId}/remove")
    public String removeLine(@PathVariable Long id, @PathVariable Long lineId,
                             @RequestParam(required = false) String note,
                             @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        String error = noteError(note);
        if (error != null) {
            return back(id, "errorMessage", error, redirectAttributes);
        }
        return afterEdit(id, editService.removeLine(user(currentUser), id, lineId, note), "officeorder.edit.removed",
                redirectAttributes);
    }

    // An item already on the order adds to its line (a removed line comes back with this quantity).
    @PostMapping(LIST + "/{id}/lines/add")
    public String addItem(@PathVariable Long id, @RequestParam(required = false) Long itemId,
                          @RequestParam(required = false) String quantity,
                          @RequestParam(required = false) String note,
                          @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        if (itemId == null) {
            return back(id, "errorMessage", "officeorder.edit.add.chooseItem", redirectAttributes);
        }
        ShipOrder order = sent(id);
        Item item = itemRepository.findById(itemId)
                .filter(i -> i.getShip().getId().equals(order.getShip().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // The rule of the item's unit, for the quantity added.
        QuantityRules.Check check = quantityRules.forItem(item, Use.OFFICE_ORDER).check(quantity);
        if (!check.ok()) {
            return quantityError(id, null, item.getId(), item.getName(), quantity, check, redirectAttributes);
        }
        String error = noteError(note);
        if (error != null) {
            return back(id, "errorMessage", error, redirectAttributes);
        }
        Edit edit;
        try {
            edit = editService.addItem(user(currentUser), id, itemId, check.value(), note);
        } catch (IllegalArgumentException e) {
            // The line's total would be over 9 whole digits.
            return back(id, "errorMessage", "officeorder.edit.quantityError", redirectAttributes);
        }
        return afterEdit(id, edit, "officeorder.edit.added", redirectAttributes);
    }

    // "Done editing": the Master and the Chief cook are told now.
    @PostMapping(LIST + "/{id}/edits/done")
    public String finishEditing(@PathVariable Long id, @AuthenticationPrincipal CurrentUser currentUser,
                                RedirectAttributes redirectAttributes) {
        sent(id);
        boolean told = editService.finishEditing(user(currentUser), id);
        return back(id, "successMessage", told ? "officeorder.edit.done" : "officeorder.edit.doneNothing",
                redirectAttributes);
    }

    private static String afterEdit(Long id, Edit edit, String successKey, RedirectAttributes redirectAttributes) {
        return switch (edit) {
            case DONE -> back(id, "successMessage", successKey, redirectAttributes);
            case UNCHANGED -> back(id, "successMessage", "officeorder.edit.unchanged", redirectAttributes);
            case NOT_EDITABLE -> back(id, "errorMessage", "officeorder.edit.notEditable", redirectAttributes);
            case REMOVED -> back(id, "errorMessage", "officeorder.edit.lineRemoved", redirectAttributes);
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    private static String back(Long id, String kind, String key, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute(kind, key);
        return "redirect:" + LIST + "/" + id;
    }

    private static String noteError(String note) {
        return note != null && note.strip().length() > ShipOrderLine.NOTE_MAX_LENGTH ? "officeorder.edit.noteTooLong"
                : null;
    }

    // A refused quantity: the error with the item's name on top, and under the field with what was typed: a
    // line's ("quantityError", lineId) or Add an item's ("addQuantityError", itemId: the item stays chosen).
    private static String quantityError(Long id, Long lineId, Long itemId, String itemName, String quantity,
                                        QuantityRules.Check check, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorArgs", new Object[] {itemName, check.message()});
        redirectAttributes.addFlashAttribute(lineId != null ? "quantityError" : "addQuantityError",
                QuantityRules.LineError.of(lineId != null ? lineId : itemId, quantity, check));
        redirectAttributes.addFlashAttribute("errorMessage", "quantity.error.line");
        return "redirect:" + LIST + "/" + id + (lineId != null ? "#line-" + lineId : "#addItemTitle");
    }

    /**
     * The workbook of the checked orders (the list), or of one order (its page): a .xls download, one row per order
     * line. It marks them exported now. Too many lines for one .xls sheet: back to the
     * list with "Too many rows for one file", nothing marked. Nothing checked, or none of them an order the ships sent:
     * back to the list with a message.
     */
    @PostMapping(LIST + "/export")
    public Object export(@RequestParam(required = false) List<Long> ids,
                         @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        List<Long> chosen = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).toList();
        if (chosen.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "officeorder.export.noneSelected");
            return "redirect:" + LIST;
        }
        XlsExport.File file;
        try {
            file = orderService.export(user(currentUser), chosen).orElse(null);
        } catch (XlsExport.TooManyRowsException e) {
            redirectAttributes.addFlashAttribute("errorMessage", XlsExport.TooManyRowsException.MESSAGE);
            return "redirect:" + LIST;
        }
        if (file == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "officeorder.export.nothing");
            return "redirect:" + LIST;
        }
        return file.response();
    }

    private String showDetail(ShipOrder order, ConfirmOrderForm confirmForm, Model model) {
        Map<ItemCategory, List<ShipOrderLine>> byCategory = new EnumMap<>(ItemCategory.class);
        for (ShipOrderLine line : ShipOrderService.byCategoryAndName(order.getLines())) {
            byCategory.computeIfAbsent(line.getItem().getCategory(), category -> new ArrayList<>()).add(line);
        }
        model.addAttribute("order", order);
        model.addAttribute("groups", byCategory.entrySet().stream()
                .map(entry -> new LineGroup(entry.getKey(), List.copyOf(entry.getValue()))).toList());
        model.addAttribute("canConfirm", order.getStatus() != ShipOrderStatus.CLOSED);
        // The receipt, once the Master confirmed it (the files the Master is still uploading are not shown).
        boolean received = order.getStatus() == ShipOrderStatus.CLOSED;
        model.addAttribute("received", received);
        model.addAttribute("receiptFiles", received ? receiptService.files(order.getId()) : List.of());
        model.addAttribute("shortageNoteMax", ShipOrder.NOTE_MAX_LENGTH);
        // Order, delivery and invoice side by side, for an order received with a read invoice (null otherwise).
        model.addAttribute("comparison", received ? comparisonService.comparison(order).orElse(null) : null);
        model.addAttribute("acceptNoteMax", InvoiceDecision.NOTE_MAX_LENGTH);
        // The lines can be changed while SENT; "Done editing" while the ship has not been told about a change.
        boolean editable = OfficeOrderEditService.isEditable(order);
        model.addAttribute("editable", editable);
        model.addAttribute("editPending", editable && order.getOfficeEditedAt() != null);
        model.addAttribute("noteMax", ShipOrderLine.NOTE_MAX_LENGTH);
        if (editable) {
            // "Add an item" offers the vessel's active items NOT on the order (a removed line's item is not on
            // it: adding it brings the line back), and lists the lines on it for reference. The server still accepts
            // an item already on the order (a stale page) and adds to its line.
            Set<Long> onOrder = order.getLines().stream().filter(line -> !line.isRemoved())
                    .map(line -> line.getItem().getId()).collect(Collectors.toSet());
            Map<ItemCategory, List<Item>> items = new EnumMap<>(ItemCategory.class);
            boolean hasItems = false;
            for (Item item : itemRepository.findAllByShipIdOrderByNameAsc(order.getShip().getId())) {
                if (item.isActive()) {
                    hasItems = true;
                    if (!onOrder.contains(item.getId())) {
                        items.computeIfAbsent(item.getCategory(), category -> new ArrayList<>()).add(item);
                    }
                }
            }
            model.addAttribute("hasItems", hasItems);
            model.addAttribute("itemGroups", items.entrySet().stream()
                    .map(entry -> new ItemGroup(entry.getKey(), List.copyOf(entry.getValue()))).toList());
            Map<ItemCategory, List<ShipOrderLine>> lines = new EnumMap<>(ItemCategory.class);
            for (ShipOrderLine line : ShipOrderService.byCategoryAndName(order.getLines())) {
                if (!line.isRemoved()) {
                    lines.computeIfAbsent(line.getItem().getCategory(), category -> new ArrayList<>()).add(line);
                }
            }
            model.addAttribute("onOrderGroups", lines.entrySet().stream()
                    .map(entry -> new LineGroup(entry.getKey(), List.copyOf(entry.getValue()))).toList());
        }
        model.addAttribute("confirmForm", confirmForm);
        // The order's requests for quotes, the open one, whether one can be started, and the latest award.
        model.addAttribute("rfqs", rfqService.forOrder(order.getId()));
        model.addAttribute("canRequestQuotes", rfqService.canRequest(order));
        model.addAttribute("award", rfqService.awarded(order.getId()).orElse(null));
        return "officeorder/detail";
    }

    private ShipOrder sent(Long id) {
        return orderService.findSent(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private User user(CurrentUser currentUser) {
        return userRepository.findById(currentUser.getId()).orElseThrow();
    }

    // "sent", "delivery_scheduled", "closed" (Pending, Delivery scheduled, Received); the older "confirmed" and
    // "delivery_pending" still mean Delivery scheduled, so saved links keep working. null for anything else (and OPEN,
    // which the office never sees).
    private static ShipOrderStatus status(String value) {
        if ("confirmed".equals(value) || "delivery_pending".equals(value)) {
            return ShipOrderStatus.CONFIRMED;
        }
        return Arrays.stream(ShipOrderStatus.values())
                .filter(ShipOrderService.SENT_TO_OFFICE::contains)
                .filter(status -> param(status).equals(value))
                .findFirst().orElse(null);
    }

    static String param(ShipOrderStatus status) {
        return status.filterParam();
    }
}
