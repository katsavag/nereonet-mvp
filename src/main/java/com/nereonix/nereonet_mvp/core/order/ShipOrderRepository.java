package com.nereonix.nereonet_mvp.core.order;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipOrderRepository extends JpaRepository<ShipOrder, Long> {

    // At most one OPEN order per ship (ShipOrderService).
    Optional<ShipOrder> findByShipIdAndStatus(Long shipId, ShipOrderStatus status);

    List<ShipOrder> findAllByShipIdAndStatus(Long shipId, ShipOrderStatus status);
}
