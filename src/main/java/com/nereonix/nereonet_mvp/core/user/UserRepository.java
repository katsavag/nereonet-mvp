package com.nereonix.nereonet_mvp.core.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.ship.Ship;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByRole(Role role);

    boolean existsByEmail(String email);

    List<User> findAllByRoleOrderByFullNameAsc(Role role);

    // Every user on the ship, enabled or not: the Crew tab of the ship page.
    long countByShip(Ship ship);

    List<User> findAllByShipAndRoleAndEnabledTrue(Ship ship, Role role);

    List<User> findAllByShipAndRoleNotAndEnabledTrue(Ship ship, Role role);

    List<User> findAllByShipAndRoleNotAndEnabledTrueOrderByFullNameAsc(Ship ship, Role role);

    long countByShipAndRoleNotAndEnabledTrue(Ship ship, Role role);

    // Enabled users of one ship: Crew on board on the Master's home page.
    long countByShipAndEnabledTrue(Ship ship);

    List<User> findAllByShipActiveTrueAndRoleNotAndEnabledTrue(Role role);

    // The crew of a chosen set of ships (an announcement to selected ships).
    List<User> findAllByShipInAndRoleNotAndEnabledTrue(Collection<Ship> ships, Role role);

    // Enabled crew with a rank on the active ships, each with their ship, in one query: whose required documents
    // the Overview checks.
    @Query("""
            select u from User u join fetch u.ship s
            where s.active = true and u.enabled = true and u.role <> :office and u.rank is not null
            order by u.id""")
    List<User> findRankedCrewOnActiveShips(Role office);

    // Users by id with their ship, in one query (the Crew list's page: a fixed number of queries).
    @Query("select u from User u left join fetch u.ship where u.id in :ids")
    List<User> findAllWithShipByIdIn(Collection<Long> ids);
}
