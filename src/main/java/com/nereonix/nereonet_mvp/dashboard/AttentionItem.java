package com.nereonix.nereonet_mvp.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.nereonix.nereonet_mvp.core.alert.Alert;
import com.nereonix.nereonet_mvp.core.alert.AlertKind;
import com.nereonix.nereonet_mvp.core.alert.AlertLevels;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.vetting.InspectionState;
import com.nereonix.nereonet_mvp.core.vetting.ObservationType;
import com.nereonix.nereonet_mvp.core.vetting.SireReportState;
import com.nereonix.nereonet_mvp.dashboard.DashboardQueries.FleetShip;

/**
 * One row of the Overview's "Needs attention" card, one record per kind (AttentionType). NeedsAttention builds them.
 */
public sealed interface AttentionItem {

    AttentionType type();

    Long id();

    /** The active ships it concerns: one, or for an announcement the ships of those who have not acknowledged. */
    Set<Long> shipIds();

    /** Orders the items of one type, smallest first: the most overdue or the oldest. */
    long sortKey();

    /** The item as an alert the office can snooze, or null for the kinds that cannot be. */
    default Alert alert() {
        return null;
    }

    /** The date that drives the item (an expiry, a deadline), or null: the date sort and the Next 30 days card. */
    default LocalDate dueDate() {
        return null;
    }

    /** An expired certificate, or one expiring within 30 days (type tells which); daysLeft is negative once expired. */
    record CertificateItem(AttentionType type, Long id, Long userId, String certificateType,
                           LocalDate expiresOn, long daysLeft, FleetShip ship, String fullName, Rank rank)
            implements AttentionItem {

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return expiresOn.toEpochDay();
        }

        public Alert alert() {
            return Alert.of(type == AttentionType.CERTIFICATE_EXPIRED ? AlertKind.CERTIFICATE_EXPIRED
                    : AlertKind.CERTIFICATE_EXPIRING, id, AlertLevels.ofDaysLeft(daysLeft));
        }

