package com.nereonix.nereonet_mvp.core.library;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nereonix.nereonet_mvp.core.ship.Ship;

public interface LibraryItemRepository extends JpaRepository<LibraryItem, Long> {

    List<LibraryItem> findAllByShipAndRetiredFalseAndCurrentVersionIsNotNullOrderByTitleAsc(Ship ship);

    Optional<LibraryItem> findByIdAndShipAndRetiredFalseAndCurrentVersionIsNotNull(Long id, Ship ship);

    boolean existsByShip(Ship ship);

    long countByShipAndRetiredFalse(Ship ship);

    // The items itemsByCategory lists: the ship page's Library tab.
    long countByShipAndRetiredFalseAndCurrentVersionIsNotNull(Ship ship);
}
