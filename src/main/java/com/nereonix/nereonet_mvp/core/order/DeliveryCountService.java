package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * The Chief cook counts a delivery, the Master approves it (P61). While an order is CONFIRMED (delivery scheduled) the
 * vessel's enabled Chief cook counts what arrived of each line (saveDraft, as often as he likes), then sends the count
 * to the Master (send): the Master is notified and Confirm receipt opens with the cook's numbers. The Master either
 * confirms receipt (OrderReceiptService.confirmReceipt, which marks the count APPROVED) or sends it back with a note
 * (sendBack): a draft again, the cook's numbers kept. Nothing here touches the store; the receipt still does (P56).
 * The count only helps: the Master can confirm receipt without one. Every change locks the order's row, as the
 * receipt does. The order keeps its status (CONFIRMED) throughout; the office may still change the delivery's port and
 * date, which keeps the count.
 */
@Service
public class DeliveryCountService {

    /** What a change of the count did. */
    public enum Result {
        DONE,
        // Not CONFIRMED: the delivery is not scheduled yet. Nothing changed.
        NOT_CONFIRMED,
        // CLOSED: the receipt was confirmed. Nothing changed.
        RECEIVED,
        // saveDraft, send: the count is with the Master already. sendBack: no count is waiting for the Master.
        WRONG_STATE
    }

    /** What the cook counted of one line: the quantity (null: not counted yet; 0: nothing arrived) and a note. */
    public record LineCount(BigDecimal counted, String note) {
    }

    /** The count: per line by its id, and the cook's note to the Master (optional). */
    public record Count(Map<Long, LineCount> lines, String note) {
    }

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final EntityManager entityManager;

