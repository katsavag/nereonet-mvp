package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * The office corrects the lines of a SENT order before arranging delivery (P50): change a quantity, remove a line, add
 * one of the ship's active items (or add to its line when the item is already there). Once the delivery is confirmed
 * (CONFIRMED) the lines are read-only again. Each change locks the order's row (as ShipOrderService.confirm does), is
 * recorded in the audit log (line, before, after, note) and marks the line as changed since the ship was last told.
 * The Master and the Chief cook get one notification per editing session, not one per change: when the office
 * presses "Done editing" (finishEditing), after QUIET without changes (notifyIdle, OfficeOrderEditJob), or when the
 * delivery is confirmed (ShipOrderService.confirm calls notifyPending first).
 */
@Service
public class OfficeOrderEditService {

    /** What a change did. */
    public enum Edit {
        DONE,
        // Same quantity and note: nothing changed, nothing recorded.
        UNCHANGED,
        // The order is no longer SENT (delivery confirmed, or closed): nothing changed.
        NOT_EDITABLE,
        // The line was removed (another tab): add the item again to bring it back.
        REMOVED,
        // No SENT/CONFIRMED/CLOSED order with this id, no such line on it, or not an active item of its ship.
        NOT_FOUND
    }

    private final ItemRepository itemRepository;
    private final ShipOrderLineRepository lineRepository;
    private final ShipOrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final EntityManager entityManager;
    private final Duration quiet;

    public OfficeOrderEditService(ItemRepository itemRepository, ShipOrderLineRepository lineRepository,
                                  ShipOrderRepository orderRepository, UserRepository userRepository,
                                  AuditService auditService, NotificationService notificationService,
                                  MessageSource messageSource, EntityManager entityManager,
                                  @Value("${app.orders.office-edit-quiet:PT10M}") Duration quiet) {
        this.itemRepository = itemRepository;
        this.lineRepository = lineRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
        this.entityManager = entityManager;
        this.quiet = quiet;
    }

    /** True while the office can change the order's lines: SENT (P49: "Pending"). */
    public static boolean isEditable(ShipOrder order) {
        return order.getStatus() == ShipOrderStatus.SENT;
    }

    /** A note as it is saved: stripped, null when blank. IllegalArgumentException when longer than the column. */
    public static String note(String note) {
        String stripped = note == null || note.isBlank() ? null : note.strip();
        if (stripped != null && stripped.length() > ShipOrderLine.NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("A note is at most " + ShipOrderLine.NOTE_MAX_LENGTH + " characters");
        }
        return stripped;
    }

    /**
     * Sets the quantity of a line of a SENT order, with an optional note (it replaces the line's note). The first
     * change of a line from before P50 keeps the quantity it found as what the ship sent. IllegalArgumentException for
     * a quantity that is not valid (ShipOrderService.isValidQuantity) or a too long note; AccessDeniedException when
     * the user is not an enabled office user.
     */
    @Transactional
    public Edit changeQuantity(User officeUser, Long orderId, Long lineId, BigDecimal quantity, String note) {
        checkOffice(officeUser);
        if (!ShipOrderService.isValidQuantity(quantity)) {
            throw new IllegalArgumentException("A quantity must be above zero, with at most 3 decimals");
        }
        String cleanNote = note(note);
        ShipOrder order = lockSent(orderId);
        if (order == null) {
            return Edit.NOT_FOUND;
        }
        ShipOrderLine line = lineOf(order, lineId);
        if (line == null) {
            return Edit.NOT_FOUND;
        }
        if (!isEditable(order)) {
            return Edit.NOT_EDITABLE;
        }
        if (line.isRemoved()) {
            return Edit.REMOVED;
        }
        if (line.getQuantity().compareTo(quantity) == 0 && Objects.equals(line.getChangeNote(), cleanNote)) {
            return Edit.UNCHANGED;
        }
        BigDecimal before = line.getQuantity();
        keepOriginal(line);
        line.setQuantity(quantity);
        line.setChangeNote(cleanNote);
        changed(order, line, officeUser, "ORDER_LINE_CHANGED", amount(before, line) + " -> " + amount(quantity, line),
                cleanNote);
        return Edit.DONE;
    }

