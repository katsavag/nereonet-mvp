package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * One line of a delivery invoice as read (P62). quantity, unitPrice and amount are the values after the checks
 * (InvoiceChecks: a recovered one is flagged corrected); readQuantity, readUnitPrice and readAmount what the page
 * gave (null: unreadable), rawText the row as the OCR wrote it. A line whose quantity was not read (quantityNotRead)
 * has no quantity: the count shows "Invoice: not read" (P63).
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

    public boolean isFlagged() {
        return corrected || needsCheck || quantityNotRead;
    }
}