        public LocalDate dueDate() {
            return expiresOn;
        }
    }

    /**
     * A seafarer on board (enabled, with a rank, on an active vessel) missing required documents, an expired one
     * included: missing of required, and the types (most urgent first). id: the user. Most missing first.
     */
    record DocumentsItem(Long id, String fullName, Rank rank, FleetShip ship, long missing, long required,
                         List<String> types) implements AttentionItem {

        public AttentionType type() {
            return AttentionType.DOCUMENTS_MISSING;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return -missing;
        }

        public Alert alert() {
            return Alert.of(AlertKind.DOCUMENTS_MISSING, id, (int) -missing);
        }
    }

    /**
     * A vessel's SIRE 2.0 item that needs attention, each linking to the vessel's SIRE page.
     * SIRE_COMMENTS: the operator comments are due on date (daysLeft, negative once overdue); id the inspection.
     * SIRE_REPORT: the report is current until date (state ENDING or NOT_CURRENT); id the inspection.
     * SIRE_OBSERVATION: an open observation (question, observationType) past its target date; id the observation.
     */
    record SireItem(AttentionType type, Long id, FleetShip ship, LocalDate date, long daysLeft,
                    SireReportState state, String question, ObservationType observationType, int level)
            implements AttentionItem {

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return date.toEpochDay();
        }

        public Alert alert() {
            return Alert.of(switch (type) {
                case SIRE_COMMENTS -> AlertKind.SIRE_COMMENTS;
                case SIRE_REPORT -> AlertKind.SIRE_REPORT;
                default -> AlertKind.SIRE_OBSERVATION;
            }, id, level);
        }

        public LocalDate dueDate() {
            return date;
        }
    }

    /**
     * A vessel's RightShip standing that needs attention. VETTING_INSPECTION: the inspection is expired,
     * unacceptable or expiring (state; validUntil and daysLeft, negative once expired, when there is a date).
     * VETTING_SCORE: the Safety Score is 2 or below (score). id: the vessel's current ShipRightShipStatus row.
     */
    record VettingItem(AttentionType type, Long id, FleetShip ship, InspectionState state, LocalDate validUntil,
                       long daysLeft, Integer score, int level) implements AttentionItem {

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            if (type == AttentionType.VETTING_SCORE) {
                return score;
            }
            return validUntil == null ? Long.MIN_VALUE : validUntil.toEpochDay();
        }

        public Alert alert() {
            return Alert.of(type == AttentionType.VETTING_SCORE ? AlertKind.VETTING_SCORE : AlertKind.VETTING_INSPECTION,
                    ship.id(), level);
        }

        public LocalDate dueDate() {
            return type == AttentionType.VETTING_INSPECTION ? validUntil : null;
        }
    }

    /** An order a ship has sent and the office never exported; sentBy is null when that user was deleted. */
    record OrderItem(Long id, FleetShip ship, String sentBy, Rank rank, Instant sentAt, long lines)
            implements AttentionItem {

        public AttentionType type() {
            return AttentionType.ORDER_NOT_EXPORTED;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return sentAt == null ? Long.MIN_VALUE : sentAt.getEpochSecond();
        }
    }

    /**
     * An order received with a read invoice, until an office user marks it matched: diffLines lines differ (0:
     * all agree, to confirm). Most differences first, then the oldest received.
     */
    record InvoiceItem(Long id, FleetShip ship, Instant receivedAt, int diffLines) implements AttentionItem {

        public AttentionType type() {
            return AttentionType.ORDER_INVOICE;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return -diffLines * 10_000_000_000L + (receivedAt == null ? 0 : receivedAt.getEpochSecond());
        }
    }

    /** An order received on board with lines short, until an office user marks the shortage handled. */
    record ShortageItem(Long id, FleetShip ship, Instant receivedAt, int shortLines) implements AttentionItem {

        public AttentionType type() {
            return AttentionType.ORDER_SHORTAGE;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return receivedAt == null ? Long.MIN_VALUE : receivedAt.getEpochSecond();
        }
    }

    /**
     * An open request for quotes: RFQ_READY with quotes from at least two suppliers (the oldest request first),
     * or RFQ_PAST_REPLY_BY when the reply-by date has passed with fewer (the longest overdue first). quoted: suppliers
     * who quoted; waiting: suppliers not yet answered.
     */
    record RfqItem(AttentionType type, Long id, Long orderId, FleetShip ship, LocalDate replyBy, Instant sentAt,
                   long quoted, long waiting) implements AttentionItem {

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            if (type == AttentionType.RFQ_PAST_REPLY_BY) {
                return replyBy.toEpochDay();
            }
            return sentAt == null ? Long.MIN_VALUE : sentAt.getEpochSecond();
        }

        public LocalDate dueDate() {
            return type == AttentionType.RFQ_PAST_REPLY_BY ? replyBy : null;
        }

        /** The RFQ's page, where the comparison and the quotes are. */
        public String url() {
            return "/office/orders/" + orderId + "/rfqs/" + id;
        }
    }

    /** A pending crew invitation: expired, or sent (last) more than 48 hours ago. */
    record InvitationItem(Long id, FleetShip ship, String fullName, Rank rank, Instant sentAt,
                          Instant expiresAt, boolean expired) implements AttentionItem {

        public AttentionType type() {
            return AttentionType.INVITATION;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return expiresAt.getEpochSecond();
        }

        public LocalDate dueDate() {
            return LocalDate.ofInstant(expiresAt, ZoneOffset.UTC);
        }
    }

    /** An announcement not acknowledged by everyone. fleetWide: sent to all ships; otherwise target is its one ship
     *  (when active), or null when it went to targetShips ships. */
    record AnnouncementItem(Long id, String title, boolean fleetWide, FleetShip target, int targetShips,
                            Instant sentAt, long recipients, long acknowledged, Set<Long> shipIds)
            implements AttentionItem {

        public AttentionType type() {
            return AttentionType.ANNOUNCEMENT;
        }

        public long notAcknowledged() {
            return recipients - acknowledged;
        }

        public long sortKey() {
            return sentAt.getEpochSecond();
        }
    }

    /** A Required library item whose current version not everyone on board has acknowledged. */
    record LibraryAttentionItem(Long id, String title, FleetShip ship, String versionLabel,
                                Instant publishedAt, long onBoard, long acknowledged) implements AttentionItem {

        public AttentionType type() {
            return AttentionType.LIBRARY_ITEM;
        }

        public long notAcknowledged() {
            return onBoard - acknowledged;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return publishedAt.getEpochSecond();
        }
    }

    /** Most urgent type first, then the most overdue or oldest. */
    Comparator<AttentionItem> URGENCY = Comparator
            .comparing(AttentionItem::type)
            .thenComparingLong(AttentionItem::sortKey)
            .thenComparing(AttentionItem::id);

    /** By the date that drives the item, soonest first; items without one last, in urgency order. */
    Comparator<AttentionItem> BY_DATE = Comparator
            .comparing(AttentionItem::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(URGENCY);
}
