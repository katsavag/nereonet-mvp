package com.nereonix.nereonet_mvp.core.announcement;

import java.util.Collection;
import java.util.List;

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
}
