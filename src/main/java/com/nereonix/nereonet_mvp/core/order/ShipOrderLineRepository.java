package com.nereonix.nereonet_mvp.core.order;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShipOrderLineRepository extends JpaRepository<ShipOrderLine, Long> {

    Optional<ShipOrderLine> findByOrderIdAndItemId(Long orderId, Long itemId);

    // A line of an order of this ship (P35); another ship's line is not found.
    Optional<ShipOrderLine> findByIdAndOrderShipId(Long id, Long shipId);

    // The Master's counter of Ship order (P35): lines of the OPEN order of the ship of the user with this email.
    @Query("""
            select count(l) from ShipOrderLine l
            where l.order.status = :status
              and l.order.ship = (select u.ship from User u where u.email = :email)""")
    long countByOrderStatusOnShipOf(ShipOrderStatus status, String email);
}
