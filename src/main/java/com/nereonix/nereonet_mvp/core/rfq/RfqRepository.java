package com.nereonix.nereonet_mvp.core.rfq;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RfqRepository extends JpaRepository<Rfq, Long> {

    // An order's RFQs, newest first (the order page), and its one OPEN RFQ.
    List<Rfq> findAllByOrderIdOrderByIdDesc(Long orderId);

    Optional<Rfq> findByOrderIdAndStatus(Long orderId, RfqStatus status);

    Optional<Rfq> findByIdAndOrderId(Long id, Long orderId);

    // The latest awarded RFQ of an order: "Awarded to VANOS · EUR 1,240.50" and the Confirm form's prefill.
    Optional<Rfq> findFirstByOrderIdAndStatusOrderByClosedAtDescIdDesc(Long orderId, RfqStatus status);
}
