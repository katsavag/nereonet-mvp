package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
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

// Ship orders, for any feature (P34). RequestService.approve adds each approved request to the ship's OPEN order; the
// Master edits its lines and sends it to the office (P35, shiporder). Every change locks the ship's row first, so an
// approval, an edit and a send never interleave: an approval made while the order is sent goes into a new OPEN order.
@Service
public class ShipOrderService {

    // ShipOrderLine.quantity is numeric(12, 3).
    static final int QUANTITY_SCALE = 3;
    static final int QUANTITY_INTEGER_DIGITS = 9;

    private final ShipOrderRepository orderRepository;
    private final ShipOrderLineRepository lineRepository;
    private final RequestLineRepository requestLineRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final Dates dates;
    private final EntityManager entityManager;

    public ShipOrderService(ShipOrderRepository orderRepository, ShipOrderLineRepository lineRepository,
                            RequestLineRepository requestLineRepository, UserRepository userRepository,
                            AuditService auditService, NotificationService notificationService,
                            MessageSource messageSource, Dates dates, EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.requestLineRepository = requestLineRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
        this.dates = dates;
        this.entityManager = entityManager;
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
                // The office has no order page yet, so the notification links nowhere.
                notificationService.notify(officeUser, NotificationType.ORDER, ship.getName(), text, null);
            }
        }
        return new Send(order);
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