    public DeliveryCountService(UserRepository userRepository, AuditService auditService,
                                NotificationService notificationService, MessageSource messageSource,
                                EntityManager entityManager) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
        this.entityManager = entityManager;
    }

    /**
     * The cook saves the count as a draft: every line not removed gets the quantity given (null: not counted yet) and
     * note; the order the cook's note. DRAFT, or RETURNED kept (the Master's note stays on top until he sends again).
     * NOT_CONFIRMED, RECEIVED, WRONG_STATE (sent already): nothing changed. IllegalArgumentException for a line of
     * another order, a quantity that is not a received quantity (zero or more), or a note too long; 404 for an order
     * of another ship; AccessDeniedException for anyone but the vessel's enabled Chief cook.
     */
    @Transactional
    public Result saveDraft(User cook, Long orderId, Count count) {
        ShipOrder order = lockForCook(cook, orderId);
        Result refused = refuseCount(order);
        if (refused != null) {
            return refused;
        }
        apply(order, count);
        if (order.getDeliveryCountStatus() != DeliveryCountStatus.RETURNED) {
            order.setDeliveryCount(DeliveryCountStatus.DRAFT);
        }
        order.setCountedBy(cook);
        order.setCountUpdatedAt(Instant.now());
        return Result.DONE;
    }

    /**
     * The cook sends the count to the Master: as saveDraft, and every line must be counted. SENT_TO_MASTER; recorded
     * as "Delivery counted"; the ship's enabled Masters get "Order 12: delivery counted by Demo Cook, waiting for your
     * approval", linking to the receipt page. Refusals as saveDraft; IllegalArgumentException also for a line without a
     * quantity.
     */
    @Transactional
    public Result send(User cook, Long orderId, Count count) {
        ShipOrder order = lockForCook(cook, orderId);
        Result refused = refuseCount(order);
        if (refused != null) {
            return refused;
        }
        apply(order, count);
        List<ShipOrderLine> lines = order.getLines().stream().filter(line -> !line.isRemoved()).toList();
        if (lines.stream().anyMatch(line -> line.getCountedQuantity() == null)) {
            throw new IllegalArgumentException("Every line needs a counted quantity before it is sent");
        }
        Instant now = Instant.now();
        order.setDeliveryCount(DeliveryCountStatus.SENT_TO_MASTER);
        order.setCountedBy(cook);
        order.setCountUpdatedAt(now);
        order.setCountSentAt(now);
        long differ = lines.stream().filter(line -> line.getCountedQuantity().compareTo(line.getQuantity()) != 0).count();
        Ship ship = order.getShip();
        auditService.record("ORDER_DELIVERY_COUNTED", ship, "Order #" + order.getId() + ", " + lines.size()
                + (lines.size() == 1 ? " line" : " lines") + " counted, " + differ + " not as ordered", cook);
        String text = messageSource.getMessage("order.notification.counted",
                new Object[] {order.getId(), cook.getFullName()}, Locale.ENGLISH);
        for (User master : userRepository.findAllByShipAndRoleAndEnabledTrue(ship, Role.MASTER)) {
            notificationService.notify(master, NotificationType.ORDER, ship.getName(), text,
                    "/ship/orders/" + order.getId() + "/receipt");
        }
        return Result.DONE;
    }

    /**
     * The Master sends the count back to the cook with a note (required, at most COUNT_NOTE_MAX_LENGTH): RETURNED, the
     * cook's numbers kept. Recorded as "Delivery count sent back"; the cook who counted (or, when he is gone, the
     * ship's enabled Chief cooks) gets "Order 12: the Master sent the delivery count back: Recount the frozen meat",
     * linking to the count. NOT_CONFIRMED, RECEIVED, WRONG_STATE (no count waiting): nothing changed.
     * IllegalArgumentException for a missing or too long note; 404 for another ship's order; AccessDeniedException for
     * anyone but the vessel's enabled Master.
     */
    @Transactional
    public Result sendBack(User master, Long orderId, String note) {
        if (master == null || master.getRole() != Role.MASTER || !master.isEnabled() || master.getShip() == null) {
            throw new AccessDeniedException("Only the Master of the ship sends a delivery count back");
        }
        String text = note == null ? "" : note.strip();
        if (text.isEmpty() || text.length() > ShipOrder.COUNT_NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("Sending a count back needs a note of at most "
                    + ShipOrder.COUNT_NOTE_MAX_LENGTH + " characters");
        }
        ShipOrder order = lock(master, orderId);
        if (order.getStatus() == ShipOrderStatus.CLOSED) {
            return Result.RECEIVED;
        }
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        if (order.getDeliveryCountStatus() != DeliveryCountStatus.SENT_TO_MASTER) {
            return Result.WRONG_STATE;
        }
        order.setDeliveryCount(DeliveryCountStatus.RETURNED);
        order.setCountReturnNote(text);
        order.setCountReturnedAt(Instant.now());
        Ship ship = order.getShip();
        auditService.record("ORDER_DELIVERY_COUNT_RETURNED", ship, "Order #" + order.getId() + ": " + text, master);
        String message = messageSource.getMessage("order.notification.countReturned",
                new Object[] {order.getId(), text}, Locale.ENGLISH);
        User counted = order.getCountedBy();
        List<User> cooks = counted != null && counted.isEnabled() && counted.getRole() == Role.CHIEF_COOK
                && counted.getShip() != null && counted.getShip().getId().equals(ship.getId())
                ? List.of(counted) : userRepository.findAllByShipAndRoleAndEnabledTrue(ship, Role.CHIEF_COOK);
        for (User cook : cooks) {
            notificationService.notify(cook, NotificationType.ORDER, ship.getName(), message,
                    "/ship/orders/" + order.getId() + "/count");
        }
        return Result.DONE;
    }

    // NOT_CONFIRMED, RECEIVED or WRONG_STATE when the cook cannot change the count now; null when he can.
    private static Result refuseCount(ShipOrder order) {
        if (order.getStatus() == ShipOrderStatus.CLOSED) {
            return Result.RECEIVED;
        }
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        return order.getDeliveryCountStatus().editable() ? null : Result.WRONG_STATE;
    }

    // The count onto the order's lines (not the removed ones), checked first: nothing is set unless all is valid.
    private static void apply(ShipOrder order, Count count) {
        String note = blankToNull(count.note());
        if (note != null && note.length() > ShipOrder.COUNT_NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("A note to the Master is at most " + ShipOrder.COUNT_NOTE_MAX_LENGTH
                    + " characters");
        }
        for (Long lineId : count.lines().keySet()) {
            if (order.getLines().stream().noneMatch(line -> line.getId().equals(lineId) && !line.isRemoved())) {
                throw new IllegalArgumentException("Not a line of this order: " + lineId);
            }
        }
        for (LineCount lineCount : count.lines().values()) {
            if (lineCount.counted() != null && !OrderReceiptService.isValidReceivedQuantity(lineCount.counted())) {
                throw new IllegalArgumentException("A counted quantity is zero or more");
            }
            String lineNote = blankToNull(lineCount.note());
            if (lineNote != null && lineNote.length() > ShipOrderLine.NOTE_MAX_LENGTH) {
                throw new IllegalArgumentException("A note is at most " + ShipOrderLine.NOTE_MAX_LENGTH + " characters");
            }
        }
        for (ShipOrderLine line : order.getLines()) {
            if (line.isRemoved()) {
                continue;
            }
            LineCount lineCount = count.lines().get(line.getId());
            line.setCountedQuantity(lineCount == null ? null : lineCount.counted());
            line.setCountNote(lineCount == null ? null : blankToNull(lineCount.note()));
        }
        order.setCountNote(note);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private ShipOrder lockForCook(User cook, Long orderId) {
        if (cook == null || cook.getRole() != Role.CHIEF_COOK || !cook.isEnabled() || cook.getShip() == null) {
            throw new AccessDeniedException("Only the Chief cook of the ship counts its deliveries");
        }
        return lock(cook, orderId);
    }

    // The order of the user's ship, its row locked; 404 for another ship's or an OPEN one.
    private ShipOrder lock(User user, Long orderId) {
        ShipOrder order = entityManager.find(ShipOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null || !order.getShip().getId().equals(user.getShip().getId())
                || order.getStatus() == ShipOrderStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return order;
    }
}
