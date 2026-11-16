package com.nereonix.nereonet_mvp.core.order;

import java.time.Instant;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * The office's decision on one point of an order's invoice comparison (InvoiceComparison): a difference of a line
 * accepted with a note, or an item to review confirmed or changed. itemKey names the point ("diff:o12" a line,
 * "match:45" an invoice line's product, "check:Page 1: ..." a failed check); one decision per point, the latest.
 * detail is what the point was when decided (a difference's words and numbers): a difference that changes afterwards
 * (a value corrected) needs a new decision. Table invoice_decision, Hibernate creates it.
 */
@Entity
@Getter
@Setter
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_invoice_decision_key", columnNames = {"order_id", "item_key"}))
public class InvoiceDecision {

    public static final int KEY_MAX_LENGTH = 300;
    public static final int DETAIL_MAX_LENGTH = 500;
    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ShipOrder order;

    @Column(name = "item_key", nullable = false, length = KEY_MAX_LENGTH)
    private String itemKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceDecisionKind kind;

    @Column(length = DETAIL_MAX_LENGTH)
    private String detail;

    @Column(length = NOTE_MAX_LENGTH)
    private String note;

    // Null once that user is deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User decidedBy;

    @Column(nullable = false)
    private Instant decidedAt;
}
