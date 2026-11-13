package com.nereonix.nereonet_mvp.masterhome;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipientRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLineRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// What the Master's home page (/ship as MASTER) shows: one count query per card, and the latest orders sent. Read only,
// and it reads only from core.
@Service
public class MasterHomeService {

    /**
     * One card: key names its label (masterhome.card.<key>) and its icon (layout/parts :: navIcon), count is the
     * number on it, url where it opens. A card with zero is shown quietly, one with something to do highlighted,
     * unless it only informs (Crew on board is never "to do").
     */
    public record Card(String key, String icon, long count, String url, boolean informs) {

        static Card toDo(String key, String icon, long count, String url) {
            return new Card(key, icon, count, url, false);
        }

        public boolean highlighted() {
            return !informs && count > 0;
        }
    }

    /** ship: the Master's ship; cards: in page order; recentOrders: the last three sent, newest first. */
    public record MasterHome(Ship ship, List<Card> cards, List<ShipOrder> recentOrders) {
    }

    private final UserRepository userRepository;
    private final ItemRequestRepository requestRepository;
    private final ShipOrderLineRepository orderLineRepository;
    private final ShipOrderRepository orderRepository;
    private final AnnouncementRecipientRepository recipientRepository;
    private final LibraryItemRepository libraryItemRepository;

    public MasterHomeService(UserRepository userRepository, ItemRequestRepository requestRepository,
                             ShipOrderLineRepository orderLineRepository, ShipOrderRepository orderRepository,
                             AnnouncementRecipientRepository recipientRepository,
                             LibraryItemRepository libraryItemRepository) {
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.orderLineRepository = orderLineRepository;
        this.orderRepository = orderRepository;
        this.recipientRepository = recipientRepository;
        this.libraryItemRepository = libraryItemRepository;
    }

    @Transactional(readOnly = true)
    public MasterHome load(String email) {
        User master = userRepository.findByEmail(email).orElseThrow();
        Ship ship = master.getShip();
        List<Card> cards = List.of(
                Card.toDo("approvals", "approvals",
                        requestRepository.countByStatusAndShipId(RequestStatus.PENDING, ship.getId()), "/ship/approvals"),
                Card.toDo("order", "order",
                        orderLineRepository.countByOrderStatusOnShipOf(ShipOrderStatus.OPEN, email), "/ship/order"),
                Card.toDo("announcements", "announcements",
                        recipientRepository.countByUserEmailAndAcknowledgedAtIsNull(email), "/ship/news"),
                Card.toDo("library", "library",
                        libraryItemRepository.countRequiredNotAcknowledgedBy(ship, email), "/ship/library"),
                // Room for "Stores" (items low and out of stock, /ship/stores) once the inventory exists: one more
                // Card.toDo here, its label masterhome.card.stores and an icon in layout/parts :: navIcon.
                new Card("crew", "crew", userRepository.countByShipAndEnabledTrue(ship), "/ship/crew", true));
        List<ShipOrder> recent = orderRepository.findTop3ByShipIdAndStatusInOrderBySentAtDescIdDesc(ship.getId(),
                ShipOrderService.SENT_TO_OFFICE);
        return new MasterHome(ship, cards, recent);
    }
}
