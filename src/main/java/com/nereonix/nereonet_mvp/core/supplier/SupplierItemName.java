package com.nereonix.nereonet_mvp.core.supplier;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A supplier's name for one of a vessel's items, learned from a delivery: the invoice's description, normalised
 * (InvoiceMatcher.key), is that item, and one invoice unit is packFactor of the item's unit (null: the same unit, or
 * not worked out). Saved when the Chief cook chooses the product of an invoice line, and when the Master approves a
 * count with an uncertain match the cook confirmed (the office may still correct it). The next invoice of that
 * supplier with the same description matches the item automatically. supplier: one of the suppliers (core/supplier)
 * when the invoice's supplier name is one of them; otherwise null, and supplierName (normalised, the name as read)
 * keeps it.
 */
@Entity
@Getter
@Setter
@Table(indexes = @Index(name = "ix_supplier_item_name_description", columnList = "description"))
public class SupplierItemName {

    public static final int DESCRIPTION_MAX_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Supplier supplier;

    @Column(nullable = false, length = 200)
    private String supplierName;

    @Column(nullable = false, length = DESCRIPTION_MAX_LENGTH)
    private String description;

    @ManyToOne(optional = false)
    private Item item;

    @Column(precision = 15, scale = 6)
    private BigDecimal packFactor;

    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User createdBy;

    @Column(nullable = false)
    private Instant createdAt;
}
