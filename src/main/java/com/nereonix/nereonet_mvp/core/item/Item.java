package com.nereonix.nereonet_mvp.core.item;

import java.time.Instant;

import com.nereonix.nereonet_mvp.core.ship.Ship;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

// A galley item of one ship, e.g. "Rice", kg. Never deleted: inactive items stay in the office list
// but are not counted or requested. The name is unique per ship, ignoring case (checked by the item service).
@Entity
@Getter
@Setter
public class Item {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int UNIT_MAX_LENGTH = 20;
    public static final int CATEGORY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Ship ship;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    // A short unit such as kg, lt or pcs.
    @Column(nullable = false, length = UNIT_MAX_LENGTH)
    private String unit;

    // Free text, e.g. "Dry goods". Null when not set.
    @Column(length = CATEGORY_MAX_LENGTH)
    private String category;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
