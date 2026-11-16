package com.nereonix.nereonet_mvp.core.ship;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShipRepository extends JpaRepository<Ship, Long> {

    boolean existsByImoNumber(String imoNumber);

    Optional<Ship> findByImoNumber(String imoNumber);

    List<Ship> findAllByOrderByNameAsc();

    List<Ship> findAllByActiveTrueOrderByNameAsc();

    // One page of the Vessels list by RightShip Safety Score, lowest or highest first (not rated last either way),
    // then by name.
    @Query(value = """
            select s from Ship s left join ShipRightShipStatus r on r.ship = s and r.current = true
            order by r.safetyScore asc nulls last, s.name asc, s.id asc""",
            countQuery = "select count(s) from Ship s")
    Page<Ship> findAllByScoreAsc(Pageable pageable);

    @Query(value = """
            select s from Ship s left join ShipRightShipStatus r on r.ship = s and r.current = true
            order by r.safetyScore desc nulls last, s.name asc, s.id asc""",
            countQuery = "select count(s) from Ship s")
    Page<Ship> findAllByScoreDesc(Pageable pageable);
}
