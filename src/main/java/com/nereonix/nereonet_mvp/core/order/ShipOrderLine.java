package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;

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
}
