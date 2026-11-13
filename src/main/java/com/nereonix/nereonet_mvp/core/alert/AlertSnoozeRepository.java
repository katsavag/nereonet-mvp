package com.nereonix.nereonet_mvp.core.alert;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AlertSnoozeRepository extends JpaRepository<AlertSnooze, Long> {

    /** The snoozes not ended, with who snoozed them, in one query: what SnoozeService.active() reads on each page
     *  that needs them. */
    @Query("select s from AlertSnooze s left join fetch s.snoozedBy left join fetch s.ship where s.endedAt is null order by s.id")
    List<AlertSnooze> findAllActive();

    Optional<AlertSnooze> findByIdAndEndedAtIsNull(Long id);

    /**
     * The certificates whose snooze still holds today (P43): an EXPIRED snooze of an expired certificate, or an
     * EXPIRING snooze of one not yet expired whose level (1 within 30 days, 2 within 60, 3 within 90) has not dropped
     * below the level remembered at the snooze (AlertLevels). For the Certificates list and its export.
     */
    @Query("""
            select c.id from Certificate c, AlertSnooze s
            where s.subjectId = c.id and s.endedAt is null and s.until >= :today
              and ((s.kind = :expired and c.expiresOn < :today)
                or (s.kind = :expiring and c.expiresOn >= :today
                    and s.level <= case when c.expiresOn <= :d30 then 1 when c.expiresOn <= :d60 then 2 else 3 end))""")
    List<Long> snoozedCertificateIds(LocalDate today, LocalDate d30, LocalDate d60, AlertKind expired, AlertKind expiring);
}
