package com.nereonix.nereonet_mvp.core.ship;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipRepository extends JpaRepository<Ship, Long> {

    boolean existsByImoNumber(String imoNumber);

    List<Ship> findAllByOrderByNameAsc();

    List<Ship> findAllByActiveTrueOrderByNameAsc();
}
