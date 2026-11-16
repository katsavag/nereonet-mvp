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
 * The Chief cook counts a delivery, the Master approves it (row by row against the invoice, CountSheet). While an order is CONFIRMED (delivery scheduled) the
 * vessel's enabled Chief cook counts what arrived of each line (saveRows, as often as he likes), then sends the count
 * to the Master (send, once every row is counted): the Master is notified and Confirm receipt opens with the cook's numbers. The Master either
 * confirms receipt (OrderReceiptService.confirmReceipt, which marks the count APPROVED) or sends it back with a note
 * (sendBack): a draft again, the cook's numbers kept. Nothing here touches the store; the receipt still does.
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
        // saveRows, send: the count is with the Master already. sendBack: no count is waiting for the Master.
        WRONG_STATE,
        // send: rows are still to do (CountSheet.getToDoTotal). Nothing changed.
        NOT_FINISHED
    }

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final EntityManager entityManager;
    private final InvoiceMatchService matchService;

    public DeliveryCountService(UserRepository userRepository, AuditService auditService,
                                NotificationService notificationService, MessageSource messageSource,
                                EntityManager entityManager, InvoiceMatchService matchService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
        this.entityManager = entityManager;
        this.matchService = matchService;
    }

    /**
     * Before the count (the cook) or the approval (the Master) is shown: the invoice lines not matched yet are
     * matched (InvoiceMatchService.ensureMatched), the order's row locked. Nothing unless the order is CONFIRMED.
     * 404 for another ship's order; AccessDeniedException for anyone but the vessel's enabled Chief cook or Master.
     */
    @Transactional
    public void prepare(User user, Long orderId) {
        if (user == null || !user.isEnabled() || user.getShip() == null
                || !(user.getRole() == Role.CHIEF_COOK || user.getRole() == Role.MASTER)) {
            throw new AccessDeniedException("Only the Chief cook and the Master see the delivery count");
        }
        ShipOrder order = lock(user, orderId);
        if (order.getStatus() == ShipOrderStatus.CONFIRMED) {
            matchService.ensureMatched(order);
        }
    }

    /** What the cook says of one row: what arrived in the item's unit (null: not counted) and an optional note. */
    public record Counted(BigDecimal quantity, String note) {
    }

    /**
     * The cook saves the count, all its rows at once (CountSheet.Row key: "o12" an order line, "i34" an invoice line
     * matched to an item not on the order): per row what arrived in the item's unit (0: nothing; null: not counted)
     * and an optional note. Rows not in the map stay as they are. DRAFT (RETURNED kept, the Master's note stays on
     * top until he sends again).
     * NOT_CONFIRMED, RECEIVED, WRONG_STATE (sent already): nothing changed. IllegalArgumentException, before anything
     * is changed, for a row of another order, a quantity that is not a received quantity, or a note too long; 404
     * for an order of another ship; AccessDeniedException for anyone but the vessel's enabled Chief cook.
     */
    @Transactional
    public Result saveRows(User cook, Long orderId, Map<String, Counted> counts) {
        ShipOrder order = lockForCook(cook, orderId);
        Result refused = refuseCount(order);
        if (refused != null) {
            return refused;
        }
        CountSheet sheet = matchService.sheet(order);
        for (Map.Entry<String, Counted> entry : counts.entrySet()) {
            Counted counted = entry.getValue();
            if (sheet.row(entry.getKey()) == null) {
                throw new IllegalArgumentException("Not a row of this count: " + entry.getKey());
            }
            if (counted.quantity() != null && !OrderReceiptService.isValidReceivedQuantity(counted.quantity())) {
                throw new IllegalArgumentException("A counted quantity is zero or more");
            }
            String text = blankToNull(counted.note());
            if (text != null && text.length() > ShipOrderLine.NOTE_MAX_LENGTH) {
                throw new IllegalArgumentException("A note is at most " + ShipOrderLine.NOTE_MAX_LENGTH + " characters");
            }
        }
        counts.forEach((key, counted) -> {
            CountSheet.Row row = sheet.row(key);
            String text = blankToNull(counted.note());
            if (row.getOrderLine() != null) {
                row.getOrderLine().setCountedQuantity(counted.quantity());
                row.getOrderLine().setCountNote(text);
            } else {
                row.getExtra().setCountedQuantity(counted.quantity());
                row.getExtra().setCountNote(text);
            }
        });
        touch(order, cook);
        return Result.DONE;
    }

    /**
     * The cook corrects what the photo read of a row's invoice line (CountSheet.Row.getInvoiceLine): what the paper
     * invoice says, in the row's unit. The line's quantity is replaced and what the photo read is kept
     * (DeliveryInvoiceLine.correctQuantity, as the office's correction); the count of the row stays, so its difference
     * is against the new number. Recorded as "Invoice quantity corrected on board" with the old and the new value.
     * Refusals as saveRows; IllegalArgumentException for a row without one invoice line to correct or a quantity that
     * is not one.
     */
    @Transactional
    public Result correctInvoice(User cook, Long orderId, String key, BigDecimal rowQuantity) {
        ShipOrder order = lockForCook(cook, orderId);
        Result refused = refuseCount(order);
        if (refused != null) {
            return refused;
        }
        CountSheet.Row row = matchService.sheet(order).row(key == null ? "" : key);
        DeliveryInvoiceLine line = row == null ? null : row.getInvoiceLine();
        if (line == null) {
            throw new IllegalArgumentException("Not a row with an invoice line to correct: " + key);
        }
        if (!OrderReceiptService.isValidReceivedQuantity(rowQuantity)) {
            throw new IllegalArgumentException("An invoice quantity is zero or more");
        }
        String before = line.getQuantity() == null ? "not read" : line.getQuantity().stripTrailingZeros().toPlainString();
        BigDecimal quantity = row.toInvoice(rowQuantity);
        if (line.correctQuantity(quantity)) {
            auditService.record("ORDER_INVOICE_QUANTITY_CORRECTED", order.getShip(), "Order #" + order.getId()
                    + ", page " + line.getPage() + (line.getLineNumber() == null ? "" : " line " + line.getLineNumber())
                    + " \"" + line.getDescription() + "\": quantity " + before + " -> "
                    + quantity.stripTrailingZeros().toPlainString(), cook);
        }
        return Result.DONE;
    }

    /**
     * Choose the product: the cook says what an invoice line nothing was found for is: a line of the order not
     * removed (orderLineId), a vessel item not on the order (itemId), or neither ("Not ordered": never stocked, it goes
     * to the office). The product chosen is learned for the supplier. Refusals as saveRows; IllegalArgumentException for
     * a line of another order's invoice or a product it cannot be.
     */
    @Transactional
    public Result choose(User cook, Long orderId, Long invoiceLineId, Long orderLineId, Long itemId) {
        ShipOrder order = lockForCook(cook, orderId);
        Result refused = refuseCount(order);
        if (refused != null) {
            return refused;
        }
        matchService.choose(order, invoiceLineId, orderLineId, itemId, cook);
        touch(order, cook);
        return Result.DONE;
    }

    /**
     * The cook sends the count to the Master with an optional note (at most COUNT_NOTE_MAX_LENGTH). Every row must
     * be counted and every invoice line chosen (what did not arrive is counted as 0): NOT_FINISHED while
     * CountSheet.getToDoTotal is above zero, nothing changed. SENT_TO_MASTER; recorded as "Delivery counted"; the
     * ship's enabled Masters get "Order 12: delivery counted by Demo Cook, waiting for your approval", linking to the
     * approval. Other refusals as saveRows.
     */
    @Transactional
    public Result send(User cook, Long orderId, String note) {
        ShipOrder order = lockForCook(cook, orderId);
        Result refused = refuseCount(order);
        if (refused != null) {
            return refused;
        }
        String text = blankToNull(note);
        if (text != null && text.length() > ShipOrder.COUNT_NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("A note to the Master is at most " + ShipOrder.COUNT_NOTE_MAX_LENGTH
                    + " characters");
        }
        CountSheet sheet = matchService.sheet(order);
        if (sheet.getToDoTotal() > 0) {
            return Result.NOT_FINISHED;
        }
        Instant now = Instant.now();
        order.setCountNote(text);
        order.setDeliveryCount(DeliveryCountStatus.SENT_TO_MASTER);
        order.setCountedBy(cook);
        order.setCountUpdatedAt(now);
        order.setCountSentAt(now);
        Ship ship = order.getShip();
        auditService.record("ORDER_DELIVERY_COUNTED", ship, "Order #" + order.getId() + ", "
                + sheet.getCountedTotal() + " of " + sheet.getTotal() + " counted, " + sheet.getDifferentTotal()
                + " different", cook);
        String message = messageSource.getMessage("order.notification.counted",
                new Object[] {order.getId(), cook.getFullName()}, Locale.ENGLISH);
        for (User master : userRepository.findAllByShipAndRoleAndEnabledTrue(ship, Role.MASTER)) {
            notificationService.notify(master, NotificationType.ORDER, ship.getName(), message,
                    "/ship/orders/" + order.getId() + "/receipt");
        }
        return Result.DONE;
    }

    /**
     * The Master changes one row of a count waiting for approval, in the item's unit ("Changed from 23"); the
     * cook's number is kept. The same number as counted clears the change. NOT_CONFIRMED, RECEIVED, WRONG_STATE (no
     * count waiting): nothing changed. IllegalArgumentException for a row of another order or a quantity that is not a
     * received quantity; 404 for another ship's order; AccessDeniedException for anyone but the vessel's enabled
     * Master.
     */
    @Transactional
    public Result change(User master, Long orderId, String key, BigDecimal quantity) {
        checkMaster(master);
        ShipOrder order = lock(master, orderId);
        Result refused = refuseApproval(order);
        if (refused != null) {
            return refused;
        }
        if (!OrderReceiptService.isValidReceivedQuantity(quantity)) {
            throw new IllegalArgumentException("A quantity is zero or more");
        }
        CountSheet.Row row = matchService.sheet(order).row(key == null ? "" : key);
        if (row == null) {
            throw new IllegalArgumentException("Not a row of this count: " + key);
        }
        BigDecimal counted = row.getCountedItem();
        BigDecimal approved = counted != null && counted.compareTo(quantity) == 0 ? null : quantity;
        if (row.getOrderLine() != null) {
            row.getOrderLine().setApprovedQuantity(approved);
        } else {
            row.getExtra().setApprovedQuantity(approved);
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
        checkMaster(master);
        String text = note == null ? "" : note.strip();
        if (text.isEmpty() || text.length() > ShipOrder.COUNT_NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("Sending a count back needs a note of at most "
                    + ShipOrder.COUNT_NOTE_MAX_LENGTH + " characters");
        }
        ShipOrder order = lock(master, orderId);
        Result refused = refuseApproval(order);
        if (refused != null) {
            return refused;
        }
        // The Master's changes go; the cook's numbers stay.
        matchService.sheet(order).getRows().forEach(row -> {
            if (row.getOrderLine() != null) {
                row.getOrderLine().setApprovedQuantity(null);
            } else {
                row.getExtra().setApprovedQuantity(null);
            }
        });
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

    // NOT_CONFIRMED, RECEIVED or WRONG_STATE when no count waits for the Master; null when one does.
    private static Result refuseApproval(ShipOrder order) {
        if (order.getStatus() == ShipOrderStatus.CLOSED) {
            return Result.RECEIVED;
        }
        if (order.getStatus() != ShipOrderStatus.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        return order.getDeliveryCountStatus() == DeliveryCountStatus.SENT_TO_MASTER ? null : Result.WRONG_STATE;
    }

    // Saved as he goes: a draft (a count sent back stays RETURNED until he sends again).
    private static void touch(ShipOrder order, User cook) {
        if (order.getDeliveryCountStatus() != DeliveryCountStatus.RETURNED) {
            order.setDeliveryCount(DeliveryCountStatus.DRAFT);
        }
        order.setCountedBy(cook);
        order.setCountUpdatedAt(Instant.now());
    }

    private static void checkMaster(User master) {
        if (master == null || master.getRole() != Role.MASTER || !master.isEnabled() || master.getShip() == null) {
            throw new AccessDeniedException("Only the Master of the ship approves or sends back a delivery count");
        }
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
