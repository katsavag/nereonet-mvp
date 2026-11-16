package com.nereonix.nereonet_mvp.shiporder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.order.DeliveryCountStatus;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptFileRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.Change;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.Send;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.SendDetails;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestLineRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * The ship order pages: the OPEN order of the signed-in user's own ship, and its sent orders. The Master changes
 * and sends the order, and confirms its receipt; since P55 the Chief cook reads the same pages (Vessel order, detail),
 * and nothing more: every change is ShipOrderService's or OrderReceiptService's, which refuse anyone but the ship's
 * enabled Master, and the receipt pages (order) and the services' user (master) refuse anyone but the Master here.
 * An order of another ship is a 404, never a 403, so nobody can probe which exist.
 */
@Service
public class ShipOrderPageService {

    /** The views of Vessel order (?view=): the OPEN order, the orders in progress, the received ones. */
    static final String VIEW_CURRENT = "current";
    static final String VIEW_PROGRESS = "progress";
    static final String VIEW_RECEIVED = "received";

    private static final List<ShipOrderStatus> RECEIVED = List.of(ShipOrderStatus.CLOSED);

    // Sent and not received yet.
    static final List<ShipOrderStatus> IN_PROGRESS = List.of(ShipOrderStatus.SENT, ShipOrderStatus.CONFIRMED);

    // Most urgent first: the scheduled deliveries by delivery date (earliest first), then the orders waiting for the
    // office by sent date (waiting longest first).
    private static final Comparator<ShipOrder> MOST_URGENT_FIRST = Comparator
            .comparing((ShipOrder order) -> order.getStatus() == ShipOrderStatus.CONFIRMED ? 0 : 1)
            .thenComparing(ShipOrder::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ShipOrder::getSentAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ShipOrder::getId);

    /** One line of the order, and who asked for it: "Chief Cook 6, A. Santos 4" (empty when nobody is linked). */
    public record LineView(ShipOrderLine line, String askedBy) {
    }

    /** One category's lines, by item name. */
    public record LineGroup(ItemCategory category, List<LineView> lines) {
    }

    /** An order with its lines by category (Provisions, then Personal items); order is null when there is none. */
    public record OrderView(Ship ship, ShipOrder order, List<LineGroup> groups) {
        public boolean isEmpty() {
            return order == null || groups.isEmpty();
        }

        public int lineCount() {
            return groups.stream().mapToInt(group -> group.lines().size()).sum();
        }
    }

    /**
     * Vessel order in one of its three views, with only what that view shows: current (the OPEN order; an empty
     * view when there is none) in "current", inProgress in "progress", received (CLOSED, newest sent first) in
     * "received"; the others are null. progressCount and receivedCount are the numbers on the tabs. attention (the
     * current view only): the orders in progress that need this user, the Master's deliveries to receive or the
     * Chief cook's deliveries to count.
     */
    public record OrderPage(Ship ship, String view, OrderView current, List<InProgress> inProgress,
                            List<ShipOrder> received, long progressCount, long receivedCount, long attention) {
    }

    /**
     * An order in progress on Vessel order: SENT or CONFIRMED, with its number of items (lines the office did not
     * remove). overdue: CONFIRMED and its delivery date (UTC) has passed without a receipt.
     */
    public record InProgress(ShipOrder order, int itemCount, boolean overdue, boolean invoicePhotographed) {
        public boolean toReceive() {
            return order.getStatus() == ShipOrderStatus.CONFIRMED;
        }

        /** P61: the Chief cook can still count the delivery (scheduled, and his count not with the Master). */
        public boolean toCount() {
            return toReceive() && order.getDeliveryCountStatus().editable();
        }

        /** Confirm receipt opens "Photograph the invoice" first (no page yet, no count of the cook to approve). */
        public boolean invoiceFirstForMaster() {
            return ShipOrderPageService.invoiceFirstForMaster(order, invoicePhotographed);
        }

        /** Count delivery opens "Photograph the invoice" first (no page yet, the count not started). */
        public boolean invoiceFirstForCook() {
            return ShipOrderPageService.invoiceFirstForCook(order, invoicePhotographed);
        }
    }

    private final UserRepository userRepository;
    private final ShipOrderRepository orderRepository;
    private final RequestLineRepository requestLineRepository;
    private final ShipOrderService shipOrderService;
    private final MessageSource messageSource;
    private final OrderReceiptFileRepository receiptFileRepository;

