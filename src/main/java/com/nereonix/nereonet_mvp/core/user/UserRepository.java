package com.nereonix.nereonet_mvp.core.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipCount;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByRole(Role role);

    boolean existsByEmail(String email);

    List<User> findAllByRoleOrderByFullNameAsc(Role role);

    List<User> findAllByRoleNotOrderByFullNameAsc(Role role);

    List<User> findAllByShipOrderByFullNameAsc(Ship ship);

    // Every user on the ship, enabled or not: the Crew tab of the ship page.
    long countByShip(Ship ship);

    List<User> findAllByShipAndRoleAndEnabledTrue(Ship ship, Role role);

    List<User> findAllByShipAndRoleNotAndEnabledTrue(Ship ship, Role role);

    List<User> findAllByShipAndRoleNotAndEnabledTrueOrderByFullNameAsc(Ship ship, Role role);

    long countByShipAndRoleNotAndEnabledTrue(Ship ship, Role role);

    // Enabled users of one ship: Crew on board on the Master's home page.
    long countByShipAndEnabledTrue(Ship ship);

    List<User> findAllByShipActiveTrueAndRoleNotAndEnabledTrue(Role role);

    // The crew of a chosen set of ships (an announcement to selected ships, P28).
    List<User> findAllByShipInAndRoleNotAndEnabledTrue(Collection<Ship> ships, Role role);

    // Enabled users per ship (ship roles only: office users have no ship).
    @Query("""
            select new com.nereonix.nereonet_mvp.core.ship.ShipCount(u.ship.id, count(u))
            from User u where u.enabled = true and u.ship is not null group by u.ship.id""")
    List<ShipCount> countEnabledByShip();
}
