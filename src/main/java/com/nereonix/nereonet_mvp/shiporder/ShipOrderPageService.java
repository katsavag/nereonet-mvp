package com.nereonix.nereonet_mvp.shiporder;

import java.math.BigDecimal;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.item.ItemCategory;
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
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * The Master's ship order pages (P35): the OPEN order of the signed-in Master's own ship, and its past orders. The
 * changes are ShipOrderService's. An order of another ship is a 404, never a 403, so nobody can probe which exist.
 */
@Service
public class ShipOrderPageService {

    static final List<ShipOrderStatus> PAST = List.of(ShipOrderStatus.SENT, ShipOrderStatus.CONFIRMED,
            ShipOrderStatus.CLOSED);

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

    public record PastOrders(Ship ship, List<ShipOrder> orders) {
    }

    private final UserRepository userRepository;
    private final ShipOrderRepository orderRepository;
    private final RequestLineRepository requestLineRepository;
    private final ShipOrderService shipOrderService;
    private final MessageSource messageSource;

    public ShipOrderPageService(UserRepository userRepository, ShipOrderRepository orderRepository,
                                RequestLineRepository requestLineRepository, ShipOrderService shipOrderService,
                                MessageSource messageSource) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.requestLineRepository = requestLineRepository;
        this.shipOrderService = shipOrderService;
        this.messageSource = messageSource;
    }

    /** The ship's OPEN order with its lines; an empty view when there is none. */
    @Transactional(readOnly = true)
    public OrderView open(String email) {
        Ship ship = ship(master(email));
        ShipOrder order = orderRepository.findByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN).orElse(null);
        return view(ship, order);
    }

    /** SENT, CONFIRMED and CLOSED orders of the ship, newest sent first. */
    @Transactional(readOnly = true)
    public PastOrders past(String email) {
        Ship ship = ship(master(email));
        return new PastOrders(ship, orderRepository.findAllByShipIdAndStatusInOrderBySentAtDescIdDesc(ship.getId(), PAST));
    }

    /** One order of the ship with its lines; 404 for another ship's. */
    @Transactional(readOnly = true)
    public OrderView detail(String email, Long id) {
        Ship ship = ship(master(email));
        ShipOrder order = orderRepository.findByIdAndShipId(id, ship.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return view(ship, order);
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

    private User master(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    private static Ship ship(User master) {
        if (master.getShip() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return master.getShip();
    }
}
