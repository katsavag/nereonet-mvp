package com.nereonix.nereonet_mvp.core.rfq;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

// A supplier's price for one RFQ line (P44). A line the supplier did not offer has no row ("Not offered", never zero).
//   unitPrice        the price of one pack, in the quote's currency
//   quantityOffered  how many packs the supplier offers
//   packSize, packUnit  what one pack holds, e.g. 5 L; both empty: a pack is one of the item's unit
//   note             the supplier's remark ("brand X", "frozen")
// The line total is unitPrice x quantityOffered; the price per item unit (the comparison's) divides the unit price
// by the pack's content in the item's unit (Units).
@Entity
@Getter
@Setter
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_rfq_quote_line", columnNames = {"quote_id", "line_id"}))
public class RfqQuoteLine {

    public static final int NOTE_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private RfqQuote quote;

    @ManyToOne(optional = false)
    private RfqLine line;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityOffered;

    @Column(precision = 12, scale = 3)
    private BigDecimal packSize;

    @Column(length = Units.UNIT_MAX_LENGTH)
    private String packUnit;

    @Column(length = NOTE_MAX_LENGTH)
    private String note;

    /** unitPrice x quantityOffered, to the cent. */
    public BigDecimal total() {
        return unitPrice.multiply(quantityOffered).setScale(2, RoundingMode.HALF_UP);
    }

    /** What one pack holds in the line's unit: 5 for a 5 L can of an item in lt, 0.5 for 500 g of an item in kg; 1
     *  without a pack size. Null when the pack unit cannot be turned into the item's unit (refused on entry). */
    public BigDecimal packInItemUnit() {
        if (packSize == null) {
            return BigDecimal.ONE;
        }
        BigDecimal factor = Units.factor(packUnit == null ? line.getUnit() : packUnit, line.getUnit());
        return factor == null ? null : packSize.multiply(factor);
    }

    /** The price per item unit (the comparison's): the unit price divided by the pack's content. Null as above. */
    public BigDecimal pricePerItemUnit() {
        BigDecimal content = packInItemUnit();
        if (content == null || content.signum() == 0) {
            return null;
        }
        return unitPrice.divide(content, 4, RoundingMode.HALF_UP);
    }

    /** The quantity offered in the item's unit: 3 cans of 5 L are 15 lt. Null as above. */
    public BigDecimal quantityInItemUnit() {
        BigDecimal content = packInItemUnit();
        return content == null ? null : quantityOffered.multiply(content);
    }

    /** True when the supplier offers at least the quantity asked. */
    public boolean coversQuantity() {
        BigDecimal offered = quantityInItemUnit();
        return offered != null && offered.compareTo(line.getQuantity()) >= 0;
    }
}
