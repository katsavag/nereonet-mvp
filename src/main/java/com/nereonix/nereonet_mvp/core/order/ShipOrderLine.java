package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;

import org.hibernate.annotations.ColumnDefault;

import com.nereonix.nereonet_mvp.core.item.Item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

// One item of a ship order (P34) and its total quantity, in the item's unit. One line per item. The request lines
// that went into it point to it (RequestLine.shipOrderLine), so the order can show who asked for what.
// P50: while the order is SENT the office can change the quantity, remove the line or add one (OfficeOrderEditService).
// The line keeps what the ship sent (originalQuantity); a removed line stays, marked removed, with its last quantity.
@Entity
@Getter
@Setter
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_ship_order_line_item", columnNames = {"order_id", "item_id"}))
public class ShipOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private ShipOrder order;

    @ManyToOne(optional = false)
    private Item item;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    public static final int NOTE_MAX_LENGTH = 200;

    // The quantity the ship sent, set once when the order is sent (P50). Null while OPEN, for a line the office added,
    // and for lines sent before P50 (the office's first change then keeps the quantity it found).
    @Column(precision = 12, scale = 3)
    private BigDecimal originalQuantity;

    // Removed by the office (P50): still listed, struck through; not delivered (quantity 0 in the export).
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean removed;

    // Added by the office to a SENT order (P50); the ship never asked for it.
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean addedByOffice;

    // The office's note on its last change of this line ("Out of season, reduced"), optional.
    @Column(length = NOTE_MAX_LENGTH)
    private String changeNote;

    // Changed by the office since the ship was last told (P50): counted by the next "The office changed order 12".
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean officeChangePending;

    /** The quantity the ship sent: originalQuantity, or the quantity for a line the office never touched. */
    public BigDecimal getSentQuantity() {
        return addedByOffice ? null : originalQuantity != null ? originalQuantity : quantity;
    }

    /** The office changed the quantity: the line is still there and differs from what the ship sent. */
    public boolean isChangedByOffice() {
        return !removed && !addedByOffice && originalQuantity != null && quantity.compareTo(originalQuantity) != 0;
    }

    /** The quantity to deliver: zero once removed. */
    public BigDecimal getDeliveredQuantity() {
        return removed ? BigDecimal.ZERO : quantity;
    }
}
