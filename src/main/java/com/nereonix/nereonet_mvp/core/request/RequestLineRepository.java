package com.nereonix.nereonet_mvp.core.request;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestLineRepository extends JpaRepository<RequestLine, Long> {

    // The request lines that went into an order (P35), to show who asked for what; oldest first.
    List<RequestLine> findAllByShipOrderLineOrderIdOrderByIdAsc(Long orderId);

    List<RequestLine> findAllByShipOrderLineId(Long shipOrderLineId);
}
