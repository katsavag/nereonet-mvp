package com.nereonix.nereonet_mvp.core.ship;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipRepository extends JpaRepository<Ship, Long> {

    boolean existsByImoNumber(String imoNumber);

    Optional<Ship> findByImoNumber(String imoNumber);

    List<Ship> findAllByOrderByNameAsc();

    List<Ship> findAllByActiveTrueOrderByNameAsc();
}
