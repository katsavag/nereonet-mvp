package com.nereonix.nereonet_mvp.core.vesseldata;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nereonix.nereonet_mvp.core.ship.Ship;

public interface VesselDataSnapshotRepository extends JpaRepository<VesselDataSnapshot, Long> {

    List<VesselDataSnapshot> findByShip(Ship ship);

    Optional<VesselDataSnapshot> findByShipAndEndpoint(Ship ship, VesselEndpoint endpoint);
}
