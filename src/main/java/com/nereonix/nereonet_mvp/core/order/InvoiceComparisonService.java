package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.order.InvoiceComparison.ReviewItem;
import com.nereonix.nereonet_mvp.core.order.InvoiceComparison.ReviewKind;
import com.nereonix.nereonet_mvp.core.order.InvoiceComparison.Row;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuoteLine;
import com.nereonix.nereonet_mvp.core.rfq.RfqService;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * The office compares order, delivery and invoice (InvoiceComparison) once the Master confirmed receipt of an
 * order with a read invoice: it accepts each difference with a note, confirms or changes each point to review (a
 * product match or a pack conversion is then learned for the supplier, InvoiceMatchService.learn; a line read wrong is
 * corrected, keeping the value as read), and marks the order MATCHED. Changing a match or a conversion after receipt
 * corrects the comparison and the name learned only, never the store: the vessel's Chief cook is asked to count those
 * items. ShipOrder.invoiceStatus and invoiceDiffLines are kept up to date (refresh) for the Overview and the list.
 * Every change locks the order's row; office users only; nothing changes once the order is MATCHED.
 */
@Service
public class InvoiceComparisonService {

    /** A pack factor is at most this (InvoiceMatcher's believable factors). */
    static final BigDecimal MAX_FACTOR = new BigDecimal("100000");
    /** A unit price or an amount the office types is at most this. */
    static final BigDecimal MAX_MONEY = new BigDecimal("1000000");

    private final DeliveryInvoiceRepository invoiceRepository;
    private final DeliveryInvoiceLineRepository lineRepository;
    private final InvoiceDecisionRepository decisionRepository;
    private final ShipOrderLineRepository orderLineRepository;
    private final ItemRepository itemRepository;
    private final InvoiceMatchService matchService;
    private final RfqService rfqService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final MessageSource messageSource;
    private final EntityManager entityManager;

    public InvoiceComparisonService(DeliveryInvoiceRepository invoiceRepository,
                                    DeliveryInvoiceLineRepository lineRepository,
                                    InvoiceDecisionRepository decisionRepository,
                                    ShipOrderLineRepository orderLineRepository, ItemRepository itemRepository,
                                    InvoiceMatchService matchService, RfqService rfqService, AuditService auditService,
                                    NotificationService notificationService, UserRepository userRepository,
                                    MessageSource messageSource, EntityManager entityManager) {
        this.invoiceRepository = invoiceRepository;
        this.lineRepository = lineRepository;
        this.decisionRepository = decisionRepository;
        this.orderLineRepository = orderLineRepository;
        this.itemRepository = itemRepository;
        this.matchService = matchService;
        this.rfqService = rfqService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.messageSource = messageSource;
        this.entityManager = entityManager;
    }

    /** What an office action did. */
    public enum Outcome {
        DONE,
        /** Changing a match or a conversion: done, and the store was updated with the old one (the cook was told). */
        DONE_STOCK,
        /** Nothing to decide here: no comparison, already MATCHED, or the point is gone (a stale page). */
        NOT_OPEN,
        /** Mark as matched: differences or points to review are left. */
        OPEN_LEFT
    }

    // ---- Reading ------------------------------------------------------------------------------------------------------

