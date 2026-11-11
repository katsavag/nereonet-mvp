package com.nereonix.nereonet_mvp.core.ship;

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
import lombok.Getter;
import lombok.Setter;

// Ships are never deleted. An inactive ship stays in the list but cannot be picked for new crew.
@Entity
@Getter
@Setter
public class Ship {

    public static final int PARTICULARS_MAX_LENGTH = 20_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The 7 digits only (see ImoNumber), shown as "IMO 9074729". Never changes after creation.
    @Column(nullable = false, unique = true, length = 7, updatable = false)
    private String imoNumber;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private ShipType type;

    // Country of registry as an ISO 3166 code, e.g. "GR".
    private String flag;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Plain text shown at the top of the ship's library.
    @Column(length = PARTICULARS_MAX_LENGTH)
    private String particularsText;

    // Null when never updated, or when that user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User particularsUpdatedBy;

    private Instant particularsUpdatedAt;
}
