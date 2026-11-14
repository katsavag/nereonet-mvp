package com.nereonix.nereonet_mvp.rfq;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.rfq.Rfq;
import com.nereonix.nereonet_mvp.core.rfq.RfqComparison;
import com.nereonix.nereonet_mvp.core.rfq.RfqLine;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuote;
import com.nereonix.nereonet_mvp.core.rfq.RfqService;
import com.nereonix.nereonet_mvp.core.rfq.RfqWorkbook;
import com.nereonix.nereonet_mvp.core.rfq.Units;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * Requests for quotes from a vessel order (P44), under the order's URL so Orders stays the active sidebar entry:
 *   GET  /office/orders/{o}/rfq/new          Request quotes: port, delivery date, reply by, suppliers
 *   POST /office/orders/{o}/rfq/review       step 2 of 2 (P10): what will be sent and to whom; nothing saved
 *   POST /office/orders/{o}/rfq/edit         Back to edit, with the values
 *   POST /office/orders/{o}/rfq              Send
 *   GET  /office/orders/{o}/rfqs/{r}         the RFQ: status per supplier and the comparison
 *   GET/POST .../rfqs/{r}/quotes/{q}         Enter quote for one supplier
 *   POST .../rfqs/{r}/quotes/{q}/decline     Declined (optional reason), one click
 *   POST .../rfqs/{r}/quotes/{q}/resend      Send again (a failed or unanswered supplier)
 *   GET  .../rfqs/{r}/quotes/{q}/file        the supplier's PDF (?download=true)
 *   GET/POST .../rfqs/{r}/quotes/{q}/award   Award (asks first, P10)
 *   GET/POST .../rfqs/{r}/cancel             Cancel the RFQ (asks first)
 * /office/** is OFFICE_USER only. An order the ships have not sent, an RFQ of another order or a quote of another
 * RFQ is a 404.
 */
@Controller
public class RfqController {

    /** The order's lines by category, read-only, on the form and the review page. */
    public record LineGroup(ItemCategory category, List<ShipOrderLine> lines) {
    }

    /** One RFQ line of the quote form, with its index in the form. */
    public record QuoteRow(int index, RfqLine line) {
    }

    /** One category's rows of the quote form. */
    public record QuoteGroup(ItemCategory category, List<QuoteRow> rows) {
    }

    private final RfqService rfqService;
    private final ShipOrderService orderService;
    private final UserRepository userRepository;
    private final ConfirmPages confirmPages;

