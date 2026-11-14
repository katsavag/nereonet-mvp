package com.nereonix.nereonet_mvp.core.rfq;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.supplier.Supplier;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

// One supplier asked in an RFQ (P44), and its answer once the office enters it: the quote's currency, delivery cost,
// delivery date, validity, its lines (RfqQuoteLine, one per line offered) and optionally the supplier's own PDF. A
// sealed bid: suppliers never see each other's prices; only the office compares them.
@Entity
@Getter
@Setter
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_rfq_quote_supplier", columnNames = {"rfq_id", "supplier_id"}))
public class RfqQuote {

    public static final int REASON_MAX_LENGTH = 500;
    public static final int FILE_NAME_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Rfq rfq;

    @ManyToOne(optional = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuoteStatus status;

    // The last time the channel sent the RFQ (or tried to).
    private Instant sentAt;

    // Why the channel failed (FAILED), for the office; never shown to the supplier.
    @Column(length = REASON_MAX_LENGTH)
    private String failure;

    // The supplier's reason for declining (DECLINED), optional.
    @Column(length = REASON_MAX_LENGTH)
    private String declineReason;

    // The quote (QUOTED). The currency starts as the supplier's.
    @Column(length = 3)
    private String currency;

    @Column(precision = 12, scale = 2)
    private BigDecimal deliveryCost;

    private LocalDate deliveryDate;

    private LocalDate validUntil;

    // Who entered the answer (quote or decline) and when.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User answeredBy;

    private Instant answeredAt;

    // The supplier's own quote as a PDF, optional, kept with the RFQ (LibraryFileStorage; the name is only shown).
    @Column(length = FILE_NAME_MAX_LENGTH)
    private String fileName;

    private Long fileSize;

    @Column(length = 64)
    private String fileStoragePath;

    @OneToMany(mappedBy = "quote")
    @OrderBy("id")
    private List<RfqQuoteLine> lines = new ArrayList<>();

    public boolean hasFile() {
        return fileStoragePath != null;
    }

    public boolean isQuoted() {
        return status == QuoteStatus.QUOTED;
    }

    /** The lines' totals added up (no delivery cost). */
    public BigDecimal subtotal() {
        return lines.stream().map(RfqQuoteLine::total).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** The subtotal plus the delivery cost: what the RFQ page compares and the order shows once awarded. */
    public BigDecimal grandTotal() {
        return subtotal().add(deliveryCost == null ? BigDecimal.ZERO : deliveryCost);
    }

    /**
     * Every RFQ line offered, each in at least the quantity asked: a complete quote (only these can be marked
     * cheapest).
     */
    public boolean complete() {
        return rfq.getLines().stream().allMatch(line -> lineFor(line).map(RfqQuoteLine::coversQuantity).orElse(false));
    }

    /** The quote's line for an RFQ line, or empty when the supplier did not offer it. */
    public Optional<RfqQuoteLine> lineFor(RfqLine line) {
        return lines.stream().filter(l -> l.getLine().getId().equals(line.getId())).findFirst();
    }

    /** What the RFQ page shows: SENT becomes NO_REPLY once the reply-by date is before today. */
    public SupplierState state(LocalDate today) {
        return switch (status) {
            case SENT -> rfq.getReplyBy().isBefore(today) ? SupplierState.NO_REPLY : SupplierState.SENT;
            case QUOTED -> SupplierState.QUOTED;
            case DECLINED -> SupplierState.DECLINED;
            case FAILED -> SupplierState.FAILED;
        };
    }

    /** The status per supplier on the RFQ page (P44). tone: the status badge's (layout/parts :: status). */
    public enum SupplierState {
        SENT("info"), QUOTED("ok"), DECLINED("neutral"), NO_REPLY("warning"), FAILED("danger");

        private final String tone;

        SupplierState(String tone) {
            this.tone = tone;
        }

        public String tone() {
            return tone;
        }

        public String labelKey() {
            return "rfq.supplierStatus." + name();
        }
    }
}
