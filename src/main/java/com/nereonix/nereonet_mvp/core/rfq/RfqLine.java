package com.nereonix.nereonet_mvp.core.rfq;

import java.math.BigDecimal;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

// One item an RFQ asks prices for (P44): a copy of a line of the vessel order as it was when the RFQ was sent (item
// name, unit, category, quantity), so later changes to the galley item never change what the suppliers were asked.
@Entity
@Getter
@Setter
public class RfqLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Rfq rfq;

    @ManyToOne(optional = false)
    private Item item;

    @Column(nullable = false, length = Item.NAME_MAX_LENGTH)
    private String itemName;

    @Column(nullable = false, length = Item.UNIT_MAX_LENGTH)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemCategory category;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;
}
