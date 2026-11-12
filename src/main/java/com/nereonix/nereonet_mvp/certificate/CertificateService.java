package com.nereonix.nereonet_mvp.certificate;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
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

    /** A CSV download, UTF-8 with a byte order mark (so Excel reads Greek names), as the request export (P20). */
    public record CsvFile(String filename, byte[] content) {
    }

    /**
     * Which certificates the Certificates list shows (P19, P20). shipId: one ship's crew, or null; activeShips: only
     * the crew of active ships (what the Overview counts); status: the Status filter. The search is the type, the
     * number or the crew member's name.
     */
    public record Filter(Long shipId, boolean activeShips, CertificateStatus status) {
    }

    private static final String[] LIST_HEADER = {"imo_number", "ship_name", "crew_member", "rank", "type", "number",
            "expires_on", "days_left", "status"};

    private static final ListQueries.Fetch<Certificate> FETCH =
            root -> root.fetch("user").fetch("ship", JoinType.LEFT);

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
     * One page of the Certificates list, paginated in the database.
     * Sort keys: expiresOn (default, soonest first), type, crewMember, ship, number.
     */
    @Transactional(readOnly = true)
    public Page<Certificate> findCertificates(Filter filter, ListQuery list) {
        return listQueries.page(Certificate.class, list, where(filter, list, today()), sortBy(list), FETCH);
    }

    /** Every certificate of the list as filtered and sorted, without pages: its CSV export. */
    @Transactional(readOnly = true)
    public List<Certificate> findAllCertificates(Filter filter, ListQuery list) {
        return listQueries.all(Certificate.class, list, where(filter, list, today()), sortBy(list), FETCH);
    }

    private static ListQueries.Where<Certificate> where(Filter filter, ListQuery list, LocalDate today) {
        return (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            Path<LocalDate> expiresOn = root.get("expiresOn");
            Join<Certificate, User> user = root.join("user");
            Predicate status = filter.status().predicate(cb, expiresOn, today);
            if (status != null) {
                where.add(status);
            }
            if (filter.shipId() != null) {
                where.add(cb.equal(user.get("ship").get("id"), filter.shipId()));
            }
            if (filter.activeShips()) {
                where.add(cb.isTrue(user.join("ship").get("active")));
            }
            String q = list.getContains();
            if (q != null) {
                where.add(cb.or(ListQueries.contains(cb, root.get("type"), q),
                        ListQueries.contains(cb, root.get("number"), q),
                        ListQueries.contains(cb, user.get("fullName"), q)));
            }
            return where;
        };
    }

    private static ListQueries.SortBy<Certificate> sortBy(ListQuery list) {
        return (root, cb) -> {
            Expression<LocalDate> expiresOn = root.get("expiresOn");
            return switch (list.getSort()) {
                case "type" -> List.of(cb.lower(root.get("type")), expiresOn);
                case "crewMember" -> List.of(cb.lower(root.join("user").get("fullName")), expiresOn);
                case "ship" -> List.of(cb.lower(root.join("user").join("ship", JoinType.LEFT).get("name")), expiresOn);
                case "number" -> List.of(cb.lower(root.get("number")), expiresOn);
                default -> List.of(expiresOn);
            };
        };
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

    /**
     * Whether there is any certificate at all, of one ship's crew or (shipId null) of anyone: an empty list then says
     * "No certificates expire within 90 days" rather than "No certificates yet" (P25).
     */
    @Transactional(readOnly = true)
    public boolean anyCertificates(Long shipId) {
        return shipId == null ? certificateRepository.count() > 0 : certificateRepository.existsByUserShipId(shipId);
    }

    /** The type suggestions of the certificate form: types in use, most used first, then the starter list. */
    @Transactional(readOnly = true)
    public List<String> typeSuggestions() {
        return CertificateTypes.suggestions(certificateRepository.countByType());
    }

    /** One column of the matrix: a type, whatever its spelling, named by its most used spelling. */
    private record Column(String key, String name, long holders) {
    }

    /** The ship's certificate matrix (P20): its active crew on board and the types they hold. */
    @Transactional(readOnly = true)
    public CertificateMatrix matrix(Ship ship) {
        List<User> crew = new ArrayList<>(findShipCrew(ship));
        // By department and rank (Rank's order), without a rank last; a stable sort, so by name within a rank.
        crew.sort(Comparator.comparing(User::getRank, Comparator.nullsLast(Comparator.naturalOrder())));
        if (crew.isEmpty()) {
            return new CertificateMatrix(List.of(), List.of());
        }
        List<Certificate> certificates =
                certificateRepository.findAllByUserIdIn(crew.stream().map(User::getId).toList());

        Map<String, List<Certificate>> byType = certificates.stream().collect(Collectors.groupingBy(
                c -> CertificateTypes.sameTypeKey(c.getType()), LinkedHashMap::new, Collectors.toList()));
        List<Column> columns = new ArrayList<>();
        byType.forEach((key, list) -> columns.add(new Column(key, mostUsedSpelling(list),
                list.stream().map(c -> c.getUser().getId()).distinct().count())));
        columns.sort(Comparator.comparingLong(Column::holders).reversed()
                .thenComparing(Column::name, String.CASE_INSENSITIVE_ORDER));

        // Per person and type, the certificate that expires last.
        Comparator<Certificate> latest = Comparator.comparing(Certificate::getExpiresOn).thenComparing(Certificate::getId);
        Map<Long, Map<String, Certificate>> current = new LinkedHashMap<>();
        for (Certificate c : certificates) {
            current.computeIfAbsent(c.getUser().getId(), id -> new LinkedHashMap<>())
                    .merge(CertificateTypes.sameTypeKey(c.getType()), c, (a, b) -> latest.compare(a, b) >= 0 ? a : b);
        }
        List<CertificateMatrix.Row> rows = crew.stream().map(member -> {
            Map<String, Certificate> held = current.getOrDefault(member.getId(), Map.of());
            return new CertificateMatrix.Row(member, columns.stream()
                    .map(column -> new CertificateMatrix.Cell(column.name(), held.get(column.key()))).toList());
        }).toList();
        return new CertificateMatrix(columns.stream().map(Column::name).toList(), rows);
    }

    // The spelling most of these certificates use; the first by name on a tie.
    private static String mostUsedSpelling(List<Certificate> certificates) {
        Map<String, Long> counts = certificates.stream()
                .collect(Collectors.groupingBy(c -> c.getType().strip(), Collectors.counting()));
        return counts.entrySet().stream()
                .min(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow().getKey();
    }

    /**
     * The list as CSV (P20), one row per certificate: imo_number, ship_name, crew_member, rank (Rank's name), type,
     * number, expires_on (ISO date), days_left (negative once expired) and status (expired, 30_days, 60_days,
     * 90_days or valid: the badge). name: the file name before the date, e.g. "certificates".
     */
    public CsvFile listCsv(String name, List<Certificate> certificates) {
        LocalDate today = today();
        return csv(name, LIST_HEADER, printer -> {
            for (Certificate c : certificates) {
                Ship ship = c.getUser().getShip();
                Integer threshold = c.reminderThreshold(today);
                printer.printRecord(ship == null ? "" : ship.getImoNumber(), ship == null ? "" : ship.getName(),
                        c.getUser().getFullName(), rank(c.getUser()), c.getType(),
                        c.getNumber() == null ? "" : c.getNumber(), c.getExpiresOn(), c.daysLeft(today),
                        c.isExpired(today) ? "expired" : threshold == null ? "valid" : threshold + "_days");
            }
        });
    }

    /** The matrix as CSV (P20): crew_member, rank, then one column per type with the expiry date (ISO), or empty. */
    public CsvFile matrixCsv(String name, CertificateMatrix matrix) {
        List<String> header = new ArrayList<>(List.of("crew_member", "rank"));
        header.addAll(matrix.types());
        return csv(name, header.toArray(String[]::new), printer -> {
            for (CertificateMatrix.Row row : matrix.rows()) {
                List<Object> values = new ArrayList<>(List.of(row.member().getFullName(), rank(row.member())));
                row.cells().forEach(cell -> values.add(cell.certificate() == null ? "" : cell.certificate().getExpiresOn()));
                printer.printRecord(values);
            }
        });
    }

    @FunctionalInterface
    private interface Rows {
        void print(CSVPrinter printer) throws IOException;
    }

    private CsvFile csv(String name, String[] header, Rows rows) {
        StringWriter csv = new StringWriter();
        csv.write(0xFEFF); // the byte order mark
        try (CSVPrinter printer = new CSVPrinter(csv, CSVFormat.DEFAULT.builder().setHeader(header).get())) {
            rows.print(printer);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // never happens with a StringWriter
        }
        return new CsvFile(name + "-" + today() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String rank(User user) {
        return user.getRank() == null ? "" : user.getRank().name();
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

    // The type is stored as a known type is spelled when it differs only in case or surrounding spaces (P20). The
    // certificate being edited does not count, so the only certificate of a type can still correct its spelling.
    private void apply(Certificate certificate, String type, String number, LocalDate expiresOn) {
        List<CertificateRepository.TypeCount> used = certificateRepository.countByType().stream()
                .map(row -> row.type().equals(certificate.getType())
                        ? new CertificateRepository.TypeCount(row.type(), row.count() - 1) : row)
                .filter(row -> row.count() > 0)
                .toList();
        certificate.setType(CertificateTypes.canonical(type, used));
        certificate.setNumber(number == null || number.isBlank() ? null : number.strip());
        certificate.setExpiresOn(expiresOn);
    }
}
