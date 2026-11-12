package com.nereonix.nereonet_mvp.core.order;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipOrderLineRepository extends JpaRepository<ShipOrderLine, Long> {

    Optional<ShipOrderLine> findByOrderIdAndItemId(Long orderId, Long itemId);
}
