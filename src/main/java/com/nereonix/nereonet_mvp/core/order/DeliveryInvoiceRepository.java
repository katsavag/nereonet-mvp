package com.nereonix.nereonet_mvp.core.order;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryInvoiceRepository extends JpaRepository<DeliveryInvoice, Long> {

    Optional<DeliveryInvoice> findByOrderId(Long orderId);
}
