package com.nereonix.nereonet_mvp.core.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// Item requests, for any feature (P33). The Chief cook's and the crew's request pages call submit; the Master decides
// on /ship/approvals (approve, decline; P34).
@Service
public class RequestService {

    // An item needs a unit. A new item asked for without one gets this when the request is approved (P34); the
    // office can change it on the item list.
    public static final String NEW_ITEM_DEFAULT_UNIT = "pcs";

    private final ItemRequestRepository requestRepository;
    private final RequestLineRepository lineRepository;
    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final ShipOrderService shipOrderService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;

    public RequestService(ItemRequestRepository requestRepository, RequestLineRepository lineRepository,
                          UserRepository userRepository, ItemRepository itemRepository,
                          ShipOrderService shipOrderService, AuditService auditService,
                          NotificationService notificationService, MessageSource messageSource) {
        this.requestRepository = requestRepository;
        this.lineRepository = lineRepository;
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.shipOrderService = shipOrderService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
    }

    /**
     * Saves a new request as PENDING with its lines (request.getLines(), not saved yet), records it in the audit log
     * (by requestedBy, so no signed-in user is needed) and notifies the enabled Master of the ship: "New request to
     * approve", linking to /ship/approvals/{id}. createdAt is now unless already set. The caller sets ship and
     * requestedBy. Throws IllegalArgumentException for a request without lines, or a line that has both or neither of
     * an item and a new item name, an item of another ship, or a quantity that is not above zero.
     */
    @Transactional
    public ItemRequest submit(ItemRequest request) {
        if (request.getShip() == null || request.getRequestedBy() == null) {
            throw new IllegalArgumentException("A request needs a ship and the person who asks");
        }
        if (request.getLines().isEmpty()) {
            throw new IllegalArgumentException("A request needs at least one line");
        }
        for (RequestLine line : request.getLines()) {
            check(request, line);
        }
        request.setStatus(RequestStatus.PENDING);
        request.setRejectionReason(null);
        request.setDecidedBy(null);
        request.setDecidedAt(null);
        if (request.getCreatedAt() == null) {
            request.setCreatedAt(Instant.now());
        }
        requestRepository.save(request);
        for (RequestLine line : request.getLines()) {
            line.setRequest(request);
            lineRepository.save(line);
        }

        User requestedBy = request.getRequestedBy();
        auditService.record("REQUEST_SUBMITTED", request.getShip(),
                "Request #" + request.getId() + ", " + request.getLines().size() + " lines", requestedBy);
        String text = messageSource.getMessage("request.notification.submitted", null, Locale.ENGLISH);
        String subject = requestedBy.getFullName() + " · " + request.getShip().getName();
        for (User master : userRepository.findAllByShipAndRoleAndEnabledTrue(request.getShip(), Role.MASTER)) {
            if (!master.getId().equals(requestedBy.getId())) {
                notificationService.notify(master, NotificationType.REQUEST, subject, text,
                        "/ship/approvals/" + request.getId());
            }
        }
        return request;
    }

    /**
     * The Master approves a PENDING request of their own ship, as a whole: it becomes APPROVED (decidedBy, decidedAt);
     * each new-item line is linked to the ship's item of that name (ignoring case), or to a new active Provisions item
     * made from its name and unit; then the request goes into the ship's OPEN order (ShipOrderService). Nothing is
     * sent to the office. Records "Request approved" and notifies the person who asked. NOT_PENDING (nothing changed)
     * when it was already decided or cancelled; NOT_FOUND when the ship has no such request. AccessDeniedException
     * when the user is not an enabled Master with a ship.
     */
    @Transactional
    public Decision approve(User master, Long requestId) {
        ItemRequest request = findForDecision(master, requestId);
        if (request == null) {
            return Decision.NOT_FOUND;
        }
        if (request.getStatus() != RequestStatus.PENDING) {
            return Decision.NOT_PENDING;
        }
        request.setStatus(RequestStatus.APPROVED);
        request.setRejectionReason(null);
        request.setDecidedBy(master);
        request.setDecidedAt(Instant.now());
        for (RequestLine line : request.getLines()) {
            if (line.isNewItem()) {
                line.setItem(itemFor(request.getShip(), line, master));
            }
        }
        shipOrderService.addApprovedRequest(request);

        auditService.record("REQUEST_APPROVED", request.getShip(),
                "Request #" + request.getId() + ", " + request.getLines().size() + " lines", master);
        notifyRequester(request, messageSource.getMessage("request.notification.approved", null, Locale.ENGLISH));
        return Decision.DECIDED;
    }

