package com.nereonix.nereonet_mvp.core.rfq;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.order.ShipOrder;
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

// A request for quotes (P44): the office asks several suppliers for prices for the lines of a vessel order the ship
// has sent. Its number is its id ("RFQ #12"). The lines are copied from the order when it is sent; each supplier
// asked has an RfqQuote (sent, quoted, declined, failed). The office picks one quote for the whole RFQ (AWARDED) or
// withdraws it (CANCELLED). An order has at most one OPEN RFQ (RfqService locks the order to keep it so).
@Entity
@Getter
@Setter
public class Rfq {

    public static final int PORT_MAX_LENGTH = ShipOrder.PORT_MAX_LENGTH;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private ShipOrder order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RfqStatus status;

    // Where and when the items are to be delivered: prefilled from the order's requested port and needed by date.
    @Column(nullable = false, length = PORT_MAX_LENGTH)
    private String port;

    @Column(nullable = false)
    private LocalDate deliveryDate;

    // The date the suppliers are asked to answer by. After it, a supplier still SENT shows "No reply".
    @Column(nullable = false)
    private LocalDate replyBy;

    // Null when the user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User sentBy;

    @Column(nullable = false, updatable = false)
    private Instant sentAt;

    // The quote chosen, once AWARDED.
    @ManyToOne
    private RfqQuote awardedQuote;

    // Who awarded or cancelled it, and when.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User closedBy;

    private Instant closedAt;

    @OneToMany(mappedBy = "rfq")
    @OrderBy("id")
    private List<RfqLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "rfq")
    @OrderBy("id")
    private List<RfqQuote> quotes = new ArrayList<>();

    public boolean isOpen() {
        return status == RfqStatus.OPEN;
    }

    /** "RFQ #12": the number in emails, subjects and file names. */
    public String number() {
        return "RFQ #" + id;
    }
}
