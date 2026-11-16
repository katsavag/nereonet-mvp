package com.nereonix.nereonet_mvp.core.order;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.ColumnDefault;
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

// The consolidated order of a ship: the approved requests added up, one line per item. A ship has at most one
// OPEN order (ShipOrderService.addApprovedRequest finds or creates it). The ship sends it to the office (SENT), the
// office schedules the delivery, port and date (CONFIRMED, shown as "Delivery scheduled"), and the Master confirms receipt
// on board with the delivery receipt (CLOSED, "Received"); only the vessel closes an order.
// While OPEN the Master can change a line's quantity or remove a line; once SENT it is read-only for the ship,
// and the office can change its lines until it confirms the delivery.
@Entity
@Getter
@Setter
public class ShipOrder {

    public static final int PORT_MAX_LENGTH = 100;
    public static final int NOTE_MAX_LENGTH = 1000;
    /** The cook's note to the Master, and the Master's note sending a count back. */
    public static final int COUNT_NOTE_MAX_LENGTH = 500;

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

    // The Master's note to the office, optional. Set when sending.
    @Column(length = NOTE_MAX_LENGTH)
    private String officeNote;

    // Null until SENT, or when the user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User sentBy;

    private Instant sentAt;

    // Set by the office when scheduling the delivery (hence "confirmed" in the names).
    @Column(length = PORT_MAX_LENGTH)
    private String confirmedPort;

    private LocalDate deliveryDate;

    // Null until CONFIRMED, or when the user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User confirmedBy;

    // The office's last change of the lines that the ship has not been told about yet; null when there is none.
    // "Done editing", 10 minutes without changes, or confirming the delivery tells the Master and the Chief cook.
    private Instant officeEditedAt;

    // The receipt on board. When the items arrived, as the Master entered it (UTC); who confirmed it, and when.
    // Null until CLOSED (receivedBy also once that user is deleted).
    private Instant receivedAt;

    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User receivedBy;

    private Instant receiptConfirmedAt;

    // The lines received short (less than ordered, "Not delivered" included), counted when the receipt is confirmed.
    @ColumnDefault("0")
    @Column(nullable = false)
    private int shortLines;

    // An office user marked the shortages handled, with a note (required). Until then the order is in the Overview's
    // Needs attention.
    private Instant shortageHandledAt;

    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User shortageHandledBy;

    @Column(length = NOTE_MAX_LENGTH)
    private String shortageNote;

    // The Chief cook's count of the delivery, while CONFIRMED (DeliveryCountService). Null is NONE (orders from
    // before P61, or nobody counted). All nullable: Hibernate adds the columns to an existing table.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DeliveryCountStatus deliveryCount;

    // The cook who counted last (saved or sent), and when the count was last saved and last sent to the Master.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User countedBy;

    private Instant countUpdatedAt;

    private Instant countSentAt;

    // The cook's note to the Master (optional), and the Master's note when sending the count back (required then).
    @Column(length = COUNT_NOTE_MAX_LENGTH)
    private String countNote;

    @Column(length = COUNT_NOTE_MAX_LENGTH)
    private String countReturnNote;

    private Instant countReturnedAt;

    // The office's comparison of order, delivery and invoice, for an order received with a read invoice (null:
    // none, the shortage flow applies). invoiceDiffLines: the lines that differ (InvoiceComparison), kept up to
    // date by InvoiceComparisonService for the Overview and the list; who marked it matched, and when.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InvoiceMatchStatus invoiceStatus;

    @ColumnDefault("0")
    @Column(nullable = false)
    private int invoiceDiffLines;

    private Instant invoiceMatchedAt;

    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User invoiceMatchedBy;

    // Last export by the office. Null until exported.
    private Instant exportedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "order")
    @OrderBy("id")
    private List<ShipOrderLine> lines = new ArrayList<>();

    /** The delivery count's state: NONE when nothing was counted. */
    public DeliveryCountStatus getDeliveryCountStatus() {
        return deliveryCount == null ? DeliveryCountStatus.NONE : deliveryCount;
    }

    /** The cook sent the count and the Master has not decided yet: "Counted by the Chief cook, to approve". */
    public boolean isCountToApprove() {
        return status == ShipOrderStatus.CONFIRMED && deliveryCount == DeliveryCountStatus.SENT_TO_MASTER;
    }

    /** Received after the Master approved the cook's count: the receipt says "Counted by". */
    public boolean isCountApproved() {
        return deliveryCount == DeliveryCountStatus.APPROVED;
    }

    /**
     * Received with lines short, and no office user has marked them handled yet. An order with a read
     * invoice is handled by matching the invoice instead.
     */
    public boolean isShortageOpen() {
        return status == ShipOrderStatus.CLOSED && shortLines > 0 && shortageHandledAt == null && invoiceStatus == null;
    }

    /** Received with a read invoice the office has not marked matched yet. */
    public boolean isInvoiceToMatch() {
        return status == ShipOrderStatus.CLOSED && invoiceStatus == InvoiceMatchStatus.TO_MATCH;
    }
}
