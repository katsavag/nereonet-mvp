package com.nereonix.nereonet_mvp.core.stock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.item.ItemCategory;

public interface StockLevelRepository extends JpaRepository<StockLevel, Long> {

    Optional<StockLevel> findByItemId(Long itemId);

    List<StockLevel> findAllByItemShipId(Long shipId);

    boolean existsByItemShipId(Long shipId);

    /** Active items of the category on the ship of the user with this email whose quantity is below the threshold, an
     *  item without a row included (quantity 0): the Chief cook's Low plus Out of stock (P52). One query. */
    @Query("""
            select count(i) from Item i left join StockLevel l on l.item = i
            where i.category = :category and i.active = true
              and i.ship = (select u.ship from User u where u.email = :email)
              and (l.id is null or l.quantity <= 0 or l.quantity < :threshold)""")
    long countBelowOnShipOf(ItemCategory category, BigDecimal threshold, String email);
}
