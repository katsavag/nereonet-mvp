package com.nereonix.nereonet_mvp.core.item;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findAllByShipIdOrderByNameAsc(Long shipId);

    long countByShipId(Long shipId);

    Optional<Item> findByIdAndShipId(Long id, Long shipId);

    boolean existsByShipIdAndNameIgnoreCase(Long shipId, String name);

    boolean existsByShipIdAndNameIgnoreCaseAndIdNot(Long shipId, String name, Long id);
}
