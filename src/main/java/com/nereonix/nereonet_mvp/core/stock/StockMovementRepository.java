package com.nereonix.nereonet_mvp.core.stock;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// The store's history is never changed or deleted (StockMovement): this repository can only add and read.
public interface StockMovementRepository extends Repository<StockMovement, Long> {

    StockMovement save(StockMovement movement);

    List<StockMovement> findAllByItemIdOrderByAtDescIdDesc(Long itemId);

    /** When the latest movement of this type on the ship was, or null without any. */
    @Query("select max(m.at) from StockMovement m where m.item.ship.id = :shipId and m.type = :type")
    Instant lastAt(Long shipId, StockMovementType type);
}
