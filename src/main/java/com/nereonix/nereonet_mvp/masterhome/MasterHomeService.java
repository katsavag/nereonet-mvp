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
    public record Card(String key, String icon, long count, String url, boolean informs, String noteKey,
                       long noteCount) {

        public Card(String key, String icon, long count, String url, boolean informs) {
            this(key, icon, count, url, informs, null, 0);
        }

        static Card toDo(String key, String icon, long count, String url) {
            return new Card(key, icon, count, url, false);
        }

        /** P61: a line under the label (masterhome.card.<noteKey>, with noteCount), or none when noteKey is null. */
        public boolean hasNote() {
            return noteKey != null && noteCount > 0;
        }

        public boolean highlighted() {
            return !informs && count > 0;
        }
    }

    /** ship: the Master's ship; cards: in page order; recentOrders: the last three sent, newest first. */
    public record MasterHome(Ship ship, List<Card> cards, List<ShipOrder> recentOrders) {
    }

    private final UserRepository userRepository;
    private final ShipOrderLineRepository orderLineRepository;
    private final ShipOrderRepository orderRepository;
    private final AnnouncementRecipientRepository recipientRepository;
    private final LibraryItemRepository libraryItemRepository;

    public MasterHomeService(UserRepository userRepository,
                             ShipOrderLineRepository orderLineRepository, ShipOrderRepository orderRepository,
                             AnnouncementRecipientRepository recipientRepository,
                             LibraryItemRepository libraryItemRepository) {
        this.userRepository = userRepository;
        this.orderLineRepository = orderLineRepository;
        this.orderRepository = orderRepository;
        this.recipientRepository = recipientRepository;
        this.libraryItemRepository = libraryItemRepository;
    }

    @Transactional(readOnly = true)
    public MasterHome load(String email) {
        User master = userRepository.findByEmail(email).orElseThrow();
        Ship ship = master.getShip();
        // The deliveries the Master confirms receipt of (CONFIRMED); with exactly one, the card opens its receipt.
        List<ShipOrder> toReceive = orderRepository.findAllByShipIdAndStatus(ship.getId(), ShipOrderStatus.CONFIRMED);
        String deliveriesUrl = toReceive.size() == 1 ? "/ship/orders/" + toReceive.get(0).getId() + "/receipt"
                : "/ship/order";
        // The deliveries the Chief cook counted, waiting for the Master's approval: "1 counted by the Chief cook,
        // to approve" under the card's label.
        long counted = toReceive.stream().filter(ShipOrder::isCountToApprove).count();
        // No Approvals card: Approvals is a tab (phones) and a sidebar entry with the same number waiting.
        List<Card> cards = List.of(
                Card.toDo("order", "order",
                        orderLineRepository.countByOrderStatusOnShipOf(ShipOrderStatus.OPEN, email), "/ship/order"),
                new Card("deliveries", "stores", toReceive.size(), deliveriesUrl, false, "deliveries.counted", counted),
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
