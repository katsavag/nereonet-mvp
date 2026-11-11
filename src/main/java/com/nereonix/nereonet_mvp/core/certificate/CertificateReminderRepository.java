package com.nereonix.nereonet_mvp.core.certificate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CertificateReminderRepository extends JpaRepository<CertificateReminder, Long> {

    // A query, not a derived name: "Before" in "DaysBefore" is a Spring Data keyword.
    @Query("""
            select count(r) > 0 from CertificateReminder r
            where r.certificate = :certificate and r.daysBefore = :daysBefore""")
    boolean existsFor(Certificate certificate, int daysBefore);

    void deleteByCertificate(Certificate certificate);
}
