package com.nereonix.nereonet_mvp.core.order;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipOrderRepository extends JpaRepository<ShipOrder, Long> {

    // At most one OPEN order per ship (ShipOrderService).
    Optional<ShipOrder> findByShipIdAndStatus(Long shipId, ShipOrderStatus status);

    List<ShipOrder> findAllByShipIdAndStatus(Long shipId, ShipOrderStatus status);

    Optional<ShipOrder> findByIdAndShipId(Long id, Long shipId);

    // Past orders (P35): newest sent first.
    List<ShipOrder> findAllByShipIdAndStatusInOrderBySentAtDescIdDesc(Long shipId, Collection<ShipOrderStatus> statuses);
}
