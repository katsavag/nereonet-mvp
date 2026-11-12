package com.nereonix.nereonet_mvp.core.order;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Getter;
import lombok.Setter;

// The consolidated order of a ship (P34): the approved requests added up, one line per item. A ship has at most one
// OPEN order (ShipOrderService.addApprovedRequest finds or creates it). The ship sends it to the office (SENT), the
// office confirms port and delivery date (CONFIRMED), and it is closed once delivered (CLOSED).
// While OPEN the Master can change a line's quantity or remove a line; once SENT it is read-only (P35).
@Entity
@Getter
@Setter
public class ShipOrder {

    public static final int PORT_MAX_LENGTH = 100;
    public static final int NOTE_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Ship ship;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipOrderStatus status;

    // The port the ship asks delivery at, and the date it needs the items by. Set when sending.
    @Column(length = PORT_MAX_LENGTH)
    private String requestedPort;

    private LocalDate neededBy;

    // The Master's note to the office (P35), optional. Set when sending.
    @Column(length = NOTE_MAX_LENGTH)
    private String officeNote;

    // Null until SENT, or when the user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User sentBy;

    private Instant sentAt;

    // Set by the office when confirming.
    @Column(length = PORT_MAX_LENGTH)
    private String confirmedPort;

    private LocalDate deliveryDate;

    // Null until CONFIRMED, or when the user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User confirmedBy;

    // Last export by the office. Null until exported.
    private Instant exportedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "order")
    @OrderBy("id")
    private List<ShipOrderLine> lines = new ArrayList<>();
}
