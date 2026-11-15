package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * The supplier's invoice of an order as read from its photographed pages (P62): one per order. Its pages are the
 * order's receipt files with an invoice page number (OrderReceiptFile.invoicePage); its lines are DeliveryInvoiceLine.
 * What was read and what the checks found (InvoiceChecks: checkNotes, referenceWarning, the lines' flags) is kept for
 * the office (P64); the ship sees only what it can act on. Re-reading replaces the lines while the order is CONFIRMED,
 * never after it is CLOSED. Every field but the order and the status is null until read.
 */
@Entity
@Getter
@Setter
public class DeliveryInvoice {

    public static final int CHECK_NOTES_MAX_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(unique = true)
    private ShipOrder order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryInvoiceStatus status;

    @Column(length = 200)
    private String supplierName;

    @Column(length = 60)
    private String invoiceNumber;

    private LocalDate invoiceDate;

    // The customer's order reference as the invoice writes it ("SR-000052").
    @Column(length = 60)
    private String orderReference;

    @Column(precision = 14, scale = 2)
    private BigDecimal net;

    @Column(precision = 14, scale = 2)
    private BigDecimal vat;

    @Column(precision = 14, scale = 2)
    private BigDecimal total;

    // When the reading started (a reading that never finished, after a restart, is given up after a while).
    private Instant startedAt;

    private Instant readAt;

    // Who pressed "Done: start counting" or "Read again". Null once that user is deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User readBy;

    // The checks that failed, one per line, in English (for the office, P64).
    @Column(length = CHECK_NOTES_MAX_LENGTH)
    private String checkNotes;

    // The invoice's order reference does not match the order, or there is none (for the office, P64).
    @Column(length = 300)
    private String referenceWarning;

    // The invoice clearly names another order: the ship is told ("This invoice says order SR-000052...").
    private Boolean otherOrder;

    public boolean isOtherOrder() {
        return Boolean.TRUE.equals(otherOrder);
    }
}
