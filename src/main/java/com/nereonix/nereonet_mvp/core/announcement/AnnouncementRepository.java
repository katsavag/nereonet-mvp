package com.nereonix.nereonet_mvp.core.announcement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// The Announcements list is queried page by page in announcement/AnnouncementService (P19, core/list/ListQueries).
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Sent to all ships, or to this ship (alone or with others): the ship page's Announcements tab (P28). */
    @Query("""
            select count(a) from Announcement a left join a.targetShips t on t.id = :shipId
            where a.fleetWide = true or t.id is not null""")
    long countForShip(Long shipId);
}
