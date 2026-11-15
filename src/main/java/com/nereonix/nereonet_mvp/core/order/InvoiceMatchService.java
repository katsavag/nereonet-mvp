package com.nereonix.nereonet_mvp.core.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Matches the lines read from an order's invoice to the order (P63) and keeps the names learned. ensureMatched runs
 * when the count or the approval opens: every invoice line not matched yet (new, or read again) is matched once, by
 * the name learned for its supplier and description (SupplierItemName), else InvoiceMatcher over the order's lines
 * not removed, else over the vessel's other active items, else PICK. The pack factor is worked out per match
 * (InvoiceMatcher.conversion). Callers hold the order's row lock (DeliveryCountService, OrderReceiptService).
 */
@Service
public class InvoiceMatchService {

    private final DeliveryInvoiceRepository invoiceRepository;
    private final DeliveryInvoiceLineRepository lineRepository;
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierItemNameRepository nameRepository;
    private final AuditService auditService;

    public InvoiceMatchService(DeliveryInvoiceRepository invoiceRepository, DeliveryInvoiceLineRepository lineRepository,
                               ItemRepository itemRepository, SupplierRepository supplierRepository,
                               SupplierItemNameRepository nameRepository, AuditService auditService) {
        this.invoiceRepository = invoiceRepository;
        this.lineRepository = lineRepository;
        this.itemRepository = itemRepository;
        this.supplierRepository = supplierRepository;
        this.nameRepository = nameRepository;
        this.auditService = auditService;
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
                .stream().filter(line -> line.getMatch() == null).toList();
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
            unlink(line, InvoiceMatch.PICK);
        }
        // Two lines matched to one product: only the best stays matched, the others are to review.
        automatic.stream().filter(auto -> auto.line().getMatch() == InvoiceMatch.MATCHED)
                .collect(Collectors.groupingBy(Auto::target)).values().stream().filter(group -> group.size() > 1)
                .forEach(group -> group.stream().sorted(Comparator.comparingDouble(Auto::score).reversed()).skip(1)
                        .forEach(auto -> auto.line().setMatch(InvoiceMatch.CHECK)));
    }

    /** What the cook may choose for an invoice line: the order lines nothing is matched to yet, then the vessel's items. */
    public record Choices(List<ShipOrderLine> orderLines, List<Item> items) {
    }

    /** The choices, optionally only those whose name has the words typed (query). */
    @Transactional(readOnly = true)
    public Choices choices(ShipOrder order, String query) {
        CountSheet sheet = sheet(order);
        Set<Long> matched = new HashSet<>();
        sheet.getRows().stream().filter(row -> row.getOrderLine() != null && !row.getInvoiceLines().isEmpty())
                .forEach(row -> matched.add(row.getOrderLine().getId()));
        // The vessel's items off the order that have a row of their own already.
        Set<Long> extras = new HashSet<>();
        sheet.getRows().stream().filter(row -> row.getExtra() != null).forEach(row -> extras.add(row.getItem().getId()));
        String folded = InvoiceMatcher.fold(query == null ? "" : query.strip());
        List<ShipOrderLine> lines = order.getLines().stream()
                .filter(line -> !line.isRemoved() && !matched.contains(line.getId()))
                .filter(line -> InvoiceMatcher.fold(line.getItem().getName()).contains(folded))
                .sorted(Comparator.comparing(line -> line.getItem().getName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<Item> items = otherItems(order).stream().filter(item -> !extras.contains(item.getId()))
                .filter(item -> InvoiceMatcher.fold(item.getName()).contains(folded)).toList();
        return new Choices(lines, items);
    }

    /**
     * The cook's choice for an invoice line (the order is locked, the count editable): an order line not removed, a
     * vessel item not on the order, or neither ("Not ordered"). A product chosen is learned for the supplier
     * (SupplierItemName, "Invoice line matched by hand"); what was counted on the line's own row before is cleared.
     * IllegalArgumentException for a line of another order's invoice, or a product the line cannot be.
     */
    void choose(ShipOrder order, Long invoiceLineId, Long orderLineId, Long itemId, User cook) {
        DeliveryInvoiceLine line = line(order, invoiceLineId);
        line.setCountedQuantity(null);
        line.setCountNote(null);
        line.setApprovedQuantity(null);
        if (orderLineId == null && itemId == null) {
            unlink(line, InvoiceMatch.NOT_ORDERED);
            return;
        }
        ShipOrderLine orderLine = null;
        Item item;
        if (orderLineId != null) {
            orderLine = order.getLines().stream().filter(l -> l.getId().equals(orderLineId) && !l.isRemoved())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Not a line of this order"));
            item = orderLine.getItem();
        } else {
            item = otherItems(order).stream().filter(i -> i.getId().equals(itemId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Not an item of the vessel off the order"));
        }
        Conversion conversion = conversion(line, item);
        link(line, InvoiceMatch.CHOSEN, orderLine, item, conversion);
        learn(order, line, item, conversion, cook);
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
