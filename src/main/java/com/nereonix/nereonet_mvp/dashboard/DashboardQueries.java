package com.nereonix.nereonet_mvp.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.rfq.QuoteStatus;
import com.nereonix.nereonet_mvp.core.rfq.RfqStatus;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The read queries of the office Overview (P09). Each method is exactly one SQL query. They return small projections,
 * never entities: the entities' many-to-one associations are eager, so loading entities would add one query per
 * related row and the page would get slower as the fleet grows.
 */
@Repository
public class DashboardQueries {

    /** An active ship, as the Overview shows it (name links to the ship page), with its crew on board (P43). */
    public record FleetShip(Long id, String name, String imoNumber, String flag, ShipType type, long crew) {
    }

    public record CertificateRow(Long id, Long userId, String type, LocalDate expiresOn, Long shipId, String fullName,
                          Rank rank) {
    }

    /**
     * An order the office works on (P36, P43): sent and never exported (an open item), sent and not yet confirmed
     * (the "Orders waiting" tile), confirmed with a delivery within the next 30 days (the "Next 30 days" card), or
     * received with lines short and the shortage not handled (P51, an open item).
     */
    public record OrderRow(Long id, Long shipId, String sentBy, Rank rank, Instant sentAt, long lines,
                           ShipOrderStatus status, Instant exportedAt, LocalDate deliveryDate, Instant receivedAt,
                           int shortLines, Instant shortageHandledAt) {

        public boolean notExported() {
            return exportedAt == null;
        }

        /** Received with lines short, the shortage not handled yet (P51). */
        public boolean shortageOpen() {
            return status == ShipOrderStatus.CLOSED && shortLines > 0 && shortageHandledAt == null;
        }
    }

    /** An open request for quotes of an active ship (P44), with how many suppliers have quoted and how many not yet
     *  answered (sent, neither quoted, declined nor failed). */
    public record RfqRow(Long id, Long orderId, Long shipId, LocalDate replyBy, Instant sentAt, long quoted,
                         long waiting) {
    }

    public record InvitationRow(Long id, Long shipId, String fullName, Rank rank, Instant expiresAt) {
    }

    /** One announcement's recipients on one ship. fleetWide: sent to all ships; targetShips: how many ships it was
     *  sent to otherwise (P28); shipId: the target when it is the only one, else null; recipientShipId the ship the
     *  recipients were on when it was sent. */
    public record AnnouncementShipRow(Long id, String title, boolean fleetWide, Integer targetShips, Long shipId,
                                      Instant sentAt, Long recipientShipId, long recipients, long acknowledged) {
    }

    public record LibraryRow(Long id, String title, Long shipId, String versionLabel, Instant publishedAt, long onBoard,
                      long acknowledged) {
    }

