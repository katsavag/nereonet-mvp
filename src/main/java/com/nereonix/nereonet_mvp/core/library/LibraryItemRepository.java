package com.nereonix.nereonet_mvp.core.library;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.ship.Ship;

public interface LibraryItemRepository extends JpaRepository<LibraryItem, Long> {

    List<LibraryItem> findAllByShipAndRetiredFalseAndCurrentVersionIsNotNullOrderByTitleAsc(Ship ship);

    Optional<LibraryItem> findByIdAndShipAndRetiredFalseAndCurrentVersionIsNotNull(Long id, Ship ship);

    boolean existsByShip(Ship ship);

    long countByShipAndRetiredFalse(Ship ship);

    // The items itemsByCategory lists: the ship page's Library tab.
    long countByShipAndRetiredFalseAndCurrentVersionIsNotNull(Ship ship);

    // The Master's home page: Required items of the ship this user has not acknowledged in their current version.
    @Query("""
            select count(i) from LibraryItem i
            where i.ship = :ship and i.required = true and i.retired = false and i.currentVersion is not null
              and not exists (select a from LibraryAcknowledgement a
                              where a.version = i.currentVersion and a.user.email = :email)""")
    long countRequiredNotAcknowledgedBy(Ship ship, String email);
}
