package com.nereonix.nereonet_mvp.approval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.request.RequestService.Decision;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * The Master's approvals (P33): the item requests of the signed-in Master's own ship. A request of another ship is a
 * 404, never a 403, so nobody can probe which requests exist.
 */
@Service
public class ApprovalService {

    /** The requests of one tab, and the number in each tab (shown on all three). */
    public record Approvals(Ship ship, ApprovalTab tab, List<ItemRequest> requests, long pending, long approved,
                            long declined) {
    }

    /** One category's lines of a request, in the request's order. */
    public record LineGroup(ItemCategory category, List<RequestLine> lines) {
    }

    public record Detail(ItemRequest request, List<LineGroup> groups) {
    }

    private final UserRepository userRepository;
    private final ItemRequestRepository requestRepository;
    private final RequestService requestService;

    public ApprovalService(UserRepository userRepository, ItemRequestRepository requestRepository,
                           RequestService requestService) {
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.requestService = requestService;
    }

    /** Pending: PENDING, oldest first. Approved: APPROVED, and Declined: REJECTED, newest decision first. */
    @Transactional(readOnly = true)
    public Approvals list(String email, ApprovalTab tab) {
        Ship ship = ship(email);
        List<ItemRequest> requests = tab == ApprovalTab.PENDING
                ? requestRepository.findAllByShipIdAndStatusOrderByCreatedAtAscIdAsc(ship.getId(), tab.status())
                : requestRepository.findAllByShipIdAndStatusOrderByDecidedAtDescIdDesc(ship.getId(), tab.status());
        // The rows show the number of lines: load them here (in batches, see ItemRequest.lines).
        requests.forEach(request -> request.getLines().size());
        return new Approvals(ship, tab, requests, count(ship, RequestStatus.PENDING),
                count(ship, RequestStatus.APPROVED), count(ship, RequestStatus.REJECTED));
    }

    private long count(Ship ship, RequestStatus status) {
        return requestRepository.countByStatusAndShipId(status, ship.getId());
    }

    /** A request of the Master's ship with its lines by category (Provisions, then Personal items); otherwise 404. */
    @Transactional(readOnly = true)
    public Detail detail(String email, Long id) {
        Ship ship = ship(email);
        ItemRequest request = requestRepository.findByIdAndShipId(id, ship.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<ItemCategory, List<RequestLine>> byCategory = new EnumMap<>(ItemCategory.class);
        for (RequestLine line : request.getLines()) {
            byCategory.computeIfAbsent(line.getCategory(), category -> new ArrayList<>()).add(line);
        }
        List<LineGroup> groups = byCategory.entrySet().stream()
                .map(entry -> new LineGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new Detail(request, groups);
    }

    /** The signed-in Master approves a request of their ship (RequestService.approve). */
    @Transactional
    public Decision approve(String email, Long id) {
        return requestService.approve(userRepository.findByEmail(email).orElseThrow(), id);
    }

    /** The signed-in Master declines a request of their ship with a reason (RequestService.decline). */
    @Transactional
    public Decision decline(String email, Long id, String reason) {
        return requestService.decline(userRepository.findByEmail(email).orElseThrow(), id, reason);
    }

    private Ship ship(String email) {
        User master = userRepository.findByEmail(email).orElseThrow();
        if (master.getShip() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return master.getShip();
    }
}
