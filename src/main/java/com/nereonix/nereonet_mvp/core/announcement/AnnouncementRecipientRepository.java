package com.nereonix.nereonet_mvp.core.announcement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;


public interface AnnouncementRecipientRepository extends JpaRepository<AnnouncementRecipient, Long> {

    List<AnnouncementRecipient> findAllByAnnouncementOrderByUserFullNameAsc(Announcement announcement);

    /** One page of who has read it (the office's announcement page): by ship name, earliest read first in a ship. */
    @Query(value = """
            select r from AnnouncementRecipient r join fetch r.user join fetch r.ship s
            where r.announcement = :announcement and r.acknowledgedAt is not null
            order by lower(s.name) asc, s.id asc, r.acknowledgedAt asc, r.id asc""",
            countQuery = """
            select count(r) from AnnouncementRecipient r
            where r.announcement = :announcement and r.acknowledgedAt is not null""")
    Page<AnnouncementRecipient> findRead(Announcement announcement, Pageable pageable);

    /** One page of who has not read it yet: by ship name, by full name in a ship. */
    @Query(value = """
            select r from AnnouncementRecipient r join fetch r.user u join fetch r.ship s
            where r.announcement = :announcement and r.acknowledgedAt is null
            order by lower(s.name) asc, s.id asc, u.fullName asc, r.id asc""",
            countQuery = """
            select count(r) from AnnouncementRecipient r
            where r.announcement = :announcement and r.acknowledgedAt is null""")
    Page<AnnouncementRecipient> findNotRead(Announcement announcement, Pageable pageable);

    /** Per ship of one announcement's recipients: the ship's id, its recipients and how many of them have read it. */
    @Query("""
            select r.ship.id, count(r), count(r.acknowledgedAt)
            from AnnouncementRecipient r where r.announcement = :announcement group by r.ship.id""")
    List<Object[]> countPerShip(Announcement announcement);

    // One row per announcement among these ids that has recipients: the page of the Announcements list.
    @Query("""
            select new com.nereonix.nereonet_mvp.core.announcement.RecipientCount(
                r.announcement.id, count(r), count(r.acknowledgedAt))
            from AnnouncementRecipient r where r.announcement.id in :announcementIds group by r.announcement.id""")
    List<RecipientCount> countFor(Collection<Long> announcementIds);

    /**
     * The announcements this user is a recipient of (the crew's Announcements): the ones still to acknowledge first,
     * each group newest first.
     */
    @Query("""
            select r from AnnouncementRecipient r join fetch r.announcement a
            where r.user.email = :email
            order by case when r.acknowledgedAt is null then 0 else 1 end, a.createdAt desc, a.id desc""")
    List<AnnouncementRecipient> findAllForUser(String email);

    /** The ones this user has not opened yet, newest first (the crew's News, "New": all of them, never paged). */
    @Query("""
            select r from AnnouncementRecipient r join fetch r.announcement a
            where r.user.email = :email and r.acknowledgedAt is null
            order by a.createdAt desc, a.id desc""")
    List<AnnouncementRecipient> findUnreadForUser(String email);

    /** One page of the ones this user has opened, newest first (the crew's News, "Earlier"). */
    @Query(value = """
            select r from AnnouncementRecipient r join fetch r.announcement a
            where r.user.email = :email and r.acknowledgedAt is not null
            order by a.createdAt desc, a.id desc""",
            countQuery = """
            select count(r) from AnnouncementRecipient r
            where r.user.email = :email and r.acknowledgedAt is not null""")
    Page<AnnouncementRecipient> findReadForUser(String email, Pageable pageable);

    /** How many announcements this user has still to acknowledge: the crew's sidebar counter. */
    long countByUserEmailAndAcknowledgedAtIsNull(String email);

    /** The Required ones this user has not opened yet, oldest first (To read on the crew home page). */
    @Query("""
            select r from AnnouncementRecipient r join fetch r.announcement a
            where r.user.email = :email and r.acknowledgedAt is null and a.required = true
            order by a.createdAt asc, a.id asc""")
    List<AnnouncementRecipient> findUnacknowledgedForUser(String email);

    /** How many Required ones this user has not opened yet: the Master's "News to read" card. */
    long countByUserEmailAndAcknowledgedAtIsNullAndAnnouncementRequiredTrue(String email);

    /** This user's place among the recipients of one announcement; empty when they are not a recipient. */
    Optional<AnnouncementRecipient> findByAnnouncementIdAndUserEmail(Long announcementId, String email);
}
