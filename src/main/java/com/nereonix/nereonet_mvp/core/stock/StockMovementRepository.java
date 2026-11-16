package com.nereonix.nereonet_mvp.core.stock;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// The store's history is never changed or deleted (StockMovement): this repository can only add and read.
public interface StockMovementRepository extends Repository<StockMovement, Long> {

    StockMovement save(StockMovement movement);

    List<StockMovement> findAllByItemIdOrderByAtDescIdDesc(Long itemId);

    /** The movements of the ship's items with one of these types, at or after start and before end, newest first
     *  (StockService.history). The item and who in the same query. */
    @Query(value = """
            select m from StockMovement m join fetch m.item i left join fetch m.by
            where i.ship.id = :shipId and m.type in :types and m.at >= :start and m.at < :end
            order by m.at desc, m.id desc""",
            countQuery = """
            select count(m) from StockMovement m
            where m.item.ship.id = :shipId and m.type in :types and m.at >= :start and m.at < :end""")
    Page<StockMovement> findOnShip(Long shipId, List<StockMovementType> types, Instant start, Instant end,
                                   Pageable pageable);

    /** When the latest movement of this type on the ship was, or null without any. */
    @Query("select max(m.at) from StockMovement m where m.item.ship.id = :shipId and m.type = :type")
    Instant lastAt(Long shipId, StockMovementType type);
}
