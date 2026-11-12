package com.nereonix.nereonet_mvp.core.item;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findAllByShipIdOrderByNameAsc(Long shipId);

    // The categories one ship's items use, for the Category filter of the Items tab (P19).
    @Query("select distinct i.category from Item i where i.ship.id = :shipId and i.category is not null order by i.category")
    List<String> findCategories(Long shipId);

    long countByShipId(Long shipId);

    Optional<Item> findByIdAndShipId(Long id, Long shipId);

    boolean existsByShipIdAndNameIgnoreCase(Long shipId, String name);

    boolean existsByShipIdAndNameIgnoreCaseAndIdNot(Long shipId, String name, Long id);
}