    /** Removes a line of a SENT order: it stays listed, struck through, and is not delivered. Exceptions as above. */
    @Transactional
    public Edit removeLine(User officeUser, Long orderId, Long lineId, String note) {
        checkOffice(officeUser);
        String cleanNote = note(note);
        ShipOrder order = lockSent(orderId);
        if (order == null) {
            return Edit.NOT_FOUND;
        }
        ShipOrderLine line = lineOf(order, lineId);
        if (line == null) {
            return Edit.NOT_FOUND;
        }
        if (!isEditable(order)) {
            return Edit.NOT_EDITABLE;
        }
        if (line.isRemoved()) {
            return Edit.UNCHANGED;
        }
        keepOriginal(line);
        line.setRemoved(true);
        line.setChangeNote(cleanNote);
        changed(order, line, officeUser, "ORDER_LINE_REMOVED", amount(line.getQuantity(), line) + " -> removed",
                cleanNote);
        return Edit.DONE;
    }

    /**
     * Adds one of the ship's active items to a SENT order. When the order already has a line for it, the quantity is
     * added to that line; a removed line comes back with this quantity. Otherwise a new line, "Added by office".
     * NOT_FOUND also for an item of another ship or an inactive one. Exceptions as changeQuantity.
     */
    @Transactional
    public Edit addItem(User officeUser, Long orderId, Long itemId, BigDecimal quantity, String note) {
        checkOffice(officeUser);
        if (!ShipOrderService.isValidQuantity(quantity)) {
            throw new IllegalArgumentException("A quantity must be above zero, with at most 3 decimals");
        }
        String cleanNote = note(note);
        ShipOrder order = lockSent(orderId);
        if (order == null) {
            return Edit.NOT_FOUND;
        }
        Item item = itemId == null ? null
                : itemRepository.findByIdAndShipId(itemId, order.getShip().getId()).filter(Item::isActive).orElse(null);
        if (item == null) {
            return Edit.NOT_FOUND;
        }
        if (!isEditable(order)) {
            return Edit.NOT_EDITABLE;
        }
        ShipOrderLine line = lineRepository.findByOrderIdAndItemId(order.getId(), item.getId()).orElse(null);
        if (line == null) {
            line = new ShipOrderLine();
            line.setOrder(order);
            line.setItem(item);
            line.setQuantity(quantity);
            line.setAddedByOffice(true);
            line.setChangeNote(cleanNote);
            lineRepository.save(line);
            order.getLines().add(line);
            changed(order, line, officeUser, "ORDER_LINE_ADDED", "added " + amount(quantity, line), cleanNote);
            return Edit.DONE;
        }
        keepOriginal(line);
        BigDecimal before = line.isRemoved() ? null : line.getQuantity();
        BigDecimal after = before == null ? quantity : before.add(quantity);
        if (!ShipOrderService.isValidQuantity(after)) {
            throw new IllegalArgumentException("The quantity of a line is at most 9 whole digits");
        }
        line.setRemoved(false);
        line.setQuantity(after);
        line.setChangeNote(cleanNote);
        changed(order, line, officeUser, "ORDER_LINE_CHANGED", (before == null ? "removed" : amount(before, line))
                + " -> " + amount(after, line) + " (added " + amount(quantity, line) + ")", cleanNote);
        return Edit.DONE;
    }

    /**
     * "Done editing": tells the Master and the Chief cook now what the office changed since they were last told.
     * False when there was nothing to tell. NOT_FOUND-like (false) for an order the ships have not sent.
     */
    @Transactional
    public boolean finishEditing(User officeUser, Long orderId) {
        checkOffice(officeUser);
        ShipOrder order = lockSent(orderId);
        return order != null && notifyPending(order);
    }

