package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.export.XlsExport;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestLineRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.time.Dates;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;

// Ship orders, for any feature. RequestService.approve adds each approved request to the ship's OPEN order; the
// Master edits its lines and sends it to the office (P35, shiporder). Every change of the ship locks the ship's row
// first, so an approval, an edit and a send never interleave: an approval made while the order is sent goes into a
// new OPEN order. The office (P36, officeorder) lists the sent orders, schedules the delivery (port and date; P51,
// "confirms" before) and exports them; until it schedules, it can correct the lines (P50, OfficeOrderEditService).
// Only the vessel closes an order, by confirming receipt (P51, OrderReceiptService).
@Service
public class ShipOrderService {

    // ShipOrderLine.quantity is numeric(12, 3).
    static final int QUANTITY_SCALE = 3;
    static final int QUANTITY_INTEGER_DIGITS = 9;

    /** The orders the office sees: those a Master has sent. Never OPEN ones. */
    public static final List<ShipOrderStatus> SENT_TO_OFFICE = List.of(ShipOrderStatus.SENT,
            ShipOrderStatus.CONFIRMED, ShipOrderStatus.CLOSED);

    /** The orders still on the office's desk: Pending and Delivery scheduled, the sidebar's Orders counter. */
    public static final List<ShipOrderStatus> NOT_RECEIVED = List.of(ShipOrderStatus.SENT, ShipOrderStatus.CONFIRMED);

    /** The columns of the office's export (an Excel workbook since P39, .xls since P40, the same columns), one row per order
     *  line. A file format: never translated. */
    static final List<String> EXPORT_HEADER = List.of("imo_number", "ship_name", "order_id", "status", "sent_at",
            "requested_port", "needed_by", "confirmed_port", "delivery_date", "category", "item_name", "unit",
            "quantity", "sent_quantity", "office_change", "office_note", "received_quantity", "received_at",
            "invoiced_quantity", "invoice_unit", "invoice_unit_price", "invoice_amount", "difference");

    // "12" or "#12": an order number in the office's search.
    private static final Pattern ORDER_NUMBER = Pattern.compile("#?(\\d{1,18})");

    private final ShipOrderRepository orderRepository;
    private final ShipOrderLineRepository lineRepository;
    private final RequestLineRepository requestLineRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final Dates dates;
    private final EntityManager entityManager;
    private final ListQueries listQueries;
    private final OfficeOrderEditService editService;
    private final DeliveryInvoiceRepository invoiceRepository;
    private final DeliveryInvoiceLineRepository invoiceLineRepository;

    public ShipOrderService(ShipOrderRepository orderRepository, ShipOrderLineRepository lineRepository,
                            RequestLineRepository requestLineRepository, UserRepository userRepository,
                            AuditService auditService, NotificationService notificationService,
                            MessageSource messageSource, Dates dates, EntityManager entityManager,
                            ListQueries listQueries, OfficeOrderEditService editService,
                            DeliveryInvoiceRepository invoiceRepository,
                            DeliveryInvoiceLineRepository invoiceLineRepository) {
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.requestLineRepository = requestLineRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
        this.dates = dates;
        this.entityManager = entityManager;
        this.listQueries = listQueries;
        this.editService = editService;
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
    }

