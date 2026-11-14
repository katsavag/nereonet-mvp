package com.nereonix.nereonet_mvp.core.order;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderReceiptFileRepository extends JpaRepository<OrderReceiptFile, Long> {

    List<OrderReceiptFile> findAllByOrderIdOrderByIdAsc(Long orderId);

    long countByOrderId(Long orderId);

    Optional<OrderReceiptFile> findByIdAndOrderId(Long id, Long orderId);
}