    /**
     * Tells the ship about the office's changes of this order not told yet, in one notification to its enabled Master
     * (linking to the order) and Chief cook: "The office changed order 12: 2 lines changed, 1 removed". Lines whose
     * changes cancel out (back to what the ship sent) are not counted; nothing is sent when nothing is left. The
     * caller holds the order's row lock. True when a notification was sent.
     */
    @Transactional
    public boolean notifyPending(ShipOrder order) {
        if (order.getOfficeEditedAt() == null) {
            return false;
        }
        int changed = 0;
        int removed = 0;
        int added = 0;
        for (ShipOrderLine line : order.getLines()) {
            if (!line.isOfficeChangePending()) {
                continue;
            }
            line.setOfficeChangePending(false);
            if (line.isRemoved()) {
                removed++;
            } else if (line.isAddedByOffice()) {
                added++;
            } else if (line.isChangedByOffice()) {
                changed++;
            }
        }
        order.setOfficeEditedAt(null);
        if (changed + removed + added == 0) {
            return false;
        }
        List<String> parts = new ArrayList<>();
        part(parts, "changed", changed);
        part(parts, "removed", removed);
        part(parts, "added", added);
        Ship ship = order.getShip();
        String text = message("order.notification.officeChanged", order.getId(), String.join(", ", parts));
        for (Role role : List.of(Role.MASTER, Role.CHIEF_COOK)) {
            for (User user : userRepository.findAllByShipAndRoleAndEnabledTrue(ship, role)) {
                // Only the Master has the order pages (/ship/orders), as for "Order confirmed".
                notificationService.notify(user, NotificationType.ORDER, ship.getName(), text,
                        role == Role.MASTER ? "/ship/orders/" + order.getId() : null);
            }
        }
        return true;
    }

    /**
     * The orders the office last changed more than QUIET before now and has not told the ship about: tells each ship
     * now (OfficeOrderEditJob, every minute). Returns how many notifications went out.
     */
    @Transactional
    public int notifyIdle(Instant now) {
        int sent = 0;
        for (ShipOrder candidate : orderRepository.findAllByOfficeEditedAtBefore(now.minus(quiet))) {
            entityManager.refresh(candidate, LockModeType.PESSIMISTIC_WRITE);
            // Changed again, or told, while this job waited for the lock.
            if (candidate.getOfficeEditedAt() != null && candidate.getOfficeEditedAt().isBefore(now.minus(quiet))
                    && notifyPending(candidate)) {
                sent++;
            }
        }
        return sent;
    }

    // "2 lines changed" first, then the short forms: "2 lines changed, 1 removed".
    private void part(List<String> parts, String kind, int count) {
        if (count > 0) {
            parts.add(message("order.notification.officeChanged." + kind + (parts.isEmpty() ? "" : ".short"), count));
        }
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, Locale.ENGLISH);
    }

    private void changed(ShipOrder order, ShipOrderLine line, User officeUser, String action, String change,
                         String note) {
        line.setOfficeChangePending(true);
        order.setOfficeEditedAt(Instant.now());
        auditService.record(action, order.getShip(), "Order #" + order.getId() + ", " + line.getItem().getName() + ": "
                + change + (note == null ? "" : ", note: " + note), officeUser);
    }

    // What the ship sent stays, whatever the office does next. Lines added by the office have none.
    private static void keepOriginal(ShipOrderLine line) {
        if (line.getOriginalQuantity() == null && !line.isAddedByOffice()) {
            line.setOriginalQuantity(line.getQuantity());
        }
    }

    // "50 kg"
    private static String amount(BigDecimal quantity, ShipOrderLine line) {
        return quantity.stripTrailingZeros().toPlainString() + " " + line.getItem().getUnit();
    }

    private ShipOrder lockSent(Long orderId) {
        ShipOrder order = entityManager.find(ShipOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        return order == null || !ShipOrderService.SENT_TO_OFFICE.contains(order.getStatus()) ? null : order;
    }

    private static ShipOrderLine lineOf(ShipOrder order, Long lineId) {
        return order.getLines().stream().filter(line -> line.getId().equals(lineId)).findFirst().orElse(null);
    }

    private static void checkOffice(User officeUser) {
        if (officeUser == null || officeUser.getRole() != Role.OFFICE_USER || !officeUser.isEnabled()) {
            throw new AccessDeniedException("Only the office changes the lines of a sent order");
        }
    }
}
