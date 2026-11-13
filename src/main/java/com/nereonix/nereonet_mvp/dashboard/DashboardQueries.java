package com.nereonix.nereonet_mvp.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.vetting.InspectionOutcome;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The read queries of the office Overview (P09). Each method is exactly one SQL query. They return small projections,
 * never entities: the entities' many-to-one associations are eager, so loading entities would add one query per
 * related row and the page would get slower as the fleet grows.
 */
@Repository
public class DashboardQueries {

    /** An active ship, as the Overview shows it (name links to the ship page). */
    public record FleetShip(Long id, String name, String imoNumber) {
    }

    public record CertificateRow(Long id, Long userId, String type, LocalDate expiresOn, Long shipId, String fullName,
                          Rank rank) {
    }

    public record OrderRow(Long id, Long shipId, String sentBy, Rank rank, Instant sentAt, long lines) {
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

    /** A vessel's current RightShip standing (P40): id is the ShipRightShipStatus row. */
    public record VettingRow(Long id, Long shipId, Integer safetyScore, LocalDate inspectionValidUntil,
                             Boolean inspectionRequired, InspectionOutcome lastInspectionOutcome) {
    }

    /** What exists of the setup steps (P25): ships, crew (users with a ship role, pending crew invitations), library
     *  items not retired and announcements; firstShipId is the first ship added, null without a ship. */
    public record SetupRow(long ships, long crewUsers, long crewInvitations, long libraryItems, long announcements,
                           Long firstShipId) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    /** The setup checklist of the Overview, in one query (not part of load()'s seven). */
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

    List<FleetShip> activeShips() {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$FleetShip(s.id, s.name, s.imoNumber)
                from Ship s where s.active = true order by s.name, s.id""", FleetShip.class).getResultList();
    }

    /** Certificates of users on active ships that expire on or before "until" (expired included). */
    List<CertificateRow> certificatesExpiringUntil(LocalDate until) {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$CertificateRow(
                    c.id, u.id, c.type, c.expiresOn, s.id, u.fullName, u.rank)
                from Certificate c join c.user u join u.ship s
                where c.expiresOn <= :until and s.active = true""", CertificateRow.class)
                .setParameter("until", until)
                .getResultList();
    }

    /**
     * The current RightShip standing of active vessels (P40) whose inspection is unacceptable or valid until "until"
     * at the latest (expired included), or whose Safety Score is at most "lowScore".
     */
    List<VettingRow> vettingNeedingAttention(LocalDate until, int lowScore) {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$VettingRow(
                    r.id, s.id, r.safetyScore, r.inspectionValidUntil, r.inspectionRequired, r.lastInspectionOutcome)
                from ShipRightShipStatus r join r.ship s
                where r.current = true and s.active = true
                  and (r.inspectionValidUntil <= :until or r.lastInspectionOutcome = :unacceptable
                       or r.safetyScore <= :lowScore)""", VettingRow.class)
                .setParameter("until", until)
                .setParameter("unacceptable", InspectionOutcome.UNACCEPTABLE)
                .setParameter("lowScore", lowScore)
                .getResultList();
    }

    /** Orders active ships have sent (P36) that were never exported, with their number of lines. */
    List<OrderRow> ordersNotExported() {
        return entityManager.createQuery("""
                select new com.nereonix.nereonet_mvp.dashboard.DashboardQueries$OrderRow(
                    o.id, s.id, u.fullName, u.rank, o.sentAt,
                    (select count(l) from ShipOrderLine l where l.order = o))
                from ShipOrder o join o.ship s left join o.sentBy u
                where o.status in :statuses and o.exportedAt is null and s.active = true""", OrderRow.class)
                .setParameter("statuses", ShipOrderService.SENT_TO_OFFICE)
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
