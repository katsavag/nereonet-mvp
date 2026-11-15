package com.nereonix.nereonet_mvp.core.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeliveryInvoiceLineRepository extends JpaRepository<DeliveryInvoiceLine, Long> {

    List<DeliveryInvoiceLine> findAllByInvoiceIdOrderByPageAscIdAsc(Long invoiceId);

    List<DeliveryInvoiceLine> findAllByInvoiceIdAndPage(Long invoiceId, int page);

    // A page removed: the lines of the pages after it move up by one.
    @Modifying(flushAutomatically = true)
    @Query("update DeliveryInvoiceLine l set l.page = l.page - 1 where l.invoice.id = :invoiceId and l.page > :page")
    int movePagesUp(Long invoiceId, int page);
}