    public ShipOrderPageService(UserRepository userRepository, ShipOrderRepository orderRepository,
                                RequestLineRepository requestLineRepository, ShipOrderService shipOrderService,
                                MessageSource messageSource, OrderReceiptFileRepository receiptFileRepository) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.requestLineRepository = requestLineRepository;
        this.shipOrderService = shipOrderService;
        this.messageSource = messageSource;
        this.receiptFileRepository = receiptFileRepository;
    }

    /** P62: the Master's Confirm receipt goes to "Photograph the invoice" first. */
    public static boolean invoiceFirstForMaster(ShipOrder order, boolean invoicePhotographed) {
        return order.getStatus() == ShipOrderStatus.CONFIRMED && !order.isCountToApprove() && !invoicePhotographed;
    }

    /** P62: the Chief cook's Count delivery goes to "Photograph the invoice" first. */
    public static boolean invoiceFirstForCook(ShipOrder order, boolean invoicePhotographed) {
        return order.getStatus() == ShipOrderStatus.CONFIRMED && order.getDeliveryCountStatus() == DeliveryCountStatus.NONE
                && !invoicePhotographed;
    }

    /** P62: a page of the order's invoice was photographed. */
    @Transactional(readOnly = true)
    public boolean invoicePhotographed(Long orderId) {
        return receiptFileRepository.countByOrderIdAndInvoicePageNotNull(orderId) > 0;
    }

    /**
     * One order of the ship, for "Photograph the invoice": the vessel's enabled Master or Chief cook; 404 for
     * another ship's or an OPEN one. AccessDeniedException for anyone else.
     */
    @Transactional(readOnly = true)
    public ShipOrder invoiceOrder(String email, Long id) {
        Ship ship = ship(reader(email));
        return orderRepository.findByIdAndShipId(id, ship.getId())
                .filter(found -> found.getStatus() != ShipOrderStatus.OPEN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** P62: the enabled Master or Chief cook with this email. AccessDeniedException for anyone else. */
    User crew(String email) {
        return reader(email);
    }

    /**
     * Vessel order for the Master or the Chief cook. view: "progress" or "received"; anything else is the current
     * order.
     */
    @Transactional(readOnly = true)
    public OrderPage page(String email, String view) {
        User user = reader(email);
        Ship ship = ship(user);
        if (VIEW_PROGRESS.equals(view)) {
            List<InProgress> inProgress = inProgress(ship);
            return new OrderPage(ship, VIEW_PROGRESS, null, inProgress, null, inProgress.size(),
                    orderRepository.countByShipIdAndStatusIn(ship.getId(), RECEIVED), 0);
        }
        if (VIEW_RECEIVED.equals(view)) {
            List<ShipOrder> received = orderRepository.findAllByShipIdAndStatusInOrderBySentAtDescIdDesc(ship.getId(),
                    RECEIVED);
            return new OrderPage(ship, VIEW_RECEIVED, null, null, received,
                    orderRepository.countByShipIdAndStatusIn(ship.getId(), IN_PROGRESS), received.size(), 0);
        }
        ShipOrder open = orderRepository.findByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN).orElse(null);
        // The orders in progress without their lines: how many there are, and how many need this user.
        List<ShipOrder> progress = orderRepository.findAllByShipIdAndStatusIn(ship.getId(), IN_PROGRESS);
        boolean master = user.getRole() == Role.MASTER;
        long attention = progress.stream()
                .filter(order -> order.getStatus() == ShipOrderStatus.CONFIRMED
                        && (master || order.getDeliveryCountStatus().editable()))
                .count();
        return new OrderPage(ship, VIEW_CURRENT, view(ship, open), null, null, progress.size(),
                orderRepository.countByShipIdAndStatusIn(ship.getId(), RECEIVED), attention);
    }

    // The ship's orders in progress: SENT and CONFIRMED, most urgent first (the scheduled deliveries by delivery
    // date, then the orders waiting for the office by sent date).
    private List<InProgress> inProgress(Ship ship) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return orderRepository.findAllByShipIdAndStatusIn(ship.getId(), IN_PROGRESS).stream()
                .sorted(MOST_URGENT_FIRST)
                .map(order -> new InProgress(order, (int) order.getLines().stream().filter(l -> !l.isRemoved()).count(),
                        order.getStatus() == ShipOrderStatus.CONFIRMED && order.getDeliveryDate() != null
                                && order.getDeliveryDate().isBefore(today),
                        order.getStatus() == ShipOrderStatus.CONFIRMED
                                && receiptFileRepository.countByOrderIdAndInvoicePageNotNull(order.getId()) > 0))
                .toList();
    }

    /** One order of the ship with its lines; 404 for another ship's. The Master or the Chief cook. */
    @Transactional(readOnly = true)
    public OrderView detail(String email, Long id) {
        Ship ship = ship(reader(email));
        ShipOrder order = orderRepository.findByIdAndShipId(id, ship.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return view(ship, order);
    }

    /** One order of the ship, OPEN or not; 404 for another ship's (the receipt pages, the Master's only). */
    @Transactional(readOnly = true)
    public ShipOrder order(String email, Long id) {
        Ship ship = ship(master(email));
        return orderRepository.findByIdAndShipId(id, ship.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * One order of the Chief cook's ship with its lines, for the delivery count; 404 for another ship's or an OPEN
     * one. AccessDeniedException for anyone but the ship's enabled Chief cook.
     */
    @Transactional(readOnly = true)
    public OrderView countView(String email, Long id) {
        Ship ship = ship(cook(email));
        ShipOrder order = orderRepository.findByIdAndShipId(id, ship.getId())
                .filter(found -> found.getStatus() != ShipOrderStatus.OPEN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return view(ship, order);
    }

    /** True when the user with this email counts the ship's deliveries: its enabled Chief cook. */
    public boolean canCount(String email) {
        return isEnabled(user(email), Role.CHIEF_COOK);
    }

    // The ship's enabled Chief cook (the delivery count). AccessDeniedException for anyone else.
    User cook(String email) {
        User user = user(email);
        if (!isEnabled(user, Role.CHIEF_COOK)) {
            throw new AccessDeniedException("Only the Chief cook counts the ship's deliveries");
        }
        return user;
    }

    /** A crew text (English only) with its arguments. */
    String message(String key, Object... args) {
        return messageSource.getMessage(key, args, Locale.ENGLISH);
    }

    /** A line of the Master's ship (the rule of its item before a change); 404 for another ship's. */
    @Transactional(readOnly = true)
    public ShipOrderLine line(String email, Long lineId) {
        Ship ship = ship(master(email));
        ShipOrderLine line = shipOrderService.findLine(ship.getId(), lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // The item is shown in the error message, after this transaction.
        line.getItem().getName();
        return line;
    }

    @Transactional
    public Change changeQuantity(String email, Long lineId, BigDecimal quantity) {
        return shipOrderService.changeQuantity(master(email), lineId, quantity);
    }

    @Transactional
    public Change removeLine(String email, Long lineId) {
        return shipOrderService.removeLine(master(email), lineId);
    }

    @Transactional
    public Send send(String email, SendOrderForm form) {
        return shipOrderService.send(master(email),
                new SendDetails(form.getRequestedPort(), form.getNeededBy(), form.getOfficeNote()));
    }

    private OrderView view(Ship ship, ShipOrder order) {
        if (order == null) {
            return new OrderView(ship, null, List.of());
        }
        // Who asked for each line, from one query for the whole order.
        Map<Long, List<RequestLine>> asked = requestLineRepository.findAllByShipOrderLineOrderIdOrderByIdAsc(order.getId())
                .stream().collect(Collectors.groupingBy(line -> line.getShipOrderLine().getId()));
        Map<ItemCategory, List<LineView>> byCategory = new EnumMap<>(ItemCategory.class);
        order.getLines().stream()
                .sorted(Comparator.comparing((ShipOrderLine line) -> line.getItem().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ShipOrderLine::getId))
                .forEach(line -> byCategory.computeIfAbsent(line.getItem().getCategory(), category -> new ArrayList<>())
                        .add(new LineView(line, askedBy(asked.getOrDefault(line.getId(), List.of())))));
        List<LineGroup> groups = byCategory.entrySet().stream()
                .map(entry -> new LineGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new OrderView(ship, order, groups);
    }

    // "Chief Cook 6, A. Santos 4": each person once, in the order they first asked, with what they asked for in all.
    private String askedBy(List<RequestLine> requestLines) {
        Map<String, BigDecimal> byPerson = new LinkedHashMap<>();
        for (RequestLine requestLine : requestLines) {
            byPerson.merge(person(requestLine.getRequest().getRequestedBy()), requestLine.getQuantity(), BigDecimal::add);
        }
        return byPerson.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(", "));
    }

    // The Master and the Chief cook by their rank (one of each on a ship), everyone else as "A. Santos".
    private String person(User user) {
        if (user == null) {
            return messageSource.getMessage("audit.deletedUser", null, Locale.ENGLISH);
        }
        if (user.getRole().isOnePerShip()) {
            return messageSource.getMessage("rank." + user.getRole().name(), null, Locale.ENGLISH);
        }
        return shortName(user.getFullName());
    }

    /** "Ana Santos" is "A. Santos", "Maria de la Cruz" is "M. de la Cruz"; a single name stays as it is. */
    static String shortName(String fullName) {
        String name = fullName == null ? "" : fullName.strip();
        int space = name.indexOf(' ');
        if (space < 0) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, 1)) + ". " + name.substring(space + 1).strip();
    }

    /** True when the user with this email changes, sends and receives the ship's order: its enabled Master. */
    public boolean canManage(String email) {
        return isEnabled(user(email), Role.MASTER);
    }

    // The ship's enabled Master: the order's changes and the receipt pages. AccessDeniedException for anyone else.
    User master(String email) {
        User user = user(email);
        if (!isEnabled(user, Role.MASTER)) {
            throw new AccessDeniedException("Only the Master changes, sends or receives the ship's order");
        }
        return user;
    }

    // The ship's enabled Master or Chief cook: the order read only. AccessDeniedException for anyone else.
    private User reader(String email) {
        User user = user(email);
        if (!isEnabled(user, Role.MASTER) && !isEnabled(user, Role.CHIEF_COOK)) {
            throw new AccessDeniedException("Only the Master and the Chief cook read the ship's order");
        }
        return user;
    }

    private User user(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    private static boolean isEnabled(User user, Role role) {
        return user.getRole() == role && user.isEnabled();
    }

    private static Ship ship(User user) {
        if (user.getShip() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return user.getShip();
    }
}