    /** The comparison of an order received with a read invoice; empty otherwise. */
    @Transactional(readOnly = true)
    public Optional<InvoiceComparison> comparison(ShipOrder order) {
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(order.getId()).orElse(null);
        if (invoice == null) {
            return Optional.empty();
        }
        List<DeliveryInvoiceLine> lines = lineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId());
        if (!InvoiceComparison.applies(order, invoice, lines)) {
            return Optional.empty();
        }
        Map<Long, BigDecimal> quoted = new HashMap<>();
        String currency = null;
        Optional<RfqService.Award> award = rfqService.awarded(order.getId());
        if (award.isPresent()) {
            currency = award.get().currency();
            for (RfqQuoteLine line : award.get().quote().getLines()) {
                BigDecimal price = line.pricePerItemUnit();
                if (price != null) {
                    quoted.put(line.getLine().getItem().getId(), price);
                }
            }
        }
        Map<String, InvoiceDecision> decisions = decisionRepository.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .collect(Collectors.toMap(InvoiceDecision::getItemKey, Function.identity(), (a, b) -> b));
        return Optional.of(InvoiceComparison.of(order, invoice, lines, quoted, currency, decisions));
    }

    /** The lines read from the order's invoice, by page. */
    @Transactional(readOnly = true)
    public List<DeliveryInvoiceLine> lines(ShipOrder order) {
        return invoiceRepository.findByOrderId(order.getId())
                .map(invoice -> lineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId()))
                .orElse(List.of());
    }

    /**
     * Keeps the order's invoice status and its count of lines that differ up to date: TO_MATCH once received with a
     * read invoice (the receipt calls it, the caller holds the order's lock); a MATCHED one stays matched.
     */
    @Transactional
    public void refresh(ShipOrder order) {
        Optional<InvoiceComparison> comparison = comparison(order);
        if (comparison.isEmpty()) {
            return;
        }
        if (order.getInvoiceStatus() == null) {
            order.setInvoiceStatus(InvoiceMatchStatus.TO_MATCH);
        }
        order.setInvoiceDiffLines(comparison.get().getDifferentTotal());
    }

    // ---- Decisions ----------------------------------------------------------------------------------------------------

    /** Accept a row's difference with a note (required, checked by the caller). NOT_OPEN: the row no longer differs. */
    @Transactional
    public Outcome accept(User officeUser, Long orderId, String rowKey, String note) {
        ShipOrder order = lock(officeUser, orderId);
        InvoiceComparison comparison = open(order);
        Row row = comparison == null ? null : comparison.row(rowKey);
        if (row == null || !row.isDifferent()) {
            return Outcome.NOT_OPEN;
        }
        decide(order, row.getDecisionKey(), InvoiceDecisionKind.ACCEPTED, row.getDetail(), note, officeUser);
        auditService.record("ORDER_INVOICE_DIFFERENCE_ACCEPTED", order.getShip(), "Order #" + order.getId() + ", "
                + cut(row.getName(), 120) + ": " + row.getDetail() + ", note: " + note, officeUser);
        return Outcome.DONE;
    }

    /**
     * Correct: the point to review is right as it is. A match or a conversion is then learned for the invoice's
     * supplier (InvoiceMatchService.learn). NOT_OPEN: no such point open.
     */
    @Transactional
    public Outcome confirm(User officeUser, Long orderId, String key) {
        ShipOrder order = lock(officeUser, orderId);
        InvoiceComparison comparison = open(order);
        ReviewItem item = comparison == null ? null : comparison.reviewItem(key);
        if (item == null) {
            return Outcome.NOT_OPEN;
        }
        DeliveryInvoiceLine line = item.line();
        if ((item.kind() == ReviewKind.MATCH || item.kind() == ReviewKind.CONVERSION) && line.getItem() != null) {
            matchService.learn(order, line, line.getItem(), conversionAsIs(line), officeUser);
        }
        decide(order, key, InvoiceDecisionKind.CONFIRMED, null, null, officeUser);
        auditService.record("ORDER_INVOICE_REVIEWED", order.getShip(), "Order #" + order.getId() + ", "
                + describe(item) + ": correct", officeUser);
        return Outcome.DONE;
    }

    /** What an invoice line can be changed to: the order's lines, the vessel's items not on it. */
    public record Choices(List<ShipOrderLine> orderLines, List<Item> items) {
    }

    @Transactional(readOnly = true)
    public Choices choices(ShipOrder order) {
        Set<Long> onOrder = order.getLines().stream().map(line -> line.getItem().getId()).collect(Collectors.toSet());
        List<ShipOrderLine> lines = order.getLines().stream().filter(line -> !line.isRemoved())
                .sorted(Comparator.comparing(line -> line.getItem().getName(), String.CASE_INSENSITIVE_ORDER)).toList();
        List<Item> items = itemRepository.findAllByShipIdOrderByNameAsc(order.getShip().getId()).stream()
                .filter(Item::isActive).filter(item -> !onOrder.contains(item.getId())).toList();
        return new Choices(lines, items);
    }

    /**
     * Change: the invoice line is another product (an order line, a vessel item not on the order: a line added on
     * receipt, or neither: Not ordered). Learned for the supplier. The store is not moved: the vessel's Chief cook is
     * asked to count the items (DONE_STOCK). IllegalArgumentException for a product the line cannot be.
     */
    @Transactional
    public Outcome changeMatch(User officeUser, Long orderId, Long invoiceLineId, Long orderLineId, Long itemId) {
        ShipOrder order = lock(officeUser, orderId);
        InvoiceComparison comparison = open(order);
        if (comparison == null) {
            return Outcome.NOT_OPEN;
        }
        DeliveryInvoiceLine line = invoiceLine(comparison, invoiceLineId);
        String before = line.getItem() == null ? "nothing" : line.getItem().getName();
        Item oldItem = line.getItem();
        ShipOrderLine oldLine = line.getOrderLine();
        if (orderLineId == null && itemId == null) {
            setMatch(line, InvoiceMatch.NOT_ORDERED, null, null, null);
        } else {
            ShipOrderLine target;
            if (orderLineId != null) {
                target = order.getLines().stream().filter(l -> l.getId().equals(orderLineId) && !l.isRemoved())
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Not a line of this order"));
            } else {
                Item item = choices(order).items().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Not an item of the vessel off the order"));
                target = new ShipOrderLine();
                target.setOrder(order);
                target.setItem(item);
                target.setQuantity(BigDecimal.ZERO);
                target.setReceivedQuantity(BigDecimal.ZERO);
                target.setAddedOnReceipt(true);
                order.getLines().add(orderLineRepository.save(target));
            }
            InvoiceMatcher.Conversion conversion = InvoiceMatcher.conversion(line.getDescription(), line.getUnit(),
                    target.getItem().getUnit());
            setMatch(line, InvoiceMatch.CHOSEN, target, target.getItem(), conversion);
            matchService.learn(order, line, target.getItem(), conversion, officeUser);
        }
        if (oldLine != null && oldLine.getInvoiceLine() != null && oldLine.getInvoiceLine().getId().equals(line.getId())) {
            oldLine.setInvoiceLine(null);
        }
        decide(order, "match:" + line.getId(), InvoiceDecisionKind.CHANGED, null, null, officeUser);
        String after = line.getItem() == null ? "not ordered" : line.getItem().getName();
        auditService.record("ORDER_INVOICE_MATCH_CHANGED", order.getShip(), "Order #" + order.getId() + ": \""
                + cut(line.getDescription(), 120) + "\" " + before + " -> " + after, officeUser);
        syncInvoiced(order, comparison.getInvoiceLines());
        refresh(order);
        if (oldItem != null || line.getItem() != null) {
            askToCount(order, line, oldItem, line.getItem());
            return Outcome.DONE_STOCK;
        }
        return Outcome.DONE;
    }

    /**
     * Change a pack conversion: one invoice unit is factor of the item's unit (more than 0, at most 100000, checked
     * here too). Learned for the supplier; the store is not moved (DONE_STOCK, the cook is asked to count).
     */
    @Transactional
    public Outcome changeFactor(User officeUser, Long orderId, Long invoiceLineId, BigDecimal factor) {
        if (factor == null || factor.signum() <= 0 || factor.compareTo(MAX_FACTOR) > 0) {
            throw new IllegalArgumentException("A pack factor is more than 0 and at most " + MAX_FACTOR);
        }
        ShipOrder order = lock(officeUser, orderId);
        InvoiceComparison comparison = open(order);
        if (comparison == null) {
            return Outcome.NOT_OPEN;
        }
        DeliveryInvoiceLine line = invoiceLine(comparison, invoiceLineId);
        if (line.getItem() == null || line.getMatch() == null || !line.getMatch().linked()) {
            return Outcome.NOT_OPEN;
        }
        BigDecimal before = line.getPackFactor();
        InvoiceMatcher.Conversion conversion = InvoiceMatcher.learned(line.getDescription(), line.getUnit(),
                line.getItem().getUnit(), factor);
        if (conversion == null || conversion.factor().compareTo(factor.stripTrailingZeros()) != 0) {
            conversion = new InvoiceMatcher.Conversion(factor.stripTrailingZeros(), line.getPack(),
                    line.getPackUnitOne() != null ? line.getPackUnitOne() : line.getUnit(),
                    line.getPackUnitMany() != null ? line.getPackUnitMany() : line.getUnit(), false);
        }
        line.setPackFactor(conversion.factor());
        line.setPack(conversion.pack());
        line.setPackUnitOne(conversion.unitOne());
        line.setPackUnitMany(conversion.unitMany());
        line.setConversionToReview(false);
        matchService.learn(order, line, line.getItem(), conversion, officeUser);
        decide(order, "conv:" + line.getId(), InvoiceDecisionKind.CHANGED, null, null, officeUser);
        auditService.record("ORDER_INVOICE_CONVERSION_CHANGED", order.getShip(), "Order #" + order.getId() + ": \""
                + cut(line.getDescription(), 120) + "\" 1 " + conversion.unitOne() + " = "
                + (before == null ? "?" : before.stripTrailingZeros().toPlainString()) + " -> "
                + conversion.factor().toPlainString() + " " + line.getItem().getUnit(), officeUser);
        syncInvoiced(order, comparison.getInvoiceLines());
        refresh(order);
        askToCount(order, line, line.getItem(), line.getItem());
        return Outcome.DONE_STOCK;
    }

    /** A line read wrong, as the office corrects it (a value null: unchanged; quantity, unit price, amount). */
    public record Correction(BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount, String description) {
    }

    /**
     * Corrects a line read wrong: the values given replace the line's (the values as read stay: readQuantity, ...,
     * descriptionRead), with who and when; its point to review is then changed. IllegalArgumentException for a value
     * out of range (checked by the caller first).
     */
    @Transactional
    public Outcome correctLine(User officeUser, Long orderId, Long invoiceLineId, Correction correction) {
        check(correction);
        ShipOrder order = lock(officeUser, orderId);
        InvoiceComparison comparison = open(order);
        if (comparison == null) {
            return Outcome.NOT_OPEN;
        }
        DeliveryInvoiceLine line = invoiceLine(comparison, invoiceLineId);
        List<String> changes = new ArrayList<>();
        String quantityBefore = text(line.getQuantity());
        if (correction.quantity() != null && line.correctQuantity(correction.quantity())) {
            changes.add("quantity " + quantityBefore + " -> " + text(correction.quantity()));
        }
        if (correction.unitPrice() != null && !same(correction.unitPrice(), line.getUnitPrice())) {
            changes.add("unit price " + text(line.getUnitPrice()) + " -> " + text(correction.unitPrice()));
            line.setUnitPrice(correction.unitPrice());
        }
        if (correction.amount() != null && !same(correction.amount(), line.getAmount())) {
            changes.add("amount " + text(line.getAmount()) + " -> " + text(correction.amount()));
            line.setAmount(correction.amount());
        }
        String description = correction.description() == null ? null : correction.description().strip();
        if (description != null && !description.isEmpty() && !description.equals(line.getDescription())) {
            changes.add("description \"" + cut(line.getDescription(), 80) + "\" -> \"" + cut(description, 80) + "\"");
            if (line.getDescriptionRead() == null) {
                line.setDescriptionRead(line.getDescription());
            }
            line.setDescription(description);
        }
        if (changes.isEmpty()) {
            return Outcome.DONE;
        }
        line.setOfficeChangedBy(officeUser);
        line.setOfficeChangedAt(Instant.now());
        if (line.isFlagged()) {
            decide(order, "line:" + line.getId(), InvoiceDecisionKind.CHANGED, null, null, officeUser);
        }
        auditService.record("ORDER_INVOICE_LINE_CORRECTED", order.getShip(), "Order #" + order.getId() + ", page "
                + line.getPage() + (line.getLineNumber() == null ? "" : " line " + line.getLineNumber()) + ": "
                + String.join(", ", changes), officeUser);
        syncInvoiced(order, comparison.getInvoiceLines());
        refresh(order);
        return Outcome.DONE;
    }

    /** Checks a correction: a quantity 0 or more (3 decimals), a unit price and an amount 0 to 1000000. */
    public static void check(Correction correction) {
        if (correction.quantity() != null && !OrderReceiptService.isValidReceivedQuantity(correction.quantity())) {
            throw new IllegalArgumentException("A quantity is 0 or more, at most 3 decimals");
        }
        for (BigDecimal money : new BigDecimal[] {correction.unitPrice(), correction.amount()}) {
            if (money != null && (money.signum() < 0 || money.compareTo(MAX_MONEY) > 0)) {
                throw new IllegalArgumentException("A price or an amount is 0 to " + MAX_MONEY);
            }
        }
        if (correction.unitPrice() != null && correction.unitPrice().stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("A unit price has at most 4 decimals");
        }
        if (correction.amount() != null && correction.amount().stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("An amount has at most 2 decimals");
        }
        if (correction.description() != null && correction.description().strip().length()
                > DeliveryInvoiceLine.DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("A description is at most " + DeliveryInvoiceLine.DESCRIPTION_MAX_LENGTH);
        }
    }

    /**
     * Mark as matched: every difference accepted, every point to review confirmed or changed (OPEN_LEFT otherwise).
     * MATCHED with who and when; a shortage of the receipt is handled with it. Recorded as "Invoice matched".
     */
    @Transactional
    public Outcome markMatched(User officeUser, Long orderId) {
        ShipOrder order = lock(officeUser, orderId);
        InvoiceComparison comparison = open(order);
        if (comparison == null) {
            return Outcome.NOT_OPEN;
        }
        if (!comparison.isCanMatch()) {
            return Outcome.OPEN_LEFT;
        }
        Instant now = Instant.now();
        order.setInvoiceStatus(InvoiceMatchStatus.MATCHED);
        order.setInvoiceMatchedAt(now);
        order.setInvoiceMatchedBy(officeUser);
        order.setInvoiceDiffLines(comparison.getDifferentTotal());
        if (order.getShortLines() > 0 && order.getShortageHandledAt() == null) {
            order.setShortageHandledAt(now);
            order.setShortageHandledBy(officeUser);
            order.setShortageNote("Matched with the invoice");
        }
        List<String> accepted = comparison.getDifferent().stream()
                .map(row -> cut(row.getName(), 60) + ": " + cut(row.getDecision().getNote(), 120)).toList();
        auditService.record("ORDER_INVOICE_MATCHED", order.getShip(), "Order #" + order.getId() + ", "
                + comparison.getTotal() + (comparison.getTotal() == 1 ? " line, " : " lines, ") + accepted.size()
                + (accepted.size() == 1 ? " difference accepted" : " differences accepted")
                + (accepted.isEmpty() ? "" : ": " + cut(String.join("; ", accepted), 700)), officeUser);
        return Outcome.DONE;
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------------

    // The comparison while it can still change (TO_MATCH), or null.
    private InvoiceComparison open(ShipOrder order) {
        if (!order.isInvoiceToMatch()) {
            return null;
        }
        return comparison(order).orElse(null);
    }

    private static DeliveryInvoiceLine invoiceLine(InvoiceComparison comparison, Long invoiceLineId) {
        return comparison.getInvoiceLines().stream().filter(l -> l.getId().equals(invoiceLineId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a line of this order's invoice"));
    }

    private void decide(ShipOrder order, String key, InvoiceDecisionKind kind, String detail, String note, User by) {
        InvoiceDecision decision = decisionRepository.findByOrderIdAndItemKey(order.getId(), key).orElseGet(() -> {
            InvoiceDecision created = new InvoiceDecision();
            created.setOrder(order);
            created.setItemKey(key);
            return created;
        });
        decision.setKind(kind);
        decision.setDetail(detail);
        decision.setNote(note);
        decision.setDecidedBy(by);
        decision.setDecidedAt(Instant.now());
        decisionRepository.save(decision);
    }

    private static void setMatch(DeliveryInvoiceLine line, InvoiceMatch match, ShipOrderLine orderLine, Item item,
                                 InvoiceMatcher.Conversion conversion) {
        line.setMatch(match);
        line.setOrderLine(orderLine);
        line.setItem(item);
        line.setPackFactor(conversion == null ? null : conversion.factor());
        line.setPack(conversion == null ? null : conversion.pack());
        line.setPackUnitOne(conversion == null ? null : conversion.unitOne());
        line.setPackUnitMany(conversion == null ? null : conversion.unitMany());
        line.setConversionToReview(conversion != null && conversion.converted());
    }

    // The line's conversion as it stands, to learn it.
    private static InvoiceMatcher.Conversion conversionAsIs(DeliveryInvoiceLine line) {
        return line.getPackFactor() == null ? null : new InvoiceMatcher.Conversion(line.getPackFactor(), line.getPack(),
                line.getPackUnitOne(), line.getPackUnitMany(), false);
    }

    // The order lines keep what the invoice says of them (invoicedQuantity and invoiceLine), for the export.
    private void syncInvoiced(ShipOrder order, List<DeliveryInvoiceLine> invoiceLines) {
        for (ShipOrderLine orderLine : order.getLines()) {
            if (orderLine.isRemoved()) {
                continue;
            }
            List<DeliveryInvoiceLine> linked = invoiceLines.stream()
                    .filter(l -> l.getMatch() != null && l.getMatch().linked() && l.getOrderLine() != null
                            && l.getOrderLine().getId().equals(orderLine.getId())).toList();
            orderLine.setInvoicedQuantity(linked.isEmpty() ? null
                    : InvoiceComparison.sum(linked.stream().map(DeliveryInvoiceLine::getItemQuantity).toList()));
            orderLine.setInvoiceLine(linked.isEmpty() ? null : linked.getFirst());
        }
    }

    // The vessel's Chief cook: the store was updated with the old match or conversion; count these items.
    private void askToCount(ShipOrder order, DeliveryInvoiceLine line, Item before, Item after) {
        Set<String> names = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        for (Item item : new Item[] {before, after}) {
            if (item != null && names.add(item.getName())) {
                ordered.add(item.getName());
            }
        }
        String text = messageSource.getMessage("order.notification.countAgain",
                new Object[] {order.getId(), cut(line.getDescription(), 80), String.join(", ", ordered)},
                Locale.ENGLISH);
        for (User cook : userRepository.findAllByShipAndRoleAndEnabledTrue(order.getShip(), Role.CHIEF_COOK)) {
            notificationService.notify(cook, NotificationType.ORDER, order.getShip().getName(), text, "/ship/stores");
        }
    }

    private static String describe(ReviewItem item) {
        return switch (item.kind()) {
            case CHECK, REFERENCE -> cut(item.text(), 200);
            case LINE -> "line \"" + cut(item.line().getDescription(), 120) + "\"";
            case MATCH -> "match \"" + cut(item.line().getDescription(), 120) + "\" is " + item.line().getItem().getName();
            case CONVERSION -> "conversion \"" + cut(item.line().getDescription(), 120) + "\" 1 "
                    + item.line().getPackUnitOne() + " = " + item.line().getPackFactor().stripTrailingZeros().toPlainString()
                    + " " + item.line().getItem().getUnit();
        };
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String text(BigDecimal value) {
        return value == null ? "not read" : value.stripTrailingZeros().toPlainString();
    }

    private static String cut(String text, int max) {
        String value = text == null ? "" : text.strip();
        return value.length() > max ? value.substring(0, max) : value;
    }

    // The order, its row locked; only an enabled office user. Null: no such order the ships sent.
    private ShipOrder lock(User officeUser, Long orderId) {
        if (officeUser == null || officeUser.getRole() != Role.OFFICE_USER || !officeUser.isEnabled()) {
            throw new AccessDeniedException("Only the office compares an order with its invoice");
        }
        ShipOrder order = entityManager.find(ShipOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || !ShipOrderService.SENT_TO_OFFICE.contains(order.getStatus())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return order;
    }
}
