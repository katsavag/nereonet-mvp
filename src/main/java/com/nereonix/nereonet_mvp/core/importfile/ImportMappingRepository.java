package com.nereonix.nereonet_mvp.core.importfile;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportMappingRepository extends JpaRepository<ImportMapping, Long> {

    Optional<ImportMapping> findByKindAndHeaderKey(String kind, String headerKey);
}
