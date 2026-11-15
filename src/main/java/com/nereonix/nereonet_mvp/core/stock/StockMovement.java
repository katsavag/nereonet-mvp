package com.nereonix.nereonet_mvp.core.stock;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// One change of an item's stock, the store's history: a count by the Chief cook or a delivery received. Never
// changed or deleted: @Immutable (Hibernate ignores changes to a loaded row), no setters, made only with of(...), and
// StockMovementRepository has no delete. The only change is the database's own: "by" becomes null when that user is
// deleted.
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(name = "ix_stock_movement_item_at", columnList = "item_id, moved_at"))
public class StockMovement {

    public static final int NOTE_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockMovementType type;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityBefore;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityAfter;

    // Who counted or received. Null when the user was deleted.
    @ManyToOne
    @JoinColumn(name = "by_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User by;

    @Column(name = "moved_at", nullable = false)
    private Instant at;

    // Optional, e.g. "Two bags torn".
    @Column(length = NOTE_MAX_LENGTH)
    private String note;

    // The ship order whose delivery this was, for a RECEIPT; null for a COUNT.
    @ManyToOne
    @JoinColumn(name = "order_id")
    private ShipOrder order;

    /** After minus before: -3 for a count that found 3 less than the store expected. */
    public BigDecimal getDifference() {
        return quantityAfter.subtract(quantityBefore);
    }

    /** A new movement. IllegalArgumentException for a quantity that is not valid (StockService.isValidQuantity). A
     *  blank note is stored as null. */
    public static StockMovement of(Item item, StockMovementType type, BigDecimal quantityBefore,
                                   BigDecimal quantityAfter, User by, Instant at, String note, ShipOrder order) {
        if (!StockService.isValidQuantity(quantityBefore) || !StockService.isValidQuantity(quantityAfter)) {
            throw new IllegalArgumentException("A stock quantity must be zero or more, with at most 3 decimals");
        }
        StockMovement movement = new StockMovement();
        movement.item = item;
        movement.type = type;
        movement.quantityBefore = quantityBefore;
        movement.quantityAfter = quantityAfter;
        movement.by = by;
        movement.at = at;
        movement.note = note == null || note.isBlank() ? null : note.strip();
        movement.order = order;
        return movement;
    }
}
