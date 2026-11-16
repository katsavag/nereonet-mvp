package com.nereonix.nereonet_mvp.core.order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShipOrderRepository extends JpaRepository<ShipOrder, Long> {

    // At most one OPEN order per ship (ShipOrderService).
    Optional<ShipOrder> findByShipIdAndStatus(Long shipId, ShipOrderStatus status);

    List<ShipOrder> findAllByShipIdAndStatus(Long shipId, ShipOrderStatus status);

    // The orders in progress (SENT and CONFIRMED) without an order: how many need the user (Vessel order).
    List<ShipOrder> findAllByShipIdAndStatusIn(Long shipId, Collection<ShipOrderStatus> statuses);

    // The Master's counter of Vessel order, orders and not lines: the orders of the ship of the user with this
    // email that need the Master, the OPEN one when it has a line (to send) and the CONFIRMED ones (to receive).
    @Query("""
            select count(o) from ShipOrder o
            where o.ship = (select u.ship from User u where u.email = :email)
              and (o.status = :toReceive or (o.status = :toSend and o.lines is not empty))""")
    long countToActOnShipOf(String email, ShipOrderStatus toSend, ShipOrderStatus toReceive);

    Optional<ShipOrder> findByIdAndShipId(Long id, Long shipId);

    // The ship's next delivery: its orders with a scheduled delivery (CONFIRMED), earliest delivery first.
    Optional<ShipOrder> findFirstByShipIdAndStatusOrderByDeliveryDateAscIdAsc(Long shipId, ShipOrderStatus status);

    // The numbers on the tabs of Vessel order (in progress, received).
    long countByShipIdAndStatusIn(Long shipId, Collection<ShipOrderStatus> statuses);

    // One page of the received orders of Vessel order: newest sent first.
    Page<ShipOrder> findAllByShipIdAndStatusInOrderBySentAtDescIdDesc(Long shipId, Collection<ShipOrderStatus> statuses,
                                                                      Pageable pageable);

    // One page of the orders in progress of Vessel order, most urgent first: the scheduled deliveries by delivery
    // date (earliest first), then the orders waiting for the office by sent date (waiting longest first).
    @Query(value = """
            select o from ShipOrder o
            where o.ship.id = :shipId and o.status in :statuses
            order by case when o.status = :scheduled then 0 else 1 end, o.deliveryDate asc nulls last,
                     o.sentAt asc nulls last, o.id asc""",
            countQuery = "select count(o) from ShipOrder o where o.ship.id = :shipId and o.status in :statuses")
    Page<ShipOrder> findInProgress(Long shipId, Collection<ShipOrderStatus> statuses, ShipOrderStatus scheduled,
                                   Pageable pageable);

    // The Master's home page: the three most recently sent.
    List<ShipOrder> findTop3ByShipIdAndStatusInOrderBySentAtDescIdDesc(Long shipId, Collection<ShipOrderStatus> statuses);

    // The office: one order the ship has sent, and the checked ones of an export, newest sent first.
    Optional<ShipOrder> findByIdAndStatusIn(Long id, Collection<ShipOrderStatus> statuses);

    List<ShipOrder> findAllByIdInAndStatusInOrderBySentAtDescIdDesc(Collection<Long> ids,
                                                                  Collection<ShipOrderStatus> statuses);

    // The office sidebar counter, Pending and Delivery scheduled orders.
    long countByStatusIn(Collection<ShipOrderStatus> statuses);

    // Orders sent and never exported, one ship (its Overview tab).

    long countByShipIdAndStatusInAndExportedAtIsNull(Long shipId, Collection<ShipOrderStatus> statuses);

    // Orders with office changes the ship has not been told about, last changed before this time.
    List<ShipOrder> findAllByOfficeEditedAtBefore(Instant time);

    List<ShipOrder> findAllByShipIdAndStatusInAndExportedAtIsNullOrderBySentAtDescIdDesc(
            Long shipId, Collection<ShipOrderStatus> statuses);
}
