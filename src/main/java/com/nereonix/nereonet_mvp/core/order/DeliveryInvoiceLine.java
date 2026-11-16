package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import com.nereonix.nereonet_mvp.core.item.Item;

import lombok.Getter;
import lombok.Setter;

/**
 * One line of a delivery invoice as read. quantity, unitPrice and amount are the values after the checks
 * (InvoiceChecks: a recovered one is flagged corrected); readQuantity, readUnitPrice and readAmount what the page
 * gave (null: unreadable), rawText the row as the reader gave it. A line whose quantity was not read (quantityNotRead)
 * has no quantity: the count shows "Invoice: not read".
 * The line's match to the order (InvoiceMatchService): the order line or, for an item of the vessel not on the
 * order, the item; how the invoice's unit converts to the item's (packFactor: one invoice unit is this many of the
 * item's unit; null when it cannot be worked out); and, for a line matched to an item not on the order, the cook's
 * count and the Master's change, in the item's unit. CHECK is the office's "match to review",
 * conversionToReview its "conversion to review".
 */
@Entity
@Getter
@Setter
public class DeliveryInvoiceLine {

    public static final int DESCRIPTION_MAX_LENGTH = 300;
    public static final int RAW_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private DeliveryInvoice invoice;

    // The page on board (1, 2, ...), as OrderReceiptFile.invoicePage.
    @Column(nullable = false)
    private int page;

    // The line's number on the invoice (No, Α/Α); null when it shows none.
    private Integer lineNumber;

    @Column(length = DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(length = 30)
    private String unit;

    @Column(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(precision = 19, scale = 3)
    private BigDecimal readQuantity;

    @Column(precision = 19, scale = 4)
    private BigDecimal readUnitPrice;

    @Column(precision = 19, scale = 2)
    private BigDecimal readAmount;

    @Column(length = RAW_MAX_LENGTH)
    private String rawText;

    // One value recovered from the other two.
    @Column(nullable = false)
    private boolean corrected;

    // "check": low confidence, or the three values do not fit and none could be recovered.
    @Column(nullable = false)
    private boolean needsCheck;

    @Column(nullable = false)
    private boolean quantityNotRead;

    private Double confidence;

    // ---- P63: the match ----------------------------------------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InvoiceMatch match;

    // The order line it is (MATCHED, CHECK, LEARNED, CHOSEN); null for an item not on the order, PICK, NOT_ORDERED.
    @ManyToOne
    private ShipOrderLine orderLine;

    // The item it is: the order line's, or a vessel item not on the order. Null for PICK and NOT_ORDERED.
    @ManyToOne
    private Item item;

    @Column(precision = 15, scale = 6)
    private BigDecimal packFactor;

    // The invoice's unit when it differs from the item's: the pack ("10 kg", null for another unit of the same
    // measure) and its name, one and many ("bag", "bags").
    @Column(length = 40)
    private String pack;

    @Column(length = 30)
    private String packUnitOne;

    @Column(length = 30)
    private String packUnitMany;

    // The factor was worked out from the description for the first time: the office reviews it.
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean conversionToReview;

    // An item not on the order: what the cook counted (the item's unit, 0: nothing arrived), his note, and the
    // Master's change of it (null: as counted).
    @Column(precision = 12, scale = 3)
    private BigDecimal countedQuantity;

    @Column(length = ShipOrderLine.NOTE_MAX_LENGTH)
    private String countNote;

    @Column(precision = 12, scale = 3)
    private BigDecimal approvedQuantity;

    // ---- P64: the office's correction of a line read wrong ------------------------------------------------------------
    // The values as read stay in readQuantity, readUnitPrice, readAmount; the description as read in descriptionRead
    // (null while the description was never changed). Who changed the line last, and when.

    @Column(length = DESCRIPTION_MAX_LENGTH)
    private String descriptionRead;

    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private com.nereonix.nereonet_mvp.core.user.User officeChangedBy;

    private java.time.Instant officeChangedAt;

    // A person corrected the quantity (the Chief cook on the count, the office on View invoice): when. Null while the
    // quantity is the reader's, or was put back to what the photo read.
    private java.time.Instant quantityCorrectedAt;

    /** The quantity is a person's, not the photo's: "(corrected, photo read 12)". */
    public boolean isQuantityCorrected() {
        return quantityCorrectedAt != null;
    }

    /**
     * A person corrects the quantity the photo read wrong, or did not read: it replaces quantity, and readQuantity
     * stays as the photo gave it. False, and nothing changed, when the line says that number already.
     */
    public boolean correctQuantity(BigDecimal value) {
        if (quantity != null && quantity.compareTo(value) == 0) {
            return false;
        }
        quantity = value;
        quantityNotRead = false;
        quantityCorrectedAt = readQuantity != null && readQuantity.compareTo(value) == 0 ? null
                : java.time.Instant.now();
        return true;
    }

    /** P64: the office corrected this line. */
    public boolean isChangedByOffice() {
        return officeChangedAt != null;
    }

    /** P63: the invoice's quantity in the item's unit; null when not read or not convertible. */
    public BigDecimal getItemQuantity() {
        return quantity == null || packFactor == null ? null
                : quantity.multiply(packFactor).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public boolean isFlagged() {
        return corrected || needsCheck || quantityNotRead;
    }
}
