package com.nereonix.nereonet_mvp.core.certificate;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    /** One spelling of a certificate type and how many certificates use it. */
    record TypeCount(String type, long count) {
    }

    // Every type in use with its number of certificates (P20: the type suggestions and the match on save).
    @Query("""
            select new com.nereonix.nereonet_mvp.core.certificate.CertificateRepository$TypeCount(c.type, count(c))
            from Certificate c group by c.type""")
    List<TypeCount> countByType();

    // The certificates of these users (a ship's crew), for the ship's certificate matrix.
    List<Certificate> findAllByUserIdIn(Collection<Long> userIds);

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

    // Whether the crew of one ship hold any certificate: the empty state of the ship's Certificates list (P25).
    boolean existsByUserShipId(Long shipId);

    // Not expired yet, expiring on or before "until", of enabled users. For the reminder job.
    @EntityGraph(attributePaths = {"user", "user.ship"})
    @Query("""
            select c from Certificate c
            where c.user.enabled = true
              and c.expiresOn >= :today
              and c.expiresOn <= :until
            order by c.expiresOn asc, c.id asc""")
    List<Certificate> findDueForReminder(LocalDate today, LocalDate until);
}