    /**
     * The Master declines a PENDING request of their own ship, as a whole: REJECTED with the reason (required, at most
     * ItemRequest.REJECTION_REASON_MAX_LENGTH), decidedBy and decidedAt. Nothing goes into the ship order. Records
     * "Request declined" and notifies the person who asked, with the reason. NOT_PENDING and NOT_FOUND as approve.
     */
    @Transactional
    public Decision decline(User master, Long requestId, String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > ItemRequest.REJECTION_REASON_MAX_LENGTH) {
            throw new IllegalArgumentException("A decline needs a reason of at most "
                    + ItemRequest.REJECTION_REASON_MAX_LENGTH + " characters");
        }
        ItemRequest request = findForDecision(master, requestId);
        if (request == null) {
            return Decision.NOT_FOUND;
        }
        if (request.getStatus() != RequestStatus.PENDING) {
            return Decision.NOT_PENDING;
        }
        request.setStatus(RequestStatus.REJECTED);
        request.setRejectionReason(reason.strip());
        request.setDecidedBy(master);
        request.setDecidedAt(Instant.now());

        auditService.record("REQUEST_DECLINED", request.getShip(),
                "Request #" + request.getId() + ": " + request.getRejectionReason(), master);
        notifyRequester(request, messageSource.getMessage("request.notification.declined",
                new Object[] {request.getRejectionReason()}, Locale.ENGLISH));
        return Decision.DECIDED;
    }

    /** What approve or decline did. */
    public enum Decision {
        DECIDED,
        // Already approved, declined or cancelled: nothing changed.
        NOT_PENDING,
        // No such request on the Master's ship.
        NOT_FOUND
    }

    // The request locked for the decision, or null when the Master's ship has no such request.
    private ItemRequest findForDecision(User master, Long requestId) {
        if (master == null || master.getRole() != Role.MASTER || !master.isEnabled() || master.getShip() == null) {
            throw new AccessDeniedException("Only the Master of the ship decides on its requests");
        }
        return requestRepository.findForDecision(requestId, master.getShip().getId()).orElse(null);
    }

    // The ship's item with the line's new item name, or a new active Provisions item (recorded in the audit log).
    private Item itemFor(Ship ship, RequestLine line, User master) {
        Item existing = itemRepository.findFirstByShipIdAndNameIgnoreCaseOrderByIdAsc(ship.getId(),
                line.getNewItemName().strip()).orElse(null);
        if (existing != null) {
            return existing;
        }
        Item item = new Item();
        item.setShip(ship);
        item.setName(line.getNewItemName().strip());
        item.setUnit(line.getNewItemUnit() == null || line.getNewItemUnit().isBlank()
                ? NEW_ITEM_DEFAULT_UNIT : line.getNewItemUnit().strip());
        item.setCategory(ItemCategory.PROVISIONS);
        item.setActive(true);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        auditService.record("ITEM_CREATED", ship, item.getName() + ", " + item.getUnit() + ", "
                + item.getCategory().fileValue() + ", -", master);
        return item;
    }

    private void notifyRequester(ItemRequest request, String text) {
        User requestedBy = request.getRequestedBy();
        if (requestedBy != null && requestedBy.isEnabled()) {
            notificationService.notify(requestedBy, NotificationType.REQUEST, request.getShip().getName(), text,
                    "/ship/requests/" + request.getId());
        }
    }

    private static void check(ItemRequest request, RequestLine line) {
        boolean hasItem = line.getItem() != null;
        boolean hasNewItem = line.getNewItemName() != null && !line.getNewItemName().isBlank();
        if (hasItem == hasNewItem) {
            throw new IllegalArgumentException("A request line has either an item or a new item name");
        }
        if (hasItem) {
            if (!line.getItem().getShip().getId().equals(request.getShip().getId())) {
                throw new IllegalArgumentException("A request line's item must be of the request's ship");
            }
            line.setNewItemName(null);
            line.setNewItemUnit(null);
        } else {
            line.setNewItemName(line.getNewItemName().strip());
            if (line.getNewItemUnit() != null) {
                line.setNewItemUnit(line.getNewItemUnit().isBlank() ? null : line.getNewItemUnit().strip());
            }
        }
        if (line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("A request line's quantity must be above zero");
        }
    }
}
