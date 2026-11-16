package com.nereonix.nereonet_mvp.core.library;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;

public interface LibraryAcknowledgementRepository extends JpaRepository<LibraryAcknowledgement, Long> {

    Optional<LibraryAcknowledgement> findByVersionAndUserEmail(LibraryItemVersion version, String email);

    /** Ids of the items whose CURRENT version this user acknowledged. */
    @Query("select a.version.item.id from LibraryAcknowledgement a"
            + " where a.user.email = :email and a.version = a.version.item.currentVersion")
    Set<Long> findItemIdsAcknowledgedInCurrentVersion(String email);

    /** One page of the acknowledgements of this version by the crew on the ship now, by full name ("Read"). */
    @Query(value = """
            select a from LibraryAcknowledgement a join fetch a.user u
            where a.version = :version and u.ship = :ship and u.enabled = true and u.role <> :officeRole
            order by u.fullName asc, u.id asc""",
            countQuery = """
            select count(a) from LibraryAcknowledgement a
            where a.version = :version and a.user.ship = :ship and a.user.enabled = true
              and a.user.role <> :officeRole""")
    Page<LibraryAcknowledgement> findOnBoardByVersion(LibraryItemVersion version, Ship ship, Role officeRole,
                                                      Pageable pageable);

    /** One page of the crew on the ship now who have not acknowledged this version, by full name ("Not read yet"). */
    @Query(value = """
            select u from User u
            where u.ship = :ship and u.enabled = true and u.role <> :officeRole
              and not exists (select a from LibraryAcknowledgement a where a.version = :version and a.user = u)
            order by u.fullName asc, u.id asc""",
            countQuery = """
            select count(u) from User u
            where u.ship = :ship and u.enabled = true and u.role <> :officeRole
              and not exists (select a from LibraryAcknowledgement a where a.version = :version and a.user = u)""")
    Page<User> findOnBoardWithout(LibraryItemVersion version, Ship ship, Role officeRole, Pageable pageable);

    /** Per item of the ship: acknowledgements of its CURRENT version by enabled ship users who are on the ship now. */
    @Query("""
            select new com.nereonix.nereonet_mvp.core.library.ItemCount(a.version.item.id, count(a))
            from LibraryAcknowledgement a
            where a.version.item.ship = :ship and a.version = a.version.item.currentVersion
              and a.user.ship = :ship and a.user.enabled = true and a.user.role <> :officeRole
            group by a.version.item.id""")
    List<ItemCount> countCurrentByItem(Ship ship, Role officeRole);
}