    /** What exists of the setup steps (P25): ships, crew (users with a ship role, pending crew invitations), library
     *  items not retired and announcements; firstShipId is the first ship added, null without a ship. */
    public record SetupRow(long ships, long crewUsers, long crewInvitations, long libraryItems, long announcements,
                           Long firstShipId) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    /** The setup checklist of the Overview, in one query (not part of load()'s). */
    SetupRow setup() {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$SetupRow(
                    (select count(s) from Ship s),
                    (select count(u) from User u where u.role <> :officeRole),
                    (select count(i) from Invitation i where i.ship is not null and i.status = :pending),
                    (select count(l) from LibraryItem l where l.retired = false),
                    (select count(a) from Announcement a),
                    (select min(s.id) from Ship s))""", SetupRow.class)
                .setParameter("officeRole", Role.OFFICE_USER)
                .setParameter("pending", Invitation.Status.PENDING)
                .getSingleResult();
    }

    /** The active ships by name, each with its enabled crew on board (ship roles), in one query (P43). */
    List<FleetShip> activeShips() {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$FleetShip(s.id, s.name, s.imoNumber,
                    s.flag, s.type,
                    (select count(u) from User u where u.ship = s and u.enabled = true and u.role <> :officeRole))
                from Ship s where s.active = true order by s.name, s.id""", FleetShip.class)
                .setParameter("officeRole", Role.OFFICE_USER)
                .getResultList();
    }

    /**
     * Certificates that expire on or before "until" (expired included), with the ship of their holder (null without
     * one). Every ship, active or not (P43: the snooze sync needs the alerts of every certificate the lists can
     * snooze; the caller keeps the active ships' ones as open items).
     */
    List<CertificateRow> certificatesExpiringUntil(LocalDate until) {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$CertificateRow(
                    c.id, u.id, c.type, c.expiresOn, s.id, u.fullName, u.rank)
                from Certificate c join c.user u left join u.ship s
                where c.expiresOn <= :until""", CertificateRow.class)
                .setParameter("until", until)
                .getResultList();
    }

    /**
     * The orders of active ships the office works on (P36, P43): sent and never exported, sent and not yet confirmed,
     * confirmed with a delivery on or before "deliveriesUntil" (today or later), or received with lines short and the
     * shortage not handled (P51), with their number of lines (lines removed by the office, P50, not counted).
     */
    List<OrderRow> orders(LocalDate today, LocalDate deliveriesUntil) {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$OrderRow(
                    o.id, s.id, u.fullName, u.rank, o.sentAt,
                    (select count(l) from ShipOrderLine l where l.order = o and l.removed = false), o.status, o.exportedAt, o.deliveryDate,
                    o.receivedAt, o.shortLines, o.shortageHandledAt)
                from ShipOrder o join o.ship s left join o.sentBy u
                where s.active = true
                  and ((o.status in :statuses and o.exportedAt is null)
                    or o.status = :sent
                    or (o.status = :confirmed and o.deliveryDate between :today and :deliveriesUntil)
                    or (o.status = :closed and o.shortLines > 0 and o.shortageHandledAt is null))""", OrderRow.class)
                .setParameter("statuses", ShipOrderService.SENT_TO_OFFICE)
                .setParameter("sent", ShipOrderStatus.SENT)
                .setParameter("confirmed", ShipOrderStatus.CONFIRMED)
                .setParameter("closed", ShipOrderStatus.CLOSED)
                .setParameter("today", today)
                .setParameter("deliveriesUntil", deliveriesUntil)
                .getResultList();
    }

    /** The open requests for quotes of active ships (P44), with their quoted and waiting suppliers counted. */
    List<RfqRow> openRfqs() {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$RfqRow(
                    r.id, o.id, s.id, r.replyBy, r.sentAt,
                    (select count(q) from RfqQuote q where q.rfq = r and q.status = :quoted),
                    (select count(q) from RfqQuote q where q.rfq = r and q.status = :waiting))
                from Rfq r join r.order o join o.ship s
                where s.active = true and r.status = :open""", RfqRow.class)
                .setParameter("quoted", QuoteStatus.QUOTED)
                .setParameter("waiting", QuoteStatus.SENT)
                .setParameter("open", RfqStatus.OPEN)
                .getResultList();
    }

    /** Pending crew invitations of active ships that expire before "before". */
    List<InvitationRow> pendingInvitationsExpiringBefore(Instant before) {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$InvitationRow(
                    i.id, s.id, i.fullName, i.rank, i.expiresAt)
                from Invitation i join i.ship s
                where i.status = :status and s.active = true and i.expiresAt < :before""", InvitationRow.class)
                .setParameter("status", Invitation.Status.PENDING)
                .setParameter("before", before)
                .getResultList();
    }

    /** Announcements sent before "sentBefore": recipients and acknowledgements per ship of the recipients. */
    List<AnnouncementShipRow> announcementsSentBefore(Instant sentBefore) {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$AnnouncementShipRow(
                    a.id, a.title, a.fleetWide, size(a.targetShips), oneTarget.id, a.createdAt, r.ship.id, count(r),
                    count(r.acknowledgedAt))
                from AnnouncementRecipient r join r.announcement a
                    left join a.targetShips oneTarget on size(a.targetShips) = 1
                where a.createdAt < :sentBefore
                group by a.id, a.title, a.fleetWide, oneTarget.id, a.createdAt, r.ship.id""", AnnouncementShipRow.class)
                .setParameter("sentBefore", sentBefore)
                .getResultList();
    }

    /**
     * Required library items of active ships whose current version was published before "publishedBefore" and
     * not every enabled ship user on board now has acknowledged (the rule of the acknowledgement overview).
     */
    List<LibraryRow> requiredItemsNotAcknowledged(Instant publishedBefore) {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$LibraryRow(
                    i.id, i.title, s.id, v.versionLabel, v.publishedAt,
                    (select count(u) from User u where u.ship = s and u.enabled = true and u.role <> :officeRole),
                    (select count(a) from LibraryAcknowledgement a
                     where a.version = v and a.user.ship = s and a.user.enabled = true and a.user.role <> :officeRole))
                from LibraryItem i join i.currentVersion v join i.ship s
                where i.required = true and i.retired = false and s.active = true and v.publishedAt < :publishedBefore
                  and (select count(u) from User u where u.ship = s and u.enabled = true and u.role <> :officeRole)
                    > (select count(a) from LibraryAcknowledgement a
                       where a.version = v and a.user.ship = s and a.user.enabled = true and a.user.role <> :officeRole)
                """, LibraryRow.class)
                .setParameter("officeRole", Role.OFFICE_USER)
                .setParameter("publishedBefore", publishedBefore)
                .getResultList();
    }
}
