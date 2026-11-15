package com.nereonix.nereonet_mvp.core.stock;

import java.math.BigDecimal;
import java.time.Instant;

import com.nereonix.nereonet_mvp.core.item.Item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

// How much of one provisions item is on board now, in the item's unit. At most one row per item; an item without
// a row has quantity 0 (StockService). Only PROVISIONS items have stock: personal items never get a row. Every change
// of the quantity is also a StockMovement, the store's history.
@Entity
@Getter
@Setter
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_stock_level_item", columnNames = "item_id"))
public class StockLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    private Item item;

    // Zero or more, at most 3 decimals and 9 whole digits (StockService.isValidQuantity).
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(nullable = false)
    private Instant updatedAt;

    /** IllegalArgumentException for a quantity that is not valid (StockService.isValidQuantity). */
    public void setQuantity(BigDecimal quantity) {
        if (!StockService.isValidQuantity(quantity)) {
            throw new IllegalArgumentException("A stock quantity must be zero or more, with at most 3 decimals");
        }
        this.quantity = quantity;
    }
}
