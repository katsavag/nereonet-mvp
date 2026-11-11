package com.nereonix.nereonet_mvp.core.announcement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    List<Announcement> findAllByOrderByCreatedAtDescIdDesc();

    // Sent to this ship or to all ships, newest first.
    @Query("""
            select a from Announcement a
            where a.ship is null or a.ship.id = :shipId
            order by a.createdAt desc, a.id desc""")
    List<Announcement> findAllForShip(Long shipId);

    @Query("select count(a) from Announcement a where a.ship is null or a.ship.id = :shipId")
    long countForShip(Long shipId);
}
