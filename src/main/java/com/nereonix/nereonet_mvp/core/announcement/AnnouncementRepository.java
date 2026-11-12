package com.nereonix.nereonet_mvp.core.announcement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// The Announcements list is queried page by page in announcement/AnnouncementService (P19, core/list/ListQueries).
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    @Query("select count(a) from Announcement a where a.ship is null or a.ship.id = :shipId")
    long countForShip(Long shipId);
}
