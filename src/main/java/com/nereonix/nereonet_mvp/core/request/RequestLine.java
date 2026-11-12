package com.nereonix.nereonet_mvp.core.request;

import java.math.BigDecimal;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

// One line of an item request: an item of the ship's list, or (item null) a new item the Chief cook asks for that is
// not in the list yet, by name and unit. Exactly one of the two (RequestService.submit checks it). The quantity is
// in the item's unit. Before P33 every line had an item; RequestSchemaMigration drops the old not null on item_id.
@Entity
@Getter
@Setter
public class RequestLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private ItemRequest request;

    // Null for a new item.
    @ManyToOne
    private Item item;

    // Set only for a new item.
    @Column(length = Item.NAME_MAX_LENGTH)
    private String newItemName;

    // Set only for a new item, and may be null there too.
    @Column(length = Item.UNIT_MAX_LENGTH)
    private String newItemUnit;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    // The ship order line this went into when the Master approved the request (P34), so the order can show who asked
    // for what. Null until approved, and for a declined or cancelled request.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private ShipOrderLine shipOrderLine;

    /** True when the line asks for an item that is not in the ship's list yet. */
    public boolean isNewItem() {
        return item == null;
    }

    /** The item's name, or the new item's. */
    public String getName() {
        return item != null ? item.getName() : newItemName;
    }

    /** The item's unit, or the new item's (may be null). */
    public String getUnit() {
        return item != null ? item.getUnit() : newItemUnit;
    }

    /** The item's category. A new item has none yet: only the Chief cook asks for new items, and they are provisions. */
    public ItemCategory getCategory() {
        return item != null ? item.getCategory() : ItemCategory.PROVISIONS;
    }
}
