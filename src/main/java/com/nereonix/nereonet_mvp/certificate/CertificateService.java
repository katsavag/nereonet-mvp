package com.nereonix.nereonet_mvp.certificate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateReminderRepository;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;

// Certificates of crew members, managed by the office.
@Service
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final CertificateReminderRepository reminderRepository;
    private final UserRepository userRepository;
    private final ShipRepository shipRepository;
    private final AuditService auditService;
    private final ListQueries listQueries;

    public CertificateService(CertificateRepository certificateRepository,
                              CertificateReminderRepository reminderRepository, UserRepository userRepository,
                              ShipRepository shipRepository, AuditService auditService, ListQueries listQueries) {
        this.certificateRepository = certificateRepository;
        this.reminderRepository = reminderRepository;
        this.userRepository = userRepository;
        this.shipRepository = shipRepository;
        this.auditService = auditService;
        this.listQueries = listQueries;
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

    /**
     * One page of the certificates that expire within 90 days or have expired (P19), paginated in the database.
     * shipId and due (the expiry step) may be null; the search is the type, the number or the crew member's name.
     * Sort keys: expiresOn (default, soonest first), type, crewMember, ship, number.
     */
    @Transactional(readOnly = true)
    public Page<Certificate> findExpiring(Long shipId, CertificateDue due, ListQuery list) {
        LocalDate today = today();
        return listQueries.page(Certificate.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            Path<LocalDate> expiresOn = root.get("expiresOn");
            Join<Certificate, User> user = root.join("user");
            where.add(cb.lessThanOrEqualTo(expiresOn, today.plusDays(Certificate.REMINDER_DAYS.getLast())));
            if (shipId != null) {
                where.add(cb.equal(user.get("ship").get("id"), shipId));
            }
            if (due != null) {
                if (due.from(today) == null) {
                    where.add(cb.lessThan(expiresOn, today));
                } else {
                    where.add(cb.between(expiresOn, due.from(today), due.to(today)));
                }
            }
            String q = list.getContains();
            if (q != null) {
                where.add(cb.or(ListQueries.contains(cb, root.get("type"), q),
                        ListQueries.contains(cb, root.get("number"), q),
                        ListQueries.contains(cb, user.get("fullName"), q)));
            }
            return where;
        }, (root, cb) -> {
            Expression<LocalDate> expiresOn = root.get("expiresOn");
            return switch (list.getSort()) {
                case "type" -> List.of(cb.lower(root.get("type")), expiresOn);
                case "crewMember" -> List.of(cb.lower(root.join("user").get("fullName")), expiresOn);
                case "ship" -> List.of(cb.lower(root.join("user").join("ship", JoinType.LEFT).get("name")), expiresOn);
                case "number" -> List.of(cb.lower(root.get("number")), expiresOn);
                default -> List.of(expiresOn);
            };
        }, root -> root.fetch("user").fetch("ship", JoinType.LEFT));
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
