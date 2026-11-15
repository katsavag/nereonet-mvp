package com.nereonix.nereonet_mvp.core.supplier;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierItemNameRepository extends JpaRepository<SupplierItemName, Long> {

    // The names learned for this description on a vessel (the items are the vessel's); the supplier is checked after.
    List<SupplierItemName> findAllByItemShipIdAndDescription(Long shipId, String description);
}
