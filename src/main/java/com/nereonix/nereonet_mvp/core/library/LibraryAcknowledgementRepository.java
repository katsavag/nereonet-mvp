package com.nereonix.nereonet_mvp.core.library;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;

public interface LibraryAcknowledgementRepository extends JpaRepository<LibraryAcknowledgement, Long> {

    Optional<LibraryAcknowledgement> findByVersionAndUserEmail(LibraryItemVersion version, String email);

    /** Ids of the items whose CURRENT version this user acknowledged. */
    @Query("select a.version.item.id from LibraryAcknowledgement a"
            + " where a.user.email = :email and a.version = a.version.item.currentVersion")
    Set<Long> findItemIdsAcknowledgedInCurrentVersion(String email);

    List<LibraryAcknowledgement> findAllByVersion(LibraryItemVersion version);

    /** Per item of the ship: acknowledgements of its CURRENT version by enabled ship users who are on the ship now. */
    @Query("""
            select new com.nereonix.nereonet_mvp.core.library.ItemCount(a.version.item.id, count(a))
            from LibraryAcknowledgement a
            where a.version.item.ship = :ship and a.version = a.version.item.currentVersion
              and a.user.ship = :ship and a.user.enabled = true and a.user.role <> :officeRole
            group by a.version.item.id""")
    List<ItemCount> countCurrentByItem(Ship ship, Role officeRole);
}
