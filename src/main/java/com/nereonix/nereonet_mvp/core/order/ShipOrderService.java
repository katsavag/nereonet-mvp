package com.nereonix.nereonet_mvp.core.order;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

// Ship orders, for any feature (P34). RequestService.approve adds each approved request to the ship's OPEN order.
@Service
public class ShipOrderService {

    private final ShipOrderRepository orderRepository;
    private final ShipOrderLineRepository lineRepository;
    private final EntityManager entityManager;

    public ShipOrderService(ShipOrderRepository orderRepository, ShipOrderLineRepository lineRepository,
                            EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.entityManager = entityManager;
    }

    /**
     * Adds an APPROVED request to its ship's OPEN order, creating the order if there is none: each line's quantity is
     * added to the order line of the same item, or a new order line is made, and the request line is linked to it.
     * Every line must have an item (RequestService.approve creates the new ones first). The ship's row is locked
     * first, so two approvals at the same time never make two OPEN orders, or two lines for one item.
     */
    @Transactional
    public ShipOrder addApprovedRequest(ItemRequest request) {
        if (request.getStatus() != RequestStatus.APPROVED) {
            throw new IllegalArgumentException("Only an approved request goes into the ship order");
        }
        Ship ship = request.getShip();
        entityManager.lock(entityManager.contains(ship) ? ship : entityManager.find(Ship.class, ship.getId()),
                LockModeType.PESSIMISTIC_WRITE);

        ShipOrder order = orderRepository.findByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN)
                .orElseGet(() -> open(ship));
        for (RequestLine requestLine : request.getLines()) {
            if (requestLine.getItem() == null) {
                throw new IllegalArgumentException("A request line needs an item before it goes into the ship order");
            }
            ShipOrderLine line = lineRepository.findByOrderIdAndItemId(order.getId(), requestLine.getItem().getId())
                    .orElse(null);
            if (line == null) {
                line = new ShipOrderLine();
                line.setOrder(order);
                line.setItem(requestLine.getItem());
                line.setQuantity(requestLine.getQuantity());
                lineRepository.save(line);
                order.getLines().add(line);
            } else {
                line.setQuantity(line.getQuantity().add(requestLine.getQuantity()));
            }
            requestLine.setShipOrderLine(line);
        }
        return order;
    }

    private ShipOrder open(Ship ship) {
        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(ShipOrderStatus.OPEN);
        order.setCreatedAt(Instant.now());
        return orderRepository.save(order);
    }
}
