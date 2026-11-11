package com.nereonix.nereonet_mvp.core.request;

import java.math.BigDecimal;

import com.nereonix.nereonet_mvp.core.item.Item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

// One item and its quantity (in the item's unit) in an item request.
@Entity
@Getter
@Setter
public class RequestLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private ItemRequest request;

    @ManyToOne(optional = false)
    private Item item;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;
}
