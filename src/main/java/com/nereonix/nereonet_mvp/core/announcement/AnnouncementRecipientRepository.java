package com.nereonix.nereonet_mvp.core.announcement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;


public interface AnnouncementRecipientRepository extends JpaRepository<AnnouncementRecipient, Long> {

    List<AnnouncementRecipient> findAllByAnnouncementOrderByUserFullNameAsc(Announcement announcement);

    // One row per announcement that has recipients.
    @Query("""
            select new com.nereonix.nereonet_mvp.core.announcement.RecipientCount(
                r.announcement.id, count(r), count(r.acknowledgedAt))
            from AnnouncementRecipient r group by r.announcement.id""")
    List<RecipientCount> countAll();
}
