package com.nereonix.nereonet_mvp.core.library;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipCount;
import com.nereonix.nereonet_mvp.core.user.Role;

public interface LibraryItemRepository extends JpaRepository<LibraryItem, Long> {

    List<LibraryItem> findAllByShipAndRetiredFalseAndCurrentVersionIsNotNullOrderByTitleAsc(Ship ship);

    Optional<LibraryItem> findByIdAndShipAndRetiredFalseAndCurrentVersionIsNotNull(Long id, Ship ship);

    boolean existsByShip(Ship ship);

    long countByShipAndRetiredFalse(Ship ship);

    // Per ship: Required items (not retired) whose CURRENT version not every enabled ship user on board now acknowledged.
    @Query("""
            select new com.nereonix.nereonet_mvp.core.ship.ShipCount(i.ship.id, count(i))
            from LibraryItem i
            where i.required = true and i.retired = false and i.currentVersion is not null
              and (select count(u) from User u
                   where u.ship = i.ship and u.enabled = true and u.role <> :officeRole)
                > (select count(a) from LibraryAcknowledgement a
                   where a.version = i.currentVersion
                     and a.user.ship = i.ship and a.user.enabled = true and a.user.role <> :officeRole)
            group by i.ship.id""")
    List<ShipCount> countRequiredNotAcknowledgedByShip(Role officeRole);
}
