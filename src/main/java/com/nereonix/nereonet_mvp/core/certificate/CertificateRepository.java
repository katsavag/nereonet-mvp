package com.nereonix.nereonet_mvp.core.certificate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    List<Certificate> findAllByUserIdOrderByExpiresOnAsc(Long userId);

    Optional<Certificate> findByIdAndUserId(Long id, Long userId);

    // Expiring on or before "until" (expired included), soonest first. A null shipId means all ships.
    @EntityGraph(attributePaths = {"user", "user.ship"})
    @Query("""
            select c from Certificate c
            where c.expiresOn <= :until
              and (:shipId is null or c.user.ship.id = :shipId)
            order by c.expiresOn asc, c.id asc""")
    List<Certificate> findExpiringUntil(LocalDate until, Long shipId);

    // The same certificates as findExpiringUntil for one ship, counted: the ship page's tab counters.
    @Query("""
            select count(c) from Certificate c
            where c.expiresOn <= :until and c.user.ship.id = :shipId""")
    long countExpiringUntil(LocalDate until, Long shipId);

    // Expired or expiring on or before "until", every ship: the sidebar counter of Certificates.
    long countByExpiresOnLessThanEqual(LocalDate until);

    // Not expired yet, expiring on or before "until", of enabled users. For the reminder job.
    @EntityGraph(attributePaths = {"user", "user.ship"})
    @Query("""
            select c from Certificate c
            where c.user.enabled = true
              and c.expiresOn >= :today
              and c.expiresOn <= :until
            order by c.expiresOn asc, c.id asc""")
    List<Certificate> findDueForReminder(LocalDate today, LocalDate until);

    // Per ship of the certificate's user: expired, and expiring within 30, 31-60 and 61-90 days.
    @Query("""
            select new com.nereonix.nereonet_mvp.core.certificate.CertificateCounts(c.user.ship.id,
                sum(case when c.expiresOn < :today then 1 else 0 end),
                sum(case when c.expiresOn >= :today and c.expiresOn <= :in30 then 1 else 0 end),
                sum(case when c.expiresOn > :in30 and c.expiresOn <= :in60 then 1 else 0 end),
                sum(case when c.expiresOn > :in60 then 1 else 0 end))
            from Certificate c
            where c.expiresOn <= :in90 and c.user.ship is not null
            group by c.user.ship.id""")
    List<CertificateCounts> countExpiringByShip(LocalDate today, LocalDate in30, LocalDate in60, LocalDate in90);
}
