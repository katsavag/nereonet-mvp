package com.nereonix.nereonet_mvp.core.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// Item requests, for any feature (P33). The Chief cook's and the crew's request pages call submit; the Master decides
// on /ship/approvals.
@Service
public class RequestService {

    private final ItemRequestRepository requestRepository;
    private final RequestLineRepository lineRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;

    public RequestService(ItemRequestRepository requestRepository, RequestLineRepository lineRepository,
                          UserRepository userRepository, AuditService auditService,
                          NotificationService notificationService, MessageSource messageSource) {
        this.requestRepository = requestRepository;
        this.lineRepository = lineRepository;
        this.userRepository = userRepository;
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
