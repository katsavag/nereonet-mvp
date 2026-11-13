package com.nereonix.nereonet_mvp.core.vetting;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.library.LibraryFileStorage;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.vetting.RightShipClient.VesselInfo;

/**
 * Vetting (P40): every active vessel's RightShip standing (ShipRightShipStatus, one current row per vessel and the
 * earlier ones as history) and its official RightShip documents (RightShipReport). The office edits the data by hand
 * (saveManual) and uploads the documents; the RightShip API sync (RightShipSyncService) adds rows through applyApi.
 * Every change is in the audit log. Only active vessels have Vetting pages: an inactive or unknown vessel is a 404.
 */
@Service
public class VettingService {

    /** The Vetting list's sorts (P19), the default first: most urgent. */
    public static final String SORT_URGENCY = "urgency";

    private final ShipRepository shipRepository;
    private final ShipRightShipStatusRepository statusRepository;
    private final RightShipReportRepository reportRepository;
    private final RightShipSyncRepository syncRepository;
    private final RightShipSettings settings;
    private final LibraryFileStorage fileStorage;
    private final AuditService auditService;

    public VettingService(ShipRepository shipRepository, ShipRightShipStatusRepository statusRepository,
                          RightShipReportRepository reportRepository, RightShipSyncRepository syncRepository,
                          RightShipSettings settings, LibraryFileStorage fileStorage, AuditService auditService) {
        this.shipRepository = shipRepository;
        this.statusRepository = statusRepository;
        this.reportRepository = reportRepository;
        this.syncRepository = syncRepository;
        this.settings = settings;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------------------------

    /** One row of the Vetting list: an active vessel and its standing. */
    public record VettingRow(Ship ship, RightShipView view) {
    }

    /** The active vessel with this id, or 404 (an inactive vessel has no Vetting pages). */
    @Transactional(readOnly = true)
    public Ship activeShip(Long id) {
        return shipRepository.findById(id).filter(Ship::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public RightShipView view(Ship ship) {
        return new RightShipView(statusRepository.findByShipIdAndCurrentTrue(ship.getId()).orElse(null), today());
    }

    /** Every vessel's standing by its id (the Vessels list); a vessel without data has none. */
    @Transactional(readOnly = true)
    public Map<Long, RightShipView> viewsByShip() {
        LocalDate today = today();
        return statusRepository.findAllCurrent().stream()
                .collect(Collectors.toMap(s -> s.getShip().getId(), s -> new RightShipView(s, today)));
    }

    /**
     * The Vetting list: every active vessel, searched (name or IMO number), filtered by type and Status
     * (RightShipView.STATUSES; null: all) and sorted by the list's sort, then name. The fleet is small, so this is
     * done in memory: two queries, whatever the page.
     */
    @Transactional(readOnly = true)
    public Page<VettingRow> list(ListQuery list, ShipType type, String status) {
        LocalDate today = today();
        Map<Long, ShipRightShipStatus> current = statusRepository.findAllCurrent().stream()
                .collect(Collectors.toMap(s -> s.getShip().getId(), Function.identity()));
        String q = list.getQ() == null ? null : list.getQ().toLowerCase(Locale.ROOT);
        List<VettingRow> rows = new ArrayList<>(shipRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .filter(s -> q == null || s.getName().toLowerCase(Locale.ROOT).contains(q) || s.getImoNumber().contains(q))
                .filter(s -> type == null || s.getType() == type)
                .map(s -> new VettingRow(s, new RightShipView(current.get(s.getId()), today)))
                .filter(r -> status == null || r.view().filterStatus().equals(status))
                .toList());
        rows.sort(order(list.getSort(), list.isDescending()));
        long total = rows.size();
        int page = ListQueries.clamp(list, total);
        int from = Math.min(page * list.getSize(), rows.size());
        int to = Math.min(from + list.getSize(), rows.size());
        return new PageImpl<>(List.copyOf(rows.subList(from, to)), PageRequest.of(page, list.getSize()), total);
    }

    // The sort's order, then the vessel's name (always A to Z) and id. Not rated, no GHG Rating and no date sort last
    // in either direction.
    private static Comparator<VettingRow> order(String sort, boolean descending) {
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        Comparator<VettingRow> byName = Comparator.comparing((VettingRow r) -> r.ship().getName(), collator)
                .thenComparing(r -> r.ship().getId());
        Comparator<VettingRow> first = switch (sort) {
            case "name" -> null;
            case "type" -> nullsLast(r -> r.ship().getType() == null ? null : r.ship().getType().ordinal(), descending);
            case "score" -> nullsLast(r -> r.view().score(), descending);
            case "ghg" -> nullsLast(r -> r.view().ghgRated() ? ghgRank(r.view()) : null, descending);
            case "inspection" -> nullsLast(r -> r.view().inspectionValidUntil(), descending);
            // Most urgent: expired, expiring, then the lowest score (not rated last).
            default -> Comparator.comparingInt((VettingRow r) -> r.view().urgency())
                    .thenComparing(r -> r.view().score(), Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (first == null) {
            return descending ? byName.reversed() : byName;
        }
        return first.thenComparing(byName);
    }

    // A+ before A before B ... E.
    private static int ghgRank(RightShipView view) {
        return view.status().getGhgRating().ordinal() * 2 + (view.ghgLetter().endsWith("+") ? 0 : 1);
    }

    private static <T extends Comparable<? super T>> Comparator<VettingRow> nullsLast(Function<VettingRow, T> key,
                                                                                    boolean descending) {
        Comparator<T> natural = Comparator.naturalOrder();
        return Comparator.comparing(key, Comparator.nullsLast(descending ? natural.reversed() : natural));
    }

    /** The sidebar counter of Vetting: active vessels whose inspection is expired or expires within 60 days. */
    @Transactional(readOnly = true)
    public long inspectionsDue() {
        return statusRepository.countInspectionDueUntil(today().plusDays(InspectionState.EXPIRING_DAYS));
    }

    /** The vessel's official documents, newest first; the first is "the official document". */
    @Transactional(readOnly = true)
    public List<RightShipReport> reports(Long shipId) {
        return reportRepository.findAllByShipIdOrderByDocumentDateDescUploadedAtDescIdDesc(shipId);
    }

    /** When the RightShip sync last failed, if it has not worked since; null when it works or is not configured. */
    @Transactional(readOnly = true)
    public Instant syncFailedAt() {
        if (!settings.configured()) {
            return null;
        }
        return syncRepository.findById(RightShipSync.ID).filter(RightShipSync::failing)
                .map(RightShipSync::getLastFailureAt).orElse(null);
    }

    public boolean apiConfigured() {
        return settings.configured();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Changing
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The office's edit: values (a draft row from the form, every RightShip field set) becomes the vessel's current
     * row, source MANUAL, by user, now. Recorded as "RightShip data updated".
     */
    @Transactional
    public ShipRightShipStatus saveManual(Ship ship, ShipRightShipStatus values, User user) {
        ShipRightShipStatus row = values.copy();
        row.setShip(ship);
        row.setSubscoresReportDate(null);
        row.setSource(RightShipSource.MANUAL);
        row.setUpdatedBy(user);
        makeCurrent(ship, row);
        auditService.record("RIGHTSHIP_UPDATED", ship, describe(row), user);
        return row;
    }

    /**
     * What the RightShip API gives for the vessel becomes its current row, source API (the sync or "Refresh from
     * RightShip"; actor null for the nightly job): the overall score, the DOC subscore, GHG Rating, inspection and
     * restrictions. The other five subscores, which the API does not give, are kept from the vessel's last manual
     * entry, with the date of its report. Nothing is written when nothing changed. Returns whether it changed.
     */
    @Transactional
    public boolean applyApi(Ship ship, VesselInfo info, User actor, String auditAction) {
        ShipRightShipStatus current = statusRepository.findByShipIdAndCurrentTrue(ship.getId()).orElse(null);
        ShipRightShipStatus manual = statusRepository
                .findFirstByShipIdAndSourceOrderByUpdatedAtDescIdDesc(ship.getId(), RightShipSource.MANUAL).orElse(null);
        ShipRightShipStatus row = new ShipRightShipStatus();
        row.setShip(ship);
        row.setSafetyScore(info.safetyScore());
        row.setIndicative(info.indicative());
        row.setCalculatedAt(info.calculatedAt());
        for (Subscore s : Subscore.values()) {
            row.setSubscore(s, s.fromApi() ? valid(info.docSafetyScore()) : manual == null ? null : manual.subscore(s));
        }
        row.setSubscoresReportDate(manual == null ? null : manual.getCalculatedAt());
        row.setGhgRating(info.ghgRating());
        row.setGhgPlus(info.ghgPlus() && info.ghgRating() == GhgRating.A);
        row.setGhgVerified(info.ghgVerified());
        row.setGhgRatedAt(info.ghgRatedAt());
        row.setInspectionRequired(info.inspectionRequired());
        row.setLastInspectionOutcome(info.lastInspectionOutcome());
        row.setInspectionValidUntil(info.inspectionValidUntil());
        row.setRestricted(info.restricted());
        row.setRestrictionComments(cut(info.restrictionComments(), ShipRightShipStatus.COMMENTS_MAX_LENGTH));
        String link = info.platformLink() != null ? info.platformLink() : current == null ? null : current.getPlatformLink();
        row.setPlatformLink(link == null || !link.startsWith("https://") ? null : cut(link, ShipRightShipStatus.LINK_MAX_LENGTH));
        if (row.sameValues(current)) {
            return false;
        }
        row.setSource(RightShipSource.API);
        row.setUpdatedBy(actor);
        makeCurrent(ship, row);
        auditService.record(auditAction, ship, describe(row), actor);
        return true;
    }

    private void makeCurrent(Ship ship, ShipRightShipStatus row) {
        statusRepository.findByShipIdAndCurrentTrue(ship.getId()).ifPresent(old -> {
            old.setCurrent(false);
            statusRepository.saveAndFlush(old);
        });
        row.setCurrent(true);
        row.setUpdatedAt(Instant.now());
        statusRepository.save(row);
    }

    /**
     * Saves an official RightShip document (PDF, PNG or JPEG, already checked by the form) for the vessel, dated
     * documentDate, uploaded by user now. Recorded as "RightShip report uploaded".
     */
    @Transactional
    public RightShipReport upload(Ship ship, MultipartFile file, String contentType, LocalDate documentDate, User user) {
        try (InputStream in = file.getInputStream()) {
            return saveReport(ship, in, file.getOriginalFilename(), contentType, file.getSize(), documentDate, user);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** As upload, from any content (the dev data's sample reports); user null. */
    @Transactional
    public RightShipReport saveReport(Ship ship, InputStream content, String fileName, String contentType, long size,
                                      LocalDate documentDate, User user) {
        RightShipReport report = new RightShipReport();
        report.setShip(ship);
        report.setFileName(fileName == null || fileName.isBlank() ? "rightship-report" : cut(fileName.strip(), 255));
        report.setFileContentType(contentType);
        report.setFileSize(size);
        report.setStoragePath(fileStorage.save(content));
        report.setDocumentDate(documentDate);
        report.setUploadedBy(user);
        report.setUploadedAt(Instant.now());
        reportRepository.save(report);
        auditService.record("RIGHTSHIP_REPORT_UPLOADED", ship,
                report.getFileName() + ", dated " + documentDate, user);
        return report;
    }

    /** A report's file: opened in the browser, or downloaded. 404 when the vessel has no such report. */
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> reportFile(Long shipId, Long reportId, boolean download) {
        RightShipReport report = reportRepository.findByIdAndShipId(reportId, shipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ContentDisposition.Builder disposition = download ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition.filename(report.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(report.getFileContentType()))
                // A report never changes once uploaded: the browser may keep it for a day (slow links on board).
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate())
                .body(fileStorage.load(report.getStoragePath()));
    }

    // For the audit log: "Safety Score 3/5 (21 Oct 2026), subscores 4 2 3 4 4 5, GHG C unverified, inspection valid
    // until 2026-12-21".
    static String describe(ShipRightShipStatus row) {
        StringBuilder text = new StringBuilder("Safety Score ")
                .append(row.getSafetyScore() == null ? "N/A" : row.getSafetyScore() + "/5");
        if (row.getCalculatedAt() != null) {
            text.append(" (").append(row.getCalculatedAt()).append(")");
        }
        text.append(", subscores");
        for (Subscore s : Subscore.values()) {
            text.append(' ').append(row.subscore(s) == null ? "-" : row.subscore(s));
        }
        GhgRating ghg = row.getGhgRating();
        text.append(", GHG ").append(ghg == null || !ghg.rated() ? "N/A"
                : ghg.name() + (row.isGhgPlus() ? "+" : "") + (row.isGhgVerified() ? " verified" : " unverified"));
        text.append(", inspection ").append(row.getInspectionValidUntil() == null ? "-"
                : "valid until " + row.getInspectionValidUntil());
        if (row.getLastInspectionOutcome() != null) {
            text.append(", ").append(row.getLastInspectionOutcome().name().toLowerCase(Locale.ROOT));
        }
        if (row.isRestricted()) {
            text.append(", restricted");
        }
        return text.toString();
    }

    private static Integer valid(Integer subscore) {
        return subscore == null || subscore < 1 || subscore > 5 ? null : subscore;
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
