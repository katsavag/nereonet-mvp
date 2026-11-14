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

// Ship orders, for any feature (P34). RequestService.approve adds each approved request to the ship's OPEN order; the
// Master edits its lines and sends it to the office (P35, shiporder). Every change of the ship locks the ship's row
// first, so an approval, an edit and a send never interleave: an approval made while the order is sent goes into a
// new OPEN order. The office (P36, officeorder) lists the sent orders, confirms the port and delivery date, and
// exports them as CSV; it never changes lines or quantities.
@Service
public class ShipOrderService {

    // ShipOrderLine.quantity is numeric(12, 3).
    static final int QUANTITY_SCALE = 3;
    static final int QUANTITY_INTEGER_DIGITS = 9;

    /** The orders the office sees (P36): those a Master has sent. Never OPEN ones. */
    public static final List<ShipOrderStatus> SENT_TO_OFFICE = List.of(ShipOrderStatus.SENT,
            ShipOrderStatus.CONFIRMED, ShipOrderStatus.CLOSED);

    /** The columns of the office's export (P36; an Excel workbook since P39, .xls since P40, the same columns), one row per order
     *  line. A file format: never translated. */
    static final List<String> EXPORT_HEADER = List.of("imo_number", "ship_name", "order_id", "status", "sent_at",
            "requested_port", "needed_by", "confirmed_port", "delivery_date", "category", "item_name", "unit",
            "quantity");

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

    public ShipOrderService(ShipOrderRepository orderRepository, ShipOrderLineRepository lineRepository,
                            RequestLineRepository requestLineRepository, UserRepository userRepository,
                            AuditService auditService, NotificationService notificationService,
                            MessageSource messageSource, Dates dates, EntityManager entityManager,
                            ListQueries listQueries) {
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
    }

