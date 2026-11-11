package com.nereonix.nereonet_mvp.certificate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateReminderRepository;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// Certificates of crew members, managed by the office.
@Service
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final CertificateReminderRepository reminderRepository;
    private final UserRepository userRepository;
    private final ShipRepository shipRepository;
    private final AuditService auditService;

    public CertificateService(CertificateRepository certificateRepository,
                              CertificateReminderRepository reminderRepository, UserRepository userRepository,
                              ShipRepository shipRepository, AuditService auditService) {
        this.certificateRepository = certificateRepository;
        this.reminderRepository = reminderRepository;
        this.userRepository = userRepository;
        this.shipRepository = shipRepository;
        this.auditService = auditService;
    }

    // 404 if there is no such user or it is an office user.
    @Transactional(readOnly = true)
    public User getCrewMember(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> user.getRole() != Role.OFFICE_USER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // 404 if this crew member has no such certificate.
    @Transactional(readOnly = true)
    public Certificate getCertificate(Long userId, Long id) {
        return certificateRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Certificates that expire within 90 days or have expired, soonest first. shipId may be null for all. */
    @Transactional(readOnly = true)
    public List<Certificate> findExpiring(Long shipId) {
        return certificateRepository.findExpiringUntil(today().plusDays(Certificate.REMINDER_DAYS.getLast()), shipId);
    }

    /** The enabled crew members of a ship, by name: who can get a certificate from the ship page. */
    @Transactional(readOnly = true)
    public List<User> findShipCrew(Ship ship) {
        return userRepository.findAllByShipAndRoleNotAndEnabledTrueOrderByFullNameAsc(ship, Role.OFFICE_USER);
    }

    @Transactional(readOnly = true)
    public List<Ship> findAllShips() {
        return shipRepository.findAllByOrderByNameAsc();
    }

    @Transactional
    public void create(Long userId, String type, String number, LocalDate expiresOn) {
        Certificate certificate = new Certificate();
        certificate.setUser(getCrewMember(userId));
        apply(certificate, type, number, expiresOn);
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
        auditService.record("CERTIFICATE_CREATED", certificate.getUser().getShip(), describe(certificate));
    }

    /** A new expiry date deletes the certificate's reminder rows, so its reminders start again. */
    @Transactional
    public void update(Long userId, Long id, String type, String number, LocalDate expiresOn) {
        Certificate certificate = getCertificate(userId, id);
        if (!certificate.getExpiresOn().equals(expiresOn)) {
            reminderRepository.deleteByCertificate(certificate);
        }
        apply(certificate, type, number, expiresOn);
        auditService.record("CERTIFICATE_UPDATED", certificate.getUser().getShip(), describe(certificate));
    }

    /** Its reminder rows are deleted by the database (cascade). */
    @Transactional
    public void delete(Long userId, Long id) {
        Certificate certificate = getCertificate(userId, id);
        certificateRepository.delete(certificate);
        auditService.record("CERTIFICATE_DELETED", certificate.getUser().getShip(), describe(certificate));
    }

    // The UTC date, as used by the reminder job.
    public LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    // For the audit log, e.g. "jane@example.com: STCW Basic Safety, 12345, expires 2027-01-10"
    private static String describe(Certificate certificate) {
        return certificate.getUser().getEmail() + ": " + certificate.getType() + ", "
                + (certificate.getNumber() == null ? "-" : certificate.getNumber())
                + ", expires " + certificate.getExpiresOn();
    }

    private static void apply(Certificate certificate, String type, String number, LocalDate expiresOn) {
        certificate.setType(type.strip());
        certificate.setNumber(number == null || number.isBlank() ? null : number.strip());
        certificate.setExpiresOn(expiresOn);
    }
}
