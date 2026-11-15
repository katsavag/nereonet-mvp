package com.nereonix.nereonet_mvp.core.order;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceDecisionRepository extends JpaRepository<InvoiceDecision, Long> {

    List<InvoiceDecision> findAllByOrderIdOrderByIdAsc(Long orderId);

    Optional<InvoiceDecision> findByOrderIdAndItemKey(Long orderId, String itemKey);
}
