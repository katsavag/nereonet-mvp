package com.nereonix.nereonet_mvp.core.order;

import java.time.Instant;
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

    // The Master's home page: the three most recently sent.
    List<ShipOrder> findTop3ByShipIdAndStatusInOrderBySentAtDescIdDesc(Long shipId, Collection<ShipOrderStatus> statuses);

    // The office (P36): one order the ship has sent, and the checked ones of an export, newest sent first.
    Optional<ShipOrder> findByIdAndStatusIn(Long id, Collection<ShipOrderStatus> statuses);

    List<ShipOrder> findAllByIdInAndStatusInOrderBySentAtDescIdDesc(Collection<Long> ids,
                                                                  Collection<ShipOrderStatus> statuses);

    // P51: the office sidebar counter, Pending and Delivery pending orders.
    long countByStatusIn(Collection<ShipOrderStatus> statuses);

    // Orders sent and never exported, one ship (its Overview tab).

    long countByShipIdAndStatusInAndExportedAtIsNull(Long shipId, Collection<ShipOrderStatus> statuses);

    // P50: orders with office changes the ship has not been told about, last changed before this time.
    List<ShipOrder> findAllByOfficeEditedAtBefore(Instant time);

    List<ShipOrder> findAllByShipIdAndStatusInAndExportedAtIsNullOrderBySentAtDescIdDesc(
            Long shipId, Collection<ShipOrderStatus> statuses);
}