    /**
     * Adds an APPROVED request to its ship's OPEN order, creating the order if there is none: each line's quantity is
     * added to the order line of the same item, or a new order line is made, and the request line is linked to it.
     * Every line must have an item (RequestService.approve creates the new ones first). The ship's row is locked
     * first, so two approvals at the same time never make two OPEN orders, or two lines for one item.
     */
    @Transactional
    public ShipOrder addApprovedRequest(ItemRequest request) {
        if (request.getStatus() != RequestStatus.APPROVED) {
            throw new IllegalArgumentException("Only an approved request goes into the ship order");
        }
        Ship ship = request.getShip();
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
     * The Master changes the quantity of a line of their ship's OPEN order (P35). Nobody is notified and nothing is
     * recorded in the audit log: the order is still being put together. NOT_OPEN (nothing changed) when the line's
     * order was already sent; NOT_FOUND when the ship has no such line. IllegalArgumentException for a quantity that
     * is not valid (isValidQuantity); AccessDeniedException when the user is not an enabled Master with a ship.
     */
    @Transactional
    public Change changeQuantity(User master, Long lineId, BigDecimal quantity) {
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
     * The Master removes a line from their ship's OPEN order (P35). The request lines that went into it are unlinked
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
     * The Master sends their ship's OPEN order to the office (P35): SENT with the requested port, needed by date and
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
    // The office (P36)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * One page of the orders the ships have sent (SENT, CONFIRMED, CLOSED; never OPEN), newest sent first, paginated
     * in the database. shipId and status null mean every ship and every one of those statuses; notExported keeps
     * those never exported. The search is an order number ("12" or "#12"), or the ship's name or IMO number.
     */
    @Transactional(readOnly = true)
    public Page<ShipOrder> findSent(Long shipId, ShipOrderStatus status, boolean notExported, ListQuery list) {
        return listQueries.page(ShipOrder.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(status != null && SENT_TO_OFFICE.contains(status) ? cb.equal(root.get("status"), status)
                    : root.get("status").in(SENT_TO_OFFICE));
            if (shipId != null) {
                where.add(cb.equal(root.get("ship").get("id"), shipId));
            }
            if (notExported) {
                where.add(cb.isNull(root.get("exportedAt")));
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

    /** Orders sent and never exported, every ship: the office's sidebar counter. */
    @Transactional(readOnly = true)
    public long countNotExported() {
        return orderRepository.countByStatusInAndExportedAtIsNull(SENT_TO_OFFICE);
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

    /** What the office confirms: the port and the delivery date, both required. */
    public record Confirmation(String confirmedPort, LocalDate deliveryDate) {
    }

    /** What confirm did. */
    public enum Confirm {
        DONE,
        // The order is CLOSED: nothing changed.
        CLOSED,
        // No order the ship has sent with this id.
        NOT_FOUND
    }

    /**
     * An office user confirms a SENT order, or corrects the confirmation of a CONFIRMED one (P36): confirmedPort,
     * deliveryDate, confirmedBy and CONFIRMED. Records "Order confirmed" and notifies the ship's enabled Master and
     * Chief cook each time: "Order confirmed: Rotterdam, 20 Nov 2026". CLOSED (nothing changed) once the order is
     * closed; NOT_FOUND for an OPEN order or no such order. The order's row is locked, so two confirmations at the
     * same time are saved one after the other. IllegalArgumentException for a missing or too long port or a missing
     * date; AccessDeniedException when the user is not an enabled office user.
     */
    @Transactional
    public Confirm confirm(User officeUser, Long orderId, Confirmation confirmation) {
        checkOffice(officeUser);
        String port = confirmation.confirmedPort() == null ? "" : confirmation.confirmedPort().strip();
        if (port.isEmpty() || port.length() > ShipOrder.PORT_MAX_LENGTH) {
            throw new IllegalArgumentException("A confirmation needs a port of at most " + ShipOrder.PORT_MAX_LENGTH
                    + " characters");
        }
        if (confirmation.deliveryDate() == null) {
            throw new IllegalArgumentException("A confirmation needs a delivery date");
        }
        ShipOrder order = entityManager.find(ShipOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || !SENT_TO_OFFICE.contains(order.getStatus())) {
            return Confirm.NOT_FOUND;
        }
        if (order.getStatus() == ShipOrderStatus.CLOSED) {
            return Confirm.CLOSED;
        }
        boolean correction = order.getStatus() == ShipOrderStatus.CONFIRMED;
        order.setConfirmedPort(port);
        order.setDeliveryDate(confirmation.deliveryDate());
        order.setConfirmedBy(officeUser);
        order.setStatus(ShipOrderStatus.CONFIRMED);

        Ship ship = order.getShip();
        auditService.record("ORDER_CONFIRMED", ship, "Order #" + order.getId() + ", " + port + ", delivery "
                + confirmation.deliveryDate() + (correction ? ", corrected" : ""), officeUser);
        String date = DateTimeFormatter.ofPattern(Dates.DATE_PATTERN, Locale.ENGLISH).format(confirmation.deliveryDate());
        String text = messageSource.getMessage("order.notification.confirmed", new Object[] {port, date},
                Locale.ENGLISH);
        for (Role role : List.of(Role.MASTER, Role.CHIEF_COOK)) {
            for (User user : userRepository.findAllByShipAndRoleAndEnabledTrue(ship, role)) {
                // Only the Master has the order pages (/ship/orders).
                notificationService.notify(user, NotificationType.ORDER, ship.getName(), text,
                        role == Role.MASTER ? "/ship/orders/" + order.getId() : null);
            }
        }
        return Confirm.DONE;
    }

    /**
     * The office exports the orders among ids that the ships have sent (other ids are ignored): an Excel 97-2003 workbook
     * (.xls since P40; .xlsx in P39, CSV before), sheet "Orders", one row per order line, the orders newest sent first, each order's lines by
     * category (Provisions, then Personal items) and item name; status as the office sees it (P49: pending, confirmed,
     * closed); order_id and quantity are numbers, sent_at a date and
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
            for (ShipOrderLine line : byCategoryAndName(order.getLines())) {
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
                        line.getQuantity()));
                lineCount++;
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

    /** The status column of the export: the office's word, so SENT (waiting for the office) is "pending" (P49). */
    static String exportStatus(ShipOrderStatus status) {
        return status == ShipOrderStatus.SENT ? "pending" : status.name().toLowerCase(Locale.ROOT);
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
            throw new AccessDeniedException("Only the office confirms or exports an order");
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