    /**
     * Adds an APPROVED request to its ship's OPEN order, creating the order if there is none: each line's quantity is
     * added to the order line of the same item, or a new order line is made, and the request line is linked to it.
     * Every line must have an item (RequestService.approve creates the new ones first). The ship's row is locked
     * first, so two approvals at the same time never make two OPEN orders, or two lines for one item.
     * AccessDeniedException when the request was not decided by an enabled Master of its ship.
     */
    @Transactional
    public ShipOrder addApprovedRequest(ItemRequest request) {
        if (request.getStatus() != RequestStatus.APPROVED) {
            throw new IllegalArgumentException("Only an approved request goes into the ship order");
        }
        Ship ship = request.getShip();
        User decidedBy = request.getDecidedBy();
        checkMaster(decidedBy);
        if (!decidedBy.getShip().getId().equals(ship.getId())) {
            throw new AccessDeniedException("Only the Master of the ship changes or sends its order");
        }
        lock(ship);

        ShipOrder order = orderRepository.findByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN)
                .orElseGet(() -> open(ship));
        for (RequestLine requestLine : request.getLines()) {
            if (requestLine.getItem() == null) {
                throw new IllegalArgumentException("A request line needs an item before it goes into the ship order");
            }
            ShipOrderLine line = lineRepository.findByOrderIdAndItemId(order.getId(), requestLine.getItem().getId())
                    .orElse(null);
            if (line == null) {
                line = new ShipOrderLine();
                line.setOrder(order);
                line.setItem(requestLine.getItem());
                line.setQuantity(requestLine.getQuantity());
                lineRepository.save(line);
                order.getLines().add(line);
            } else {
                line.setQuantity(line.getQuantity().add(requestLine.getQuantity()));
            }
            requestLine.setShipOrderLine(line);
        }
        return order;
    }

    /** True for a quantity an order line can hold: above zero, at most 3 decimals and 9 whole digits. */
    public static boolean isValidQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return false;
        }
        BigDecimal stripped = quantity.stripTrailingZeros();
        return stripped.scale() <= QUANTITY_SCALE && stripped.precision() - stripped.scale() <= QUANTITY_INTEGER_DIGITS;
    }

    /**
     * The Master changes the quantity of a line of their ship's OPEN order. Nobody is notified and nothing is
     * recorded in the audit log: the order is still being put together. NOT_OPEN (nothing changed) when the line's
     * order was already sent; NOT_FOUND when the ship has no such line. IllegalArgumentException for a quantity that
     * is not valid (isValidQuantity); AccessDeniedException when the user is not an enabled Master with a ship.
     */
    @Transactional
    public Change changeQuantity(User master, Long lineId, BigDecimal quantity) {
        checkMaster(master);
        if (!isValidQuantity(quantity)) {
            throw new IllegalArgumentException("A quantity must be above zero, with at most 3 decimals");
        }
        ShipOrderLine line = lineForChange(master, lineId);
        if (line == null) {
            return Change.NOT_FOUND;
        }
        if (line.getOrder().getStatus() != ShipOrderStatus.OPEN) {
            return Change.NOT_OPEN;
        }
        line.setQuantity(quantity);
        return Change.DONE;
    }

    /**
     * The Master removes a line from their ship's OPEN order. The request lines that went into it are unlinked
     * (the requests stay approved). NOT_OPEN, NOT_FOUND and exceptions as changeQuantity.
     */
    @Transactional
    public Change removeLine(User master, Long lineId) {
        ShipOrderLine line = lineForChange(master, lineId);
        if (line == null) {
            return Change.NOT_FOUND;
        }
        if (line.getOrder().getStatus() != ShipOrderStatus.OPEN) {
            return Change.NOT_OPEN;
        }
        for (RequestLine requestLine : requestLineRepository.findAllByShipOrderLineId(line.getId())) {
            requestLine.setShipOrderLine(null);
        }
        line.getOrder().getLines().remove(line);
        lineRepository.delete(line);
        return Change.DONE;
    }

    /** What the Master sends with the order: the port and date are required, the note optional. */
    public record SendDetails(String requestedPort, LocalDate neededBy, String officeNote) {
    }

    /**
     * The Master sends their ship's OPEN order to the office: SENT with the requested port, needed by date and
     * note, sentBy and sentAt. Records "Order sent" and notifies every enabled office user: "New order from MV Demo".
     * From then on the order is read-only, and the next approved request opens a new OPEN order. Returns a Send
     * without an order (nothing changed) when the ship has no OPEN order or it has no lines.
     * IllegalArgumentException for a missing or too long port, a missing date or one before today (UTC), a too long
     * note; AccessDeniedException as changeQuantity.
     */
    @Transactional
    public Send send(User master, SendDetails details) {
        checkMaster(master);
        String port = details.requestedPort() == null ? "" : details.requestedPort().strip();
        if (port.isEmpty() || port.length() > ShipOrder.PORT_MAX_LENGTH) {
            throw new IllegalArgumentException("An order needs a port of at most " + ShipOrder.PORT_MAX_LENGTH
                    + " characters");
        }
        if (details.neededBy() == null || details.neededBy().isBefore(dates.today())) {
            throw new IllegalArgumentException("An order needs a date that is not in the past");
        }
        String note = details.officeNote() == null || details.officeNote().isBlank() ? null
                : details.officeNote().strip();
        if (note != null && note.length() > ShipOrder.NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("A note is at most " + ShipOrder.NOTE_MAX_LENGTH + " characters");
        }

        Ship ship = master.getShip();
        lock(ship);
        ShipOrder order = orderRepository.findByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN).orElse(null);
        if (order == null || order.getLines().isEmpty()) {
            return new Send(null);
        }
        order.setStatus(ShipOrderStatus.SENT);
        order.setRequestedPort(port);
        order.setNeededBy(details.neededBy());
        order.setOfficeNote(note);
        order.setSentBy(master);
        order.setSentAt(Instant.now());
        // What the ship sent, whatever the office changes later.
        for (ShipOrderLine line : order.getLines()) {
            line.setOriginalQuantity(line.getQuantity());
        }

        auditService.record("ORDER_SENT", ship, "Order #" + order.getId() + ", " + order.getLines().size()
                + " lines, " + port + ", needed by " + details.neededBy(), master);
        String text = messageSource.getMessage("order.notification.sent", new Object[] {ship.getName()},
                Locale.ENGLISH);
        for (User officeUser : userRepository.findAllByRoleOrderByFullNameAsc(Role.OFFICE_USER)) {
            if (officeUser.isEnabled()) {
                notificationService.notify(officeUser, NotificationType.ORDER, ship.getName(), text,
                        "/office/orders/" + order.getId());
            }
        }
        return new Send(order);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The office
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * One page of the orders the ships have sent (SENT, CONFIRMED, CLOSED; never OPEN), newest sent first, paginated
     * in the database. shipId and status null mean every ship and every one of those statuses;
     * invoiceDiffer keeps those received whose invoice is not matched yet and has lines that
     * differ. The search is an order number ("12" or "#12"), or the ship's name or IMO number.
     */
    @Transactional(readOnly = true)
    public Page<ShipOrder> findSent(Long shipId, ShipOrderStatus status, boolean invoiceDiffer, ListQuery list) {
        return listQueries.page(ShipOrder.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(status != null && SENT_TO_OFFICE.contains(status) ? cb.equal(root.get("status"), status)
                    : root.get("status").in(SENT_TO_OFFICE));
            if (shipId != null) {
                where.add(cb.equal(root.get("ship").get("id"), shipId));
            }
            if (invoiceDiffer) {
                where.add(cb.equal(root.get("status"), ShipOrderStatus.CLOSED));
                where.add(cb.equal(root.get("invoiceStatus"), InvoiceMatchStatus.TO_MATCH));
                where.add(cb.greaterThan(root.get("invoiceDiffLines"), 0));
            }
            String q = list.getContains();
            if (q != null) {
                Join<ShipOrder, Ship> ship = root.join("ship");
                List<Predicate> any = new ArrayList<>(List.of(
                        ListQueries.contains(cb, ship.get("name"), q),
                        ListQueries.contains(cb, ship.get("imoNumber"), q)));
                Matcher number = ORDER_NUMBER.matcher(list.getQ());
                if (number.matches()) {
                    any.add(cb.equal(root.get("id"), Long.parseLong(number.group(1))));
                }
                where.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return where;
        }, (root, cb) -> List.of(root.get("sentAt")), null);
    }

    /** An order the ship has sent (SENT, CONFIRMED or CLOSED); empty for an OPEN one or no such order. */
    @Transactional(readOnly = true)
    public Optional<ShipOrder> findSent(Long id) {
        return orderRepository.findByIdAndStatusIn(id, SENT_TO_OFFICE);
    }

    /**
     * The ship's next delivery (P55, the Chief cook's Stores): its order whose delivery the office scheduled and that is
     * not received yet (CONFIRMED), the earliest delivery date first; empty when there is none.
     */
    @Transactional(readOnly = true)
    public Optional<ShipOrder> nextDelivery(Long shipId) {
        return orderRepository.findFirstByShipIdAndStatusOrderByDeliveryDateAscIdAsc(shipId, ShipOrderStatus.CONFIRMED);
    }

    /** Orders Pending or Delivery scheduled, every ship: the office's sidebar counter (not exported before). */
    @Transactional(readOnly = true)
    public long countNotReceived() {
        return orderRepository.countByStatusIn(NOT_RECEIVED);
    }

    /** One ship's orders sent and never exported, newest sent first: open items of its Overview tab. */
    @Transactional(readOnly = true)
    public List<ShipOrder> findNotExported(Long shipId) {
        return orderRepository.findAllByShipIdAndStatusInAndExportedAtIsNullOrderBySentAtDescIdDesc(shipId,
                SENT_TO_OFFICE);
    }

    @Transactional(readOnly = true)
    public long countNotExported(Long shipId) {
        return orderRepository.countByShipIdAndStatusInAndExportedAtIsNull(shipId, SENT_TO_OFFICE);
    }

    /** What the office schedules: the port and the delivery date, both required. */
    public record Delivery(String port, LocalDate deliveryDate) {
    }

    /** What scheduleDelivery did. */
    public enum Schedule {
        // The delivery of a Pending order is scheduled: Delivery scheduled.
        SCHEDULED,
        // The port or date of a Delivery scheduled order changed.
        CHANGED,
        // The order is received (CLOSED): nothing changed.
        RECEIVED,
        // No order the ship has sent with this id.
        NOT_FOUND
    }

    /**
     * An office user schedules the delivery of a SENT order, or changes the port or date of a CONFIRMED one (P36;
     * "confirm" until P51): confirmedPort, deliveryDate, confirmedBy and CONFIRMED. Recorded as "Delivery scheduled" or
     * "Delivery changed"; the ship's enabled Master and Chief cook are notified each time, both linking to the order
     * (the Chief cook reads it since P55): "Delivery scheduled: Rotterdam, 20 Nov 2026" or "Delivery changed: ...". RECEIVED (nothing changed) once the Master confirmed receipt;
     * NOT_FOUND for an OPEN order or no such order. The order's row is locked, so two at the same time are saved one
     * after the other. IllegalArgumentException for a missing or too long port or a missing date;
     * AccessDeniedException when the user is not an enabled office user.
     */
    @Transactional
    public Schedule scheduleDelivery(User officeUser, Long orderId, Delivery delivery) {
        checkOffice(officeUser);
        String port = delivery.port() == null ? "" : delivery.port().strip();
        if (port.isEmpty() || port.length() > ShipOrder.PORT_MAX_LENGTH) {
            throw new IllegalArgumentException("A delivery needs a port of at most " + ShipOrder.PORT_MAX_LENGTH
                    + " characters");
        }
        if (delivery.deliveryDate() == null) {
            throw new IllegalArgumentException("A delivery needs a date");
        }
        ShipOrder order = entityManager.find(ShipOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || !SENT_TO_OFFICE.contains(order.getStatus())) {
            return Schedule.NOT_FOUND;
        }
        if (order.getStatus() == ShipOrderStatus.CLOSED) {
            return Schedule.RECEIVED;
        }
        boolean change = order.getStatus() == ShipOrderStatus.CONFIRMED;
        // The lines are read-only from now on; the ship hears about the office's changes before the delivery.
        editService.notifyPending(order);
        order.setConfirmedPort(port);
        order.setDeliveryDate(delivery.deliveryDate());
        order.setConfirmedBy(officeUser);
        order.setStatus(ShipOrderStatus.CONFIRMED);

        Ship ship = order.getShip();
        auditService.record(change ? "ORDER_DELIVERY_CHANGED" : "ORDER_DELIVERY_SCHEDULED", ship,
                "Order #" + order.getId() + ", " + port + ", delivery " + delivery.deliveryDate(), officeUser);
        String date = DateTimeFormatter.ofPattern(Dates.DATE_PATTERN, Locale.ENGLISH).format(delivery.deliveryDate());
        String text = messageSource.getMessage(change ? "order.notification.deliveryChanged"
                : "order.notification.deliveryScheduled", new Object[] {port, date}, Locale.ENGLISH);
        for (Role role : List.of(Role.MASTER, Role.CHIEF_COOK)) {
            for (User user : userRepository.findAllByShipAndRoleAndEnabledTrue(ship, role)) {
                // The Master's order page; the Chief cook's is the same page, read only.
                notificationService.notify(user, NotificationType.ORDER, ship.getName(), text,
                        "/ship/orders/" + order.getId());
            }
        }
        return change ? Schedule.CHANGED : Schedule.SCHEDULED;
    }

    /**
     * The office exports the orders among ids that the ships have sent (other ids are ignored): an Excel 97-2003 workbook
     * (.xls since P40; .xlsx in P39, CSV before), sheet "Orders", one row per order line, the orders newest sent first, each order's lines by
     * category (Provisions, then Personal items) and item name; status as the office sees it (pending,
     * delivery_scheduled (delivery_pending before), received); quantity is what to deliver (0 for a line the office removed), sent_quantity
     * what the ship sent (empty for a line the office added), office_change changed, removed, added or empty,
     * office_note the office's note; received_quantity what arrived on board and received_at when (P51, empty until
     * the Master confirmed receipt); P64, for an order received with a read invoice: invoiced_quantity (the item's
     * unit), invoice_unit, invoice_unit_price and invoice_amount (the invoice's), difference (received minus invoiced),
     * and a row per invoice line not ordered (office_change not_ordered, quantity 0, the invoice's unit); order_id and
     * the quantities are numbers, sent_at and received_at a date and
     * time (UTC), needed_by and delivery_date dates. Sets exportedAt now on each of them (an order can be exported
     * again) and records "Orders exported". Empty when none of the ids is such an order.
     * AccessDeniedException when the user is not an enabled office user.
     */
    @Transactional
    public Optional<XlsExport.File> export(User officeUser, Collection<Long> ids) {
        checkOffice(officeUser);
        List<ShipOrder> orders = ids == null || ids.isEmpty() ? List.of()
                : orderRepository.findAllByIdInAndStatusInOrderBySentAtDescIdDesc(ids, SENT_TO_OFFICE);
        if (orders.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        XlsExport sheet = XlsExport.sheet("Orders", EXPORT_HEADER);
        int lineCount = 0;
        for (ShipOrder order : orders) {
            InvoiceComparison comparison = comparison(order);
            for (ShipOrderLine line : byCategoryAndName(order.getLines())) {
                InvoiceComparison.Row row = comparison == null || line.isRemoved() ? null : comparison.row("o" + line.getId());
                sheet.row(Arrays.asList(
                        order.getShip().getImoNumber(),
                        order.getShip().getName(),
                        order.getId(),
                        exportStatus(order.getStatus()),
                        order.getSentAt().truncatedTo(ChronoUnit.SECONDS),
                        order.getRequestedPort(),
                        order.getNeededBy(),
                        order.getConfirmedPort(),
                        order.getDeliveryDate(),
                        line.getItem().getCategory().fileValue(),
                        line.getItem().getName(),
                        line.getItem().getUnit(),
                        line.getDeliveredQuantity(),
                        line.getSentQuantity(),
                        officeChange(line),
                        line.getChangeNote(),
                        line.getReceivedQuantity(),
                        order.getReceivedAt(),
                        row == null ? null : row.getInvoiced(),
                        row == null || !row.isOnInvoice() ? null : row.getInvoiceLines().getFirst().getUnit(),
                        row == null ? null : row.getUnitPrice(),
                        row == null ? null : row.getAmount(),
                        difference(row)));
                lineCount++;
            }
            // The invoice's lines linked to no order line (not ordered), in the invoice's unit.
            if (comparison != null) {
                for (InvoiceComparison.Row row : comparison.getRows()) {
                    if (row.getOrderLine() != null) {
                        continue;
                    }
                    DeliveryInvoiceLine invoiceLine = row.getInvoiceLines().getFirst();
                    sheet.row(Arrays.asList(
                            order.getShip().getImoNumber(), order.getShip().getName(), order.getId(),
                            exportStatus(order.getStatus()), order.getSentAt().truncatedTo(ChronoUnit.SECONDS),
                            order.getRequestedPort(), order.getNeededBy(), order.getConfirmedPort(),
                            order.getDeliveryDate(), null, row.getName(), row.getUnit(), BigDecimal.ZERO, null,
                            "not_ordered", null, null, order.getReceivedAt(), row.getInvoiced(), invoiceLine.getUnit(),
                            row.getUnitPrice(), row.getAmount(), difference(row)));
                    lineCount++;
                }
            }
            order.setExportedAt(now);
        }

        // One audit entry per export, with the ship when all orders are from one ship.
        List<Ship> ships = orders.stream().map(ShipOrder::getShip).distinct().toList();
        auditService.record("ORDERS_EXPORTED", ships.size() == 1 ? ships.getFirst() : null,
                orders.size() + (orders.size() == 1 ? " order, " : " orders, ") + lineCount
                        + (lineCount == 1 ? " line, ids " : " lines, ids ")
                        + orders.stream().map(order -> order.getId().toString()).toList(), officeUser);
        return Optional.of(sheet.file("ship-orders-" + LocalDate.ofInstant(now, ZoneOffset.UTC)));
    }

    // The comparison of a received order with a read invoice (no quotes, no decisions: the numbers only), or null.
    private InvoiceComparison comparison(ShipOrder order) {
        if (order.getStatus() != ShipOrderStatus.CLOSED) {
            return null;
        }
        DeliveryInvoice invoice = invoiceRepository.findByOrderId(order.getId()).orElse(null);
        if (invoice == null) {
            return null;
        }
        List<DeliveryInvoiceLine> lines = invoiceLineRepository.findAllByInvoiceIdOrderByPageAscIdAsc(invoice.getId());
        return InvoiceComparison.applies(order, invoice, lines)
                ? InvoiceComparison.of(order, invoice, lines, java.util.Map.of(), null, java.util.Map.of()) : null;
    }

    // The difference column: received minus invoiced (nothing invoiced: 0), in the row's unit; empty without an
    // invoice or when its quantity was not read.
    private static BigDecimal difference(InvoiceComparison.Row row) {
        if (row == null || (row.isOnInvoice() && row.getInvoiced() == null)) {
            return null;
        }
        BigDecimal received = row.getReceived() == null ? BigDecimal.ZERO : row.getReceived();
        BigDecimal invoiced = row.getInvoiced() == null ? BigDecimal.ZERO : row.getInvoiced();
        return received.subtract(invoiced);
    }

    /** The status column of the export: pending, delivery_scheduled (delivery_pending before) and received. */
    static String exportStatus(ShipOrderStatus status) {
        return switch (status) {
            case SENT -> "pending";
            case CONFIRMED -> "delivery_scheduled";
            case CLOSED -> "received";
            case OPEN -> "open";
        };
    }

    /** The office_change column: what the office did to the line, or null. */
    static String officeChange(ShipOrderLine line) {
        if (line.isRemoved()) {
            return "removed";
        }
        if (line.isAddedByOffice()) {
            return "added";
        }
        return line.isChangedByOffice() ? "changed" : null;
    }

    /** An order's lines by category (Provisions, then Personal items), then item name: the office's order. */
    public static List<ShipOrderLine> byCategoryAndName(List<ShipOrderLine> lines) {
        return lines.stream()
                .sorted(Comparator.comparing((ShipOrderLine line) -> line.getItem().getCategory())
                        .thenComparing(line -> line.getItem().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ShipOrderLine::getId))
                .toList();
    }

    private static void checkOffice(User officeUser) {
        if (officeUser == null || officeUser.getRole() != Role.OFFICE_USER || !officeUser.isEnabled()) {
            throw new AccessDeniedException("Only the office schedules the delivery of an order or exports it");
        }
    }

    /** What changeQuantity or removeLine did. */
    public enum Change {
        DONE,
        // The line's order was already sent: nothing changed.
        NOT_OPEN,
        // No such line on the Master's ship.
        NOT_FOUND
    }

    /** What send did: the order sent, or null when there was nothing to send (no OPEN order, or no lines). */
    public record Send(ShipOrder order) {
        public boolean sent() {
            return order != null;
        }
    }

    // The line, after locking the Master's ship; null when the ship has no such line.
    /** A line of one of the ship's orders, read only (its item's quantity rule before a change); empty if none. */
    @Transactional(readOnly = true)
    public java.util.Optional<ShipOrderLine> findLine(Long shipId, Long lineId) {
        return lineRepository.findByIdAndOrderShipId(lineId, shipId);
    }

    private ShipOrderLine lineForChange(User master, Long lineId) {
        checkMaster(master);
        lock(master.getShip());
        return lineRepository.findByIdAndOrderShipId(lineId, master.getShip().getId()).orElse(null);
    }

    private static void checkMaster(User master) {
        if (master == null || master.getRole() != Role.MASTER || !master.isEnabled() || master.getShip() == null) {
            throw new AccessDeniedException("Only the Master of the ship changes or sends its order");
        }
    }

    private void lock(Ship ship) {
        entityManager.lock(entityManager.contains(ship) ? ship : entityManager.find(Ship.class, ship.getId()),
                LockModeType.PESSIMISTIC_WRITE);
    }

    private ShipOrder open(Ship ship) {
        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(ShipOrderStatus.OPEN);
        order.setCreatedAt(Instant.now());
        return orderRepository.save(order);
    }
}
