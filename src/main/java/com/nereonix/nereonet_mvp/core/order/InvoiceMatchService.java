package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.ai.InvoiceLineMatcher;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatcher.Candidate;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatcher.Conversion;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatcher.Outcome;
import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.supplier.SupplierItemName;
import com.nereonix.nereonet_mvp.core.supplier.SupplierItemNameRepository;
import com.nereonix.nereonet_mvp.core.supplier.SupplierRepository;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * Matches the lines read from an order's invoice to the order and keeps the names learned. ensureMatched runs
 * when the count or the approval opens: every invoice line not matched yet (new, or read again) is matched once, by
 * the name learned for its supplier and description (SupplierItemName), else InvoiceMatcher over the order's lines
 * not removed, else over the vessel's other active items, else the vessel's item that has the name a new item would
 * get (CountSheet.newItemName), else PICK (a new item's row). The pack factor is worked out per match
 * (InvoiceMatcher.conversion). Callers hold the order's row lock (DeliveryCountService, OrderReceiptService).
 * At the end of the invoice's reading (InvoiceReadingService) the lines that ended as PICK go to Claude once
 * (claudeRequest, applyClaude): a sure answer links the line as AI, anything else leaves it PICK.
 */
@Service
public class InvoiceMatchService {

    private final DeliveryInvoiceRepository invoiceRepository;
    private final DeliveryInvoiceLineRepository lineRepository;
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierItemNameRepository nameRepository;
    private final AuditService auditService;
    private final double aiMatchMin;

    public InvoiceMatchService(DeliveryInvoiceRepository invoiceRepository, DeliveryInvoiceLineRepository lineRepository,
                               ItemRepository itemRepository, SupplierRepository supplierRepository,
                               SupplierItemNameRepository nameRepository, AuditService auditService,
                               @Value("${app.invoice.ai-match-min:0.8}") double aiMatchMin) {
        this.invoiceRepository = invoiceRepository;
        this.lineRepository = lineRepository;
        this.itemRepository = itemRepository;
        this.supplierRepository = supplierRepository;
        this.nameRepository = nameRepository;
        this.auditService = auditService;
        this.aiMatchMin = aiMatchMin;
    }

    /** The count's rows of an order, its invoice's lines as matched so far. */
    @Transactional(readOnly = true)
    public CountSheet sheet(ShipOrder order) {
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(order.getId()).orElse(null);
        List<DeliveryInvoiceLine> lines = invoice == null ? List.of()
                : lineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId());
        return CountSheet.of(order, invoice, lines);
    }

    /** Matches the invoice lines of a CONFIRMED order not matched yet (nothing while its invoice is being read). */
    @Transactional
    public void ensureMatched(ShipOrder order) {
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(order.getId()).orElse(null);
        if (invoice == null || !CountSheet.read(invoice) || order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return;
        }
        List<DeliveryInvoiceLine> waiting = lineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId())
                .stream().filter(line -> line.getMatch() == null && !line.isRemovedOnBoard()).toList();
        if (waiting.isEmpty()) {
            return;
        }
        List<ShipOrderLine> orderLines = order.getLines().stream().filter(line -> !line.isRemoved()).toList();
        Map<Long, ShipOrderLine> lineById = orderLines.stream()
                .collect(Collectors.toMap(ShipOrderLine::getId, Function.identity()));
        List<Item> others = otherItems(order);
        Map<Long, Item> itemById = others.stream().collect(Collectors.toMap(Item::getId, Function.identity()));
        List<Candidate> lineCandidates = orderLines.stream()
                .map(line -> new Candidate(line.getId(), line.getItem().getName())).toList();
        List<Candidate> itemCandidates = others.stream().map(item -> new Candidate(item.getId(), item.getName())).toList();
        Supplier supplier = supplier(invoice);

        record Auto(DeliveryInvoiceLine line, String target, double score) {
        }
        List<Auto> automatic = new ArrayList<>();
        for (DeliveryInvoiceLine line : waiting) {
            SupplierItemName learned = learned(order, invoice, supplier, line.getDescription());
            if (learned != null) {
                Item item = learned.getItem();
                ShipOrderLine orderLine = orderLines.stream()
                        .filter(l -> l.getItem().getId().equals(item.getId())).findFirst().orElse(null);
                if (orderLine != null || itemById.containsKey(item.getId())) {
                    link(line, InvoiceMatch.LEARNED, orderLine, item, InvoiceMatcher.learned(line.getDescription(),
                            line.getUnit(), item.getUnit(), learned.getPackFactor()));
                    continue;
                }
            }
            Outcome outcome = InvoiceMatcher.best(line.getDescription(), lineCandidates);
            if (outcome.found()) {
                ShipOrderLine orderLine = lineById.get(outcome.id());
                link(line, outcome.match(), orderLine, orderLine.getItem(), conversion(line, orderLine.getItem()));
                automatic.add(new Auto(line, "o" + orderLine.getId(), outcome.score()));
                continue;
            }
            outcome = InvoiceMatcher.best(line.getDescription(), itemCandidates);
            if (outcome.found()) {
                Item item = itemById.get(outcome.id());
                link(line, outcome.match(), null, item, conversion(line, item));
                automatic.add(new Auto(line, "i" + item.getId(), outcome.score()));
                continue;
            }
            // A new item whose name the vessel has already is that item, as the approval would make it.
            String name = CountSheet.newItemName(line.getDescription());
            Item named = name.isEmpty() ? null : itemRepository
                    .findFirstByShipIdAndNameIgnoreCaseOrderByIdAsc(order.getShip().getId(), name).orElse(null);
            if (named != null) {
                link(line, InvoiceMatch.MATCHED, orderLines.stream()
                        .filter(l -> l.getItem().getId().equals(named.getId())).findFirst().orElse(null), named,
                        conversion(line, named));
                continue;
            }
            unlink(line, InvoiceMatch.PICK);
        }
        // Two lines matched to one product: only the best stays matched, the others are to review.
        automatic.stream().filter(auto -> auto.line().getMatch() == InvoiceMatch.MATCHED)
                .collect(Collectors.groupingBy(Auto::target)).values().stream().filter(group -> group.size() > 1)
                .forEach(group -> group.stream().sorted(Comparator.comparingDouble(Auto::score).reversed()).skip(1)
                        .forEach(auto -> auto.line().setMatch(InvoiceMatch.CHECK)));
    }

    /**
     * What goes to Claude for an order's invoice, and what its numbers stand for: lineIds the invoice line of each
     * line, targets the order line or the vessel item of each candidate, both in the request's order (no 1 first).
     */
    public record ClaudeRequest(List<InvoiceLineMatcher.Line> lines, List<InvoiceLineMatcher.Candidate> candidates,
                                List<Long> lineIds, List<Target> targets) {
    }

    /** A candidate: a line of the order (orderLineId), or an item of the vessel not on it (itemId). */
    public record Target(Long orderLineId, Long itemId) {
    }

    /**
     * At the end of the invoice's reading (the order locked): matches the lines as ensureMatched, then gathers the
     * ones that ended as PICK and Claude was never asked about, with the candidates: the order's lines not removed
     * first, then the vessel's other active items. Null when there is nothing to ask.
     */
    @Transactional
    public ClaudeRequest claudeRequest(ShipOrder order) {
        ensureMatched(order);
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(order.getId()).orElse(null);
        if (invoice == null || !CountSheet.read(invoice) || order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return null;
        }
        List<DeliveryInvoiceLine> open = lineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId()).stream()
                .filter(line -> line.getMatch() == InvoiceMatch.PICK && !line.isAiMatchTried() && !line.isRemovedOnBoard()
                        && line.getDescription() != null && !line.getDescription().isBlank())
                .toList();
        List<InvoiceLineMatcher.Candidate> candidates = new ArrayList<>();
        List<Target> targets = new ArrayList<>();
        for (ShipOrderLine line : order.getLines()) {
            if (!line.isRemoved()) {
                targets.add(new Target(line.getId(), null));
                candidates.add(new InvoiceLineMatcher.Candidate(targets.size(), line.getItem().getName(),
                        line.getItem().getUnit()));
            }
        }
        for (Item item : otherItems(order)) {
            targets.add(new Target(null, item.getId()));
            candidates.add(new InvoiceLineMatcher.Candidate(targets.size(), item.getName(), item.getUnit()));
        }
        if (open.isEmpty() || candidates.isEmpty()) {
            return null;
        }
        List<InvoiceLineMatcher.Line> lines = new ArrayList<>();
        for (DeliveryInvoiceLine line : open) {
            lines.add(new InvoiceLineMatcher.Line(lines.size() + 1,
                    InvoiceMatcher.productAndPack(line.getDescription()), line.getUnit()));
        }
        return new ClaudeRequest(List.copyOf(lines), List.copyOf(candidates),
                open.stream().map(DeliveryInvoiceLine::getId).toList(), List.copyOf(targets));
    }

    /**
     * Claude's answer for a request (the order locked; empty when the call failed): every line asked about is marked
     * as tried. A line is linked as AI when the answer names a candidate and is at least app.invoice.ai-match-min
     * sure, the line is still as it was (PICK, not removed, not counted) and the candidate is still a line of the
     * order or an active item off it; the unit conversion is worked out as for a MATCHED line. Nothing is learned.
     * One audit entry when something was linked ("Order #12, 3 invoice lines matched by Claude"). The lines linked.
     */
    @Transactional
    public int applyClaude(ShipOrder order, ClaudeRequest request, List<InvoiceLineMatcher.Match> matches) {
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(order.getId()).orElse(null);
        if (invoice == null) {
            return 0;
        }
        Map<Long, DeliveryInvoiceLine> byId = lineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId())
                .stream().collect(Collectors.toMap(DeliveryInvoiceLine::getId, Function.identity()));
        Map<Integer, InvoiceLineMatcher.Match> answers = matches.stream()
                .collect(Collectors.toMap(InvoiceLineMatcher.Match::line, Function.identity(), (a, b) -> a));
        boolean open = order.getStatus() == ShipOrderStatus.CONFIRMED && order.getDeliveryCountStatus().editable();
        List<Item> others = otherItems(order);
        int linked = 0;
        for (int i = 0; i < request.lineIds().size(); i++) {
            DeliveryInvoiceLine line = byId.get(request.lineIds().get(i));
            if (line == null) {
                continue;
            }
            line.setAiMatchTried(true);
            InvoiceLineMatcher.Match match = answers.get(i + 1);
            if (!open || match == null || match.confidence() < aiMatchMin || match.candidate() < 1
                    || match.candidate() > request.targets().size() || line.getMatch() != InvoiceMatch.PICK
                    || line.isRemovedOnBoard() || line.getCountedQuantity() != null) {
                continue;
            }
            Target target = request.targets().get(match.candidate() - 1);
            ShipOrderLine orderLine = target.orderLineId() == null ? null : order.getLines().stream()
                    .filter(l -> l.getId().equals(target.orderLineId()) && !l.isRemoved()).findFirst().orElse(null);
            Item item = orderLine != null ? orderLine.getItem() : target.itemId() == null ? null
                    : others.stream().filter(o -> o.getId().equals(target.itemId())).findFirst().orElse(null);
            if (item == null) {
                continue;
            }
            link(line, InvoiceMatch.AI, orderLine, item, conversion(line, item));
            linked++;
        }
        if (linked > 0) {
            auditService.record("ORDER_INVOICE_CLAUDE_MATCHED", order.getShip(), "Order #" + order.getId() + ", " + linked
                    + (linked == 1 ? " invoice line" : " invoice lines") + " matched by Claude", invoice.getReadBy());
        }
        return linked;
    }

    /** What a line added by hand may be: lines of the order, then the vessel's other items. */
    public record Choices(List<ShipOrderLine> orderLines, List<Item> items) {
    }

    /**
     * What a line added by hand may be ("Add a line from the paper invoice"): every line of the order not removed, by
     * the item's name, then the vessel's other active items.
     */
    @Transactional(readOnly = true)
    public Choices addable(ShipOrder order) {
        List<ShipOrderLine> lines = order.getLines().stream().filter(line -> !line.isRemoved())
                .sorted(Comparator.comparing(line -> line.getItem().getName(), String.CASE_INSENSITIVE_ORDER)).toList();
        return new Choices(lines, otherItems(order));
    }

    /**
     * A line of the paper invoice the photo missed, added by the cook (the order is locked, the count editable): a new
     * invoice line marked addedOnBoard, for the item and its order line when it was ordered, the quantity in the item's
     * unit. IllegalArgumentException without a read invoice, for an item that is not one of addable, or one that has
     * an invoice line already.
     */
    DeliveryInvoiceLine addByHand(ShipOrder order, Long itemId, BigDecimal quantity) {
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(order.getId()).filter(CountSheet::read)
                .orElseThrow(() -> new IllegalArgumentException("The order has no read invoice"));
        ShipOrderLine orderLine = order.getLines().stream()
                .filter(l -> !l.isRemoved() && l.getItem().getId().equals(itemId)).findFirst().orElse(null);
        Item item = orderLine != null ? orderLine.getItem()
                : otherItems(order).stream().filter(i -> i.getId().equals(itemId)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Not an item of the vessel"));
        if (onInvoice(sheet(order), item.getId())) {
            throw new IllegalArgumentException("The item is on the invoice already");
        }
        DeliveryInvoiceLine line = new DeliveryInvoiceLine();
        line.setInvoice(invoice);
        line.setPage(0);
        line.setDescription(cut(item.getName(), DeliveryInvoiceLine.DESCRIPTION_MAX_LENGTH));
        line.setUnit(cut(item.getUnit(), 30));
        line.setQuantity(quantity);
        line.setAddedOnBoard(true);
        link(line, InvoiceMatch.CHOSEN, orderLine, item, Conversion.SAME);
        return lineRepository.save(line);
    }

    /** The item has a row with an invoice line on this sheet. */
    public static boolean onInvoice(CountSheet sheet, Long itemId) {
        return sheet.getRows().stream()
                .anyMatch(row -> itemId.equals(row.getItem().getId()) && !row.getInvoiceLines().isEmpty());
    }

    /**
     * "Not this item": the cook undoes what Claude matched an invoice line to (the order is locked, the count
     * editable). The line is matched to nothing again (PICK: a new item's row) and what was counted on its own row is
     * cleared; Claude is not asked about it again (aiMatchTried stays). False, nothing changed, for a line that is
     * not matched by Claude; IllegalArgumentException for a line of another order's invoice.
     */
    boolean unmatch(ShipOrder order, Long invoiceLineId) {
        DeliveryInvoiceLine line = line(order, invoiceLineId);
        if (line.getMatch() != InvoiceMatch.AI) {
            return false;
        }
        line.setCountedQuantity(null);
        line.setCountNote(null);
        line.setApprovedQuantity(null);
        unlink(line, InvoiceMatch.PICK);
        return true;
    }

    /** An invoice line of the order's invoice; IllegalArgumentException otherwise. */
    DeliveryInvoiceLine line(ShipOrder order, Long invoiceLineId) {
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new IllegalArgumentException("The order has no invoice"));
        return lineRepository.findById(invoiceLineId)
                .filter(line -> line.getInvoice().getId().equals(invoice.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Not a line of this order's invoice"));
    }

    /**
     * Learns that the invoice line's description, from its invoice's supplier, is the item (replacing what was learned
     * for that description before), and records "Invoice line matched by hand".
     */
    void learn(ShipOrder order, DeliveryInvoiceLine line, Item item, Conversion conversion, User by) {
        DeliveryInvoice invoice = line.getInvoice();
        Supplier supplier = supplier(invoice);
        String key = InvoiceMatcher.key(line.getDescription());
        if (key.isEmpty()) {
            return;
        }
        nameRepository.deleteAll(names(order, invoice, supplier, key));
        SupplierItemName name = new SupplierItemName();
        name.setSupplier(supplier);
        name.setSupplierName(cut(supplierKey(invoice), 200));
        name.setDescription(key);
        name.setItem(item);
        name.setPackFactor(conversion == null ? null : conversion.factor());
        name.setCreatedBy(by);
        name.setCreatedAt(Instant.now());
        nameRepository.save(name);
        auditService.record("ORDER_INVOICE_LINE_MATCHED", order.getShip(), "Order #" + order.getId() + ": \""
                + cut(line.getDescription(), 120) + "\" is " + item.getName()
                + (conversion != null && conversion.packed() ? " (1 " + conversion.unitOne() + " = "
                + conversion.factor().toPlainString() + " " + item.getUnit() + ")" : ""), by);
    }

    // The vessel's active items not on the order (on no line, removed or not: one line per item).
    private List<Item> otherItems(ShipOrder order) {
        Set<Long> onOrder = order.getLines().stream().map(line -> line.getItem().getId()).collect(Collectors.toSet());
        return itemRepository.findAllByShipIdOrderByNameAsc(order.getShip().getId()).stream()
                .filter(Item::isActive).filter(item -> !onOrder.contains(item.getId())).toList();
    }

    private static Conversion conversion(DeliveryInvoiceLine line, Item item) {
        return InvoiceMatcher.conversion(line.getDescription(), line.getUnit(), item.getUnit());
    }

    private static void link(DeliveryInvoiceLine line, InvoiceMatch match, ShipOrderLine orderLine, Item item,
                             Conversion conversion) {
        line.setMatch(match);
        line.setOrderLine(orderLine);
        line.setItem(item);
        line.setPackFactor(conversion == null ? null : conversion.factor());
        line.setPack(conversion == null ? null : conversion.pack());
        line.setPackUnitOne(conversion == null ? null : conversion.unitOne());
        line.setPackUnitMany(conversion == null ? null : conversion.unitMany());
        line.setConversionToReview(conversion != null && conversion.converted());
    }

    private static void unlink(DeliveryInvoiceLine line, InvoiceMatch match) {
        link(line, match, null, null, null);
    }

    // The supplier the invoice is from, when its name is one of the suppliers'.
    private Supplier supplier(DeliveryInvoice invoice) {
        if (invoice.getSupplierName() == null || invoice.getSupplierName().isBlank()) {
            return null;
        }
        List<Supplier> suppliers = supplierRepository.findAll();
        Long id = InvoiceMatcher.supplier(invoice.getSupplierName(),
                suppliers.stream().map(s -> new Candidate(s.getId(), s.getName())).toList());
        return id == null ? null : suppliers.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
    }

    private static String supplierKey(DeliveryInvoice invoice) {
        return InvoiceMatcher.supplierKey(invoice.getSupplierName());
    }

    // The newest name learned for this description from this supplier, on the order's vessel.
    private SupplierItemName learned(ShipOrder order, DeliveryInvoice invoice, Supplier supplier, String description) {
        String key = InvoiceMatcher.key(description);
        if (key.isEmpty()) {
            return null;
        }
        return names(order, invoice, supplier, key).stream().filter(name -> name.getItem().isActive())
                .max(Comparator.comparing(SupplierItemName::getCreatedAt).thenComparing(SupplierItemName::getId))
                .orElse(null);
    }

    private List<SupplierItemName> names(ShipOrder order, DeliveryInvoice invoice, Supplier supplier, String key) {
        String supplierKey = supplierKey(invoice);
        return nameRepository.findAllByItemShipIdAndDescription(order.getShip().getId(), key).stream()
                .filter(name -> supplier != null
                        ? name.getSupplier() != null && name.getSupplier().getId().equals(supplier.getId())
                        : name.getSupplier() == null && name.getSupplierName().equals(supplierKey))
                .toList();
    }

    private static String cut(String text, int max) {
        String value = text == null ? "" : text.strip();
        return value.length() > max ? value.substring(0, max) : value;
    }
}
