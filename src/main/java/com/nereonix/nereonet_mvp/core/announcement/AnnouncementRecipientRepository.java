package com.nereonix.nereonet_mvp.core.announcement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;


public interface AnnouncementRecipientRepository extends JpaRepository<AnnouncementRecipient, Long> {

    List<AnnouncementRecipient> findAllByAnnouncementOrderByUserFullNameAsc(Announcement announcement);

    // One row per announcement among these ids that has recipients: the page of the Announcements list (P19).
    @Query("""
            select new com.nereonix.nereonet_mvp.core.announcement.RecipientCount(
                r.announcement.id, count(r), count(r.acknowledgedAt))
            from AnnouncementRecipient r where r.announcement.id in :announcementIds group by r.announcement.id""")
    List<RecipientCount> countFor(Collection<Long> announcementIds);

    /** The announcements this user is a recipient of, newest first (the crew's Announcements, P27). */
    @Query("""
            select r from AnnouncementRecipient r join fetch r.announcement a
            where r.user.email = :email order by a.createdAt desc, a.id desc""")
    List<AnnouncementRecipient> findAllForUser(String email);

    /** The ones this user has not acknowledged yet, oldest first (To read on the crew home page, P27). */
    @Query("""
            select r from AnnouncementRecipient r join fetch r.announcement a
            where r.user.email = :email and r.acknowledgedAt is null order by a.createdAt asc, a.id asc""")
    List<AnnouncementRecipient> findUnacknowledgedForUser(String email);

    /** This user's place among the recipients of one announcement; empty when they are not a recipient. */
    Optional<AnnouncementRecipient> findByAnnouncementIdAndUserEmail(Long announcementId, String email);
}
