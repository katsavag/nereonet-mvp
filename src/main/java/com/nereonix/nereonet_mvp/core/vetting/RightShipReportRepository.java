package com.nereonix.nereonet_mvp.core.vetting;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RightShipReportRepository extends JpaRepository<RightShipReport, Long> {

    /** A vessel's reports, newest first: the first is the official document. */
    List<RightShipReport> findAllByShipIdOrderByDocumentDateDescUploadedAtDescIdDesc(Long shipId);

    Optional<RightShipReport> findByIdAndShipId(Long id, Long shipId);

    boolean existsByShipId(Long shipId);
}
