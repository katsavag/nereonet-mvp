package com.nereonix.nereonet_mvp.core.announcement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.ship.ShipCount;

public interface AnnouncementRecipientRepository extends JpaRepository<AnnouncementRecipient, Long> {

    List<AnnouncementRecipient> findAllByAnnouncementOrderByUserFullNameAsc(Announcement announcement);

    // One row per announcement that has recipients.
    @Query("""
            select new com.nereonix.nereonet_mvp.core.announcement.RecipientCount(
                r.announcement.id, count(r), count(r.acknowledgedAt))
            from AnnouncementRecipient r group by r.announcement.id""")
    List<RecipientCount> countAll();

    // Per ship: announcements that at least one recipient on that ship has not acknowledged yet.
    @Query("""
            select new com.nereonix.nereonet_mvp.core.ship.ShipCount(r.ship.id, count(distinct r.announcement.id))
            from AnnouncementRecipient r where r.acknowledgedAt is null group by r.ship.id""")
    List<ShipCount> countUnacknowledgedByShip();
}
