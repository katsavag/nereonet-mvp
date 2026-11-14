package com.nereonix.nereonet_mvp.core.rfq;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RfqQuoteRepository extends JpaRepository<RfqQuote, Long> {

    Optional<RfqQuote> findByIdAndRfqId(Long id, Long rfqId);
}