    public RfqController(RfqService rfqService, ShipOrderService orderService, UserRepository userRepository,
                         ConfirmPages confirmPages) {
        this.rfqService = rfqService;
        this.orderService = orderService;
        this.userRepository = userRepository;
        this.confirmPages = confirmPages;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Request quotes
    // ---------------------------------------------------------------------------------------------------------------

    @GetMapping("/office/orders/{orderId}/rfq/new")
    public String newForm(@PathVariable Long orderId, Model model, RedirectAttributes redirectAttributes) {
        ShipOrder order = order(orderId);
        String blocked = blocked(order, redirectAttributes);
        if (blocked != null) {
            return blocked;
        }
        return showForm(order, RfqForm.of(order, RfqService.today()), model);
    }

    // A GET on the POST-only URLs (after signing in again, P12) goes back to the form.
    @GetMapping({"/office/orders/{orderId}/rfq/review", "/office/orders/{orderId}/rfq/edit"})
    public String reviewGet(@PathVariable Long orderId) {
        return "redirect:/office/orders/" + orderId + "/rfq/new";
    }

    @PostMapping("/office/orders/{orderId}/rfq/review")
    public String review(@PathVariable Long orderId, @Validated @ModelAttribute("form") RfqForm form,
                         BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        ShipOrder order = order(orderId);
        String blocked = blocked(order, redirectAttributes);
        if (blocked != null) {
            return blocked;
        }
        List<Supplier> chosen = chosen(form);
        if (!result.hasFieldErrors("supplierIds") && chosen.isEmpty()) {
            result.rejectValue("supplierIds", "rfq.error.suppliersRequired");
        }
        if (result.hasErrors()) {
            return showForm(order, form, model);
        }
        model.addAttribute("order", order);
        model.addAttribute("form", form);
        model.addAttribute("chosen", chosen);
        model.addAttribute("groups", groups(order));
        return "rfq/review";
    }

    @PostMapping("/office/orders/{orderId}/rfq/edit")
    public String edit(@PathVariable Long orderId, @ModelAttribute("form") RfqForm form, Model model,
                       RedirectAttributes redirectAttributes) {
        ShipOrder order = order(orderId);
        String blocked = blocked(order, redirectAttributes);
        if (blocked != null) {
            return blocked;
        }
        return showForm(order, form, model);
    }

    @PostMapping("/office/orders/{orderId}/rfq")
    public String send(@PathVariable Long orderId, @Validated @ModelAttribute("form") RfqForm form,
                       BindingResult result, @AuthenticationPrincipal CurrentUser currentUser, Model model,
                       RedirectAttributes redirectAttributes) {
        ShipOrder order = order(orderId);
        if (result.hasErrors()) {
            String blocked = blocked(order, redirectAttributes);
            return blocked != null ? blocked : showForm(order, form, model);
        }
        RfqService.Sent sent = rfqService.send(user(currentUser), orderId, new RfqService.Request(form.getPort(),
                form.getDeliveryDate(), form.getReplyBy(), form.getSupplierIds()));
        switch (sent.outcome()) {
            case DONE -> {
                if (sent.failed() > 0) {
                    redirectAttributes.addFlashAttribute("errorMessage", "rfq.sent.failed");
                } else {
                    redirectAttributes.addFlashAttribute("successMessage", "rfq.sent");
                    redirectAttributes.addFlashAttribute("successArgs",
                            new Object[] {sent.rfq().getId(), sent.rfq().getQuotes().size()});
                }
                return "redirect:" + rfqUrl(sent.rfq());
            }
            case ALREADY_OPEN -> redirectAttributes.addFlashAttribute("errorMessage", "rfq.error.alreadyOpen");
            case ORDER_NOT_SENT -> redirectAttributes.addFlashAttribute("errorMessage", "rfq.error.orderNotSent");
            case NO_SUPPLIERS -> {
                redirectAttributes.addFlashAttribute("errorMessage", "rfq.error.suppliersRequired");
                return "redirect:/office/orders/" + orderId + "/rfq/new";
            }
        }
        return "redirect:/office/orders/" + orderId;
    }

    private String showForm(ShipOrder order, RfqForm form, Model model) {
        List<Supplier> suppliers = rfqService.suppliersFor(form.getPort());
        model.addAttribute("order", order);
        model.addAttribute("form", form);
        model.addAttribute("suppliers", suppliers);
        model.addAttribute("serving", suppliers.stream().filter(s -> s.serves(form.getPort())).count());
        model.addAttribute("groups", groups(order));
        return "rfq/form";
    }

    // Why no RFQ can be started for this order (not SENT, or one is open): back to the order (or its open RFQ).
    private String blocked(ShipOrder order, RedirectAttributes redirectAttributes) {
        if (order.getStatus() != com.nereonix.nereonet_mvp.core.order.ShipOrderStatus.SENT) {
            redirectAttributes.addFlashAttribute("errorMessage", "rfq.error.orderNotSent");
            return "redirect:/office/orders/" + order.getId();
        }
        Rfq open = rfqService.open(order.getId()).orElse(null);
        if (open != null) {
            redirectAttributes.addFlashAttribute("errorMessage", "rfq.error.alreadyOpen");
            return "redirect:" + rfqUrl(open);
        }
        return null;
    }

    // The active suppliers ticked, by name.
    private List<Supplier> chosen(RfqForm form) {
        return rfqService.suppliersFor(null).stream()
                .filter(s -> form.getSupplierIds().contains(s.getId())).toList();
    }

    private static List<LineGroup> groups(ShipOrder order) {
        Map<ItemCategory, List<ShipOrderLine>> byCategory = new EnumMap<>(ItemCategory.class);
        for (ShipOrderLine line : ShipOrderService.byCategoryAndName(order.getLines())) {
            byCategory.computeIfAbsent(line.getItem().getCategory(), c -> new ArrayList<>()).add(line);
        }
        return byCategory.entrySet().stream().map(e -> new LineGroup(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The RFQ
    // ---------------------------------------------------------------------------------------------------------------

    @GetMapping("/office/orders/{orderId}/rfqs/{rfqId}")
    public String detail(@PathVariable Long orderId, @PathVariable Long rfqId, Model model) {
        Rfq rfq = rfq(orderId, rfqId);
        LocalDate today = RfqService.today();
        model.addAttribute("order", rfq.getOrder());
        model.addAttribute("rfq", rfq);
        model.addAttribute("today", today);
        model.addAttribute("comparison", RfqComparison.of(rfq));
        model.addAttribute("lines", RfqWorkbook.byCategoryAndName(rfq.getLines()));
        return "rfq/detail";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Answers
    // ---------------------------------------------------------------------------------------------------------------

    @GetMapping("/office/orders/{orderId}/rfqs/{rfqId}/quotes/{quoteId}")
    public String quoteForm(@PathVariable Long orderId, @PathVariable Long rfqId, @PathVariable Long quoteId,
                            Model model, RedirectAttributes redirectAttributes) {
        Rfq rfq = rfq(orderId, rfqId);
        if (!rfq.isOpen()) {
            redirectAttributes.addFlashAttribute("errorMessage", "rfq.error.closed");
            return "redirect:" + rfqUrl(rfq);
        }
        RfqQuote quote = quote(rfq, quoteId);
        return showQuoteForm(rfq, quote, QuoteForm.of(quote), model);
    }

    @PostMapping("/office/orders/{orderId}/rfqs/{rfqId}/quotes/{quoteId}")
    public String saveQuote(@PathVariable Long orderId, @PathVariable Long rfqId, @PathVariable Long quoteId,
                            @Validated @ModelAttribute("form") QuoteForm form, BindingResult result,
                            @AuthenticationPrincipal CurrentUser currentUser, Model model,
                            RedirectAttributes redirectAttributes) {
        Rfq rfq = rfq(orderId, rfqId);
        RfqQuote quote = quote(rfq, quoteId);
        checkLines(rfq, form, result);
        if (result.hasErrors()) {
            return showQuoteForm(rfq, quote, form, model);
        }
        RfqService.Change change = rfqService.enterQuote(user(currentUser), rfq, quote, form.entry());
        flash(change, "rfq.quote.saved", quote, redirectAttributes);
        return "redirect:" + rfqUrl(rfq);
    }

    @PostMapping("/office/orders/{orderId}/rfqs/{rfqId}/quotes/{quoteId}/decline")
    public String decline(@PathVariable Long orderId, @PathVariable Long rfqId, @PathVariable Long quoteId,
                          @RequestParam(required = false) String reason,
                          @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        Rfq rfq = rfq(orderId, rfqId);
        RfqQuote quote = quote(rfq, quoteId);
        RfqService.Change change = rfqService.decline(user(currentUser), rfq, quote, reason);
        flash(change, "rfq.quote.declined", quote, redirectAttributes);
        return "redirect:" + rfqUrl(rfq);
    }

    @PostMapping("/office/orders/{orderId}/rfqs/{rfqId}/quotes/{quoteId}/resend")
    public String resend(@PathVariable Long orderId, @PathVariable Long rfqId, @PathVariable Long quoteId,
                         @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        Rfq rfq = rfq(orderId, rfqId);
        RfqQuote quote = quote(rfq, quoteId);
        RfqService.Change change = rfqService.resend(user(currentUser), rfq, quote);
        if (change == RfqService.Change.DONE
                && quote.getStatus() == com.nereonix.nereonet_mvp.core.rfq.QuoteStatus.FAILED) {
            redirectAttributes.addFlashAttribute("errorMessage", "rfq.sent.failed");
        } else {
            flash(change, "rfq.quote.resent", quote, redirectAttributes);
        }
        return "redirect:" + rfqUrl(rfq);
    }

    @GetMapping("/office/orders/{orderId}/rfqs/{rfqId}/quotes/{quoteId}/file")
    public ResponseEntity<Resource> file(@PathVariable Long orderId, @PathVariable Long rfqId,
                                         @PathVariable Long quoteId,
                                         @RequestParam(defaultValue = "false") boolean download) {
        Rfq rfq = rfq(orderId, rfqId);
        return rfqService.file(quote(rfq, quoteId), download);
    }

    private String showQuoteForm(Rfq rfq, RfqQuote quote, QuoteForm form, Model model) {
        Map<Long, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < form.getLines().size(); i++) {
            index.put(form.getLines().get(i).getLineId(), i);
        }
        Map<ItemCategory, List<QuoteRow>> rows = new EnumMap<>(ItemCategory.class);
        for (RfqLine line : RfqWorkbook.byCategoryAndName(rfq.getLines())) {
            Integer i = index.get(line.getId());
            if (i != null) {
                rows.computeIfAbsent(line.getCategory(), c -> new ArrayList<>()).add(new QuoteRow(i, line));
            }
        }
        model.addAttribute("order", rfq.getOrder());
        model.addAttribute("rfq", rfq);
        model.addAttribute("quote", quote);
        model.addAttribute("form", form);
        model.addAttribute("groups", rows.entrySet().stream()
                .map(e -> new QuoteGroup(e.getKey(), List.copyOf(e.getValue()))).toList());
        return "rfq/quote";
    }

    /**
     * The rules of a line that need the RFQ line: a price needs a quantity, and a pack unit must compare with the
     * item's unit (kg and g do, kg and lt do not). A line of another RFQ (a changed hidden field) is refused.
     */
    static void checkLines(Rfq rfq, QuoteForm form, BindingResult result) {
        Map<Long, RfqLine> lines = rfq.getLines().stream().collect(Collectors.toMap(RfqLine::getId, Function.identity()));
        for (int i = 0; i < form.getLines().size(); i++) {
            QuoteForm.Line l = form.getLines().get(i);
            RfqLine line = lines.get(l.getLineId());
            if (line == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            String prefix = "lines[" + i + "].";
            if (l.getUnitPrice() != null && l.getQuantityOffered() == null
                    && !result.hasFieldErrors(prefix + "quantityOffered")) {
                result.rejectValue(prefix + "quantityOffered", "quote.error.quantityRequired");
            }
            if (l.getPackUnit() != null && !l.getPackUnit().isBlank() && l.getPackSize() == null
                    && !result.hasFieldErrors(prefix + "packSize")) {
                result.rejectValue(prefix + "packSize", "quote.error.packSizeRequired");
            }
            if (l.getPackSize() != null && l.getPackUnit() != null && !l.getPackUnit().isBlank()
                    && !Units.convertible(l.getPackUnit(), line.getUnit())
                    && !result.hasFieldErrors(prefix + "packUnit")) {
                result.rejectValue(prefix + "packUnit", "quote.error.packUnit", new Object[] {line.getUnit()}, null);
            }
        }
        if (form.getLines().size() != lines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Award and cancel (both ask first, P10)
    // ---------------------------------------------------------------------------------------------------------------

    @GetMapping("/office/orders/{orderId}/rfqs/{rfqId}/quotes/{quoteId}/award")
    public String confirmAward(@PathVariable Long orderId, @PathVariable Long rfqId, @PathVariable Long quoteId,
                               Model model) {
        Rfq rfq = rfq(orderId, rfqId);
        RfqQuote quote = quote(rfq, quoteId);
        return confirmPages.neutral()
                .title("rfq.award.confirm.title", quote.getSupplier().getName())
                .body("rfq.award.confirm.body", rfq.getId())
                .confirmLabel("rfq.award")
                .action(rfqUrl(rfq) + "/quotes/" + quote.getId() + "/award", null)
                .cancelUrl(rfqUrl(rfq))
                .crumb("nav.orders", "/office/orders")
                .crumbText("#" + orderId, "/office/orders/" + orderId)
                .crumbText(rfq.number(), rfqUrl(rfq))
                .show(model);
    }

    @PostMapping("/office/orders/{orderId}/rfqs/{rfqId}/quotes/{quoteId}/award")
    public String award(@PathVariable Long orderId, @PathVariable Long rfqId, @PathVariable Long quoteId,
                        @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        Rfq rfq = rfq(orderId, rfqId);
        RfqQuote quote = quote(rfq, quoteId);
        RfqService.Change change = rfqService.award(user(currentUser), rfq, quote);
        if (change == RfqService.Change.DONE) {
            redirectAttributes.addFlashAttribute("successMessage", "rfq.awarded");
            redirectAttributes.addFlashAttribute("successArgs", new Object[] {quote.getSupplier().getName()});
            return "redirect:/office/orders/" + orderId;
        }
        flash(change, null, quote, redirectAttributes);
        return "redirect:" + rfqUrl(rfq);
    }

    @GetMapping("/office/orders/{orderId}/rfqs/{rfqId}/cancel")
    public String confirmCancel(@PathVariable Long orderId, @PathVariable Long rfqId, Model model) {
        Rfq rfq = rfq(orderId, rfqId);
        return confirmPages.danger()
                .title("rfq.cancel.confirm.title", rfq.getId())
                .body("rfq.cancel.confirm.body")
                .confirmLabel("rfq.cancel")
                .dismissLabel("rfq.cancel.keep")
                .action(rfqUrl(rfq) + "/cancel", null)
                .cancelUrl(rfqUrl(rfq))
                .crumb("nav.orders", "/office/orders")
                .crumbText("#" + orderId, "/office/orders/" + orderId)
                .crumbText(rfq.number(), rfqUrl(rfq))
                .show(model);
    }

    @PostMapping("/office/orders/{orderId}/rfqs/{rfqId}/cancel")
    public String cancel(@PathVariable Long orderId, @PathVariable Long rfqId,
                         @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        Rfq rfq = rfq(orderId, rfqId);
        RfqService.Change change = rfqService.cancel(user(currentUser), rfq);
        if (change == RfqService.Change.DONE) {
            redirectAttributes.addFlashAttribute("successMessage", "rfq.cancelled");
            redirectAttributes.addFlashAttribute("successArgs", new Object[] {rfq.getId()});
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", "rfq.error.closed");
        }
        return "redirect:" + rfqUrl(rfq);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void flash(RfqService.Change change, String doneKey, RfqQuote quote,
                              RedirectAttributes redirectAttributes) {
        switch (change) {
            case DONE -> {
                redirectAttributes.addFlashAttribute("successMessage", doneKey);
                redirectAttributes.addFlashAttribute("successArgs", new Object[] {quote.getSupplier().getName()});
            }
            case CLOSED -> redirectAttributes.addFlashAttribute("errorMessage", "rfq.error.closed");
            case NOT_ALLOWED -> redirectAttributes.addFlashAttribute("errorMessage", "rfq.error.notAllowed");
        }
    }

    static String rfqUrl(Rfq rfq) {
        return "/office/orders/" + rfq.getOrder().getId() + "/rfqs/" + rfq.getId();
    }

    private ShipOrder order(Long id) {
        return orderService.findSent(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Rfq rfq(Long orderId, Long rfqId) {
        order(orderId);
        return rfqService.find(orderId, rfqId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private RfqQuote quote(Rfq rfq, Long quoteId) {
        return rfqService.quote(rfq, quoteId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private User user(CurrentUser currentUser) {
        return userRepository.findById(currentUser.getId()).orElseThrow();
    }
}
