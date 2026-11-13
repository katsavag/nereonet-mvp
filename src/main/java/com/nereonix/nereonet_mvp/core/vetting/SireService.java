package com.nereonix.nereonet_mvp.core.vetting;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.Resource;
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

import com.nereonix.nereonet_mvp.core.alert.AlertKind;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.library.LibraryFileStorage;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * SIRE 2.0 in Vetting (P41): a vessel's SIRE 2.0 inspections (SireInspection), their negative observations
 * (SireObservation) and reports. The office enters everything by hand from the report; there is no connection to
 * OCIMF. Every add, edit and upload is in the audit log. The pages check the vessel first (VettingService.activeShip);
 * an inspection or observation of another vessel or inspection is a 404 here.
 */
@Service
public class SireService {

    private final SireInspectionRepository inspectionRepository;
    private final SireObservationRepository observationRepository;
    private final UserRepository userRepository;
    private final SireSettings settings;
    private final LibraryFileStorage fileStorage;
    private final AuditService auditService;

    public SireService(SireInspectionRepository inspectionRepository, SireObservationRepository observationRepository,
                       UserRepository userRepository, SireSettings settings, LibraryFileStorage fileStorage,
                       AuditService auditService) {
        this.inspectionRepository = inspectionRepository;
        this.observationRepository = observationRepository;
        this.userRepository = userRepository;
        this.settings = settings;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
    }

    public SireSettings settings() {
        return settings;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------------------------

    /** The vessel's latest inspection with its observations (a view without an inspection when it has none). */
    @Transactional(readOnly = true)
    public SireView latest(Ship ship) {
        List<SireInspection> all = inspections(ship.getId());
        return all.isEmpty() ? view(null) : view(all.getFirst());
    }

    /** An inspection with its observations. */
    @Transactional(readOnly = true)
    public SireView view(SireInspection inspection) {
        List<SireObservation> observations = inspection == null ? List.of()
                : observationRepository.findAllByInspectionId(inspection.getId());
        return new SireView(inspection, observations, settings, VettingService.today());
    }

    /** The vessel's inspections, newest first: the first is the latest. */
    @Transactional(readOnly = true)
    public List<SireInspection> inspections(Long shipId) {
        return inspectionRepository.findAllByShipIdOrderByInspectionDateDescIdDesc(shipId);
    }

    /** The vessel's inspection with this id, or 404 (another vessel's inspection included). */
    @Transactional(readOnly = true)
    public SireInspection inspection(Ship ship, Long inspectionId) {
        return inspectionRepository.findByIdAndShipId(inspectionId, ship.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The inspection's observation with this id, or 404. */
    @Transactional(readOnly = true)
    public SireObservation observation(SireInspection inspection, Long observationId) {
        return observationRepository.findByIdAndInspectionId(observationId, inspection.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Every vessel's latest inspection by vessel id, with its observations (the Vetting list): two queries. */
    @Transactional(readOnly = true)
    public Map<Long, SireView> latestByShip() {
        LocalDate today = VettingService.today();
        List<SireInspection> latest = inspectionRepository.findAllLatest();
        Map<Long, List<SireObservation>> observations = latest.isEmpty() ? Map.of()
                : observationRepository.findAllByInspectionIdIn(latest.stream().map(SireInspection::getId).toList())
                        .stream().collect(Collectors.groupingBy(o -> o.getInspection().getId()));
        return latest.stream().collect(Collectors.toMap(i -> i.getShip().getId(),
                i -> new SireView(i, observations.getOrDefault(i.getId(), List.of()), settings, today),
                (a, b) -> a));
    }

    /**
     * The active vessels whose latest SIRE report is no longer current or ends within 60 days, whose operator
     * comments are due within 3 days, or with an observation past its target date (the sidebar counter): two queries
     * of ids, nothing else loaded.
     */
    @Transactional(readOnly = true)
    public Set<Long> shipIdsNeedingAttention() {
        LocalDate today = VettingService.today();
        // P43: the items whose snooze holds are left out (the cut-offs give the items' levels, AlertLevels).
        Set<Long> ids = new HashSet<>(inspectionRepository.findShipIdsNeedingAttention(
                settings.currentCutoff(today), settings.commentsCutoff(today), today,
                settings.latestInspectionCurrentUntil(today.minusDays(1)),
                settings.latestInspectionCurrentUntil(today.plusDays(30)),
                today.minusDays(1).minusDays(settings.commentDays()),
                AlertKind.SIRE_REPORT, AlertKind.SIRE_COMMENTS));
        ids.addAll(observationRepository.findShipIdsWithOverdueOnLatest(today, AlertKind.SIRE_OBSERVATION));
        return ids;
    }

    /** What the Overview lists (P09): the latest inspections that need attention, and the overdue observations. */
    public record Attention(List<SireView> inspections, List<SireObservation> overdueObservations) {
    }

    /** The Overview's SIRE items: two queries (the inspections' views carry no observations: none are needed). */
    @Transactional(readOnly = true)
    public Attention attention(LocalDate today) {
        List<SireView> inspections = inspectionRepository
                .findLatestNeedingAttention(settings.currentCutoff(today), settings.commentsCutoff(today)).stream()
                .map(i -> new SireView(i, List.of(), settings, today)).toList();
        return new Attention(inspections, observationRepository.findOverdueOnLatest(today));
    }

    /** The office users who may own an observation: enabled office users, by name. */
    @Transactional(readOnly = true)
    public List<User> owners() {
        return userRepository.findAllByRoleOrderByFullNameAsc(Role.OFFICE_USER).stream().filter(User::isEnabled).toList();
    }

    /** The office user with this id, or null when there is none (or it is not an office user). */
    @Transactional(readOnly = true)
    public User owner(Long userId) {
        return userId == null ? null : userRepository.findById(userId)
                .filter(u -> u.getRole() == Role.OFFICE_USER).orElse(null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Changing
    // ---------------------------------------------------------------------------------------------------------------

    /** Adds an inspection (values: a draft from the form) to the vessel. Recorded as "SIRE 2.0 inspection added". */
    @Transactional
    public SireInspection addInspection(Ship ship, SireInspection values, User user) {
        SireInspection inspection = new SireInspection();
        inspection.setShip(ship);
        copy(values, inspection);
        inspection.setCreatedBy(user);
        inspection.setCreatedAt(Instant.now());
        inspectionRepository.save(inspection);
        auditService.record("SIRE_INSPECTION_ADDED", ship, describe(inspection), user);
        return inspection;
    }

    /** Saves the form's values on the inspection. Recorded as "SIRE 2.0 inspection updated". */
    @Transactional
    public SireInspection updateInspection(SireInspection inspection, SireInspection values, User user) {
        copy(values, inspection);
        inspectionRepository.save(inspection);
        auditService.record("SIRE_INSPECTION_UPDATED", inspection.getShip(), describe(inspection), user);
        return inspection;
    }

    private static void copy(SireInspection from, SireInspection to) {
        to.setInspectionDate(from.getInspectionDate());
        to.setPort(from.getPort());
        to.setSubmittingCompany(from.getSubmittingCompany());
        to.setInspectorName(from.getInspectorName());
        to.setReportReference(from.getReportReference());
        to.setStatus(from.getStatus());
        to.setPublishedOn(from.getPublishedOn());
    }

    /** Saves the report (a PDF, already checked by the form) on the inspection, in place of any earlier one. */
    @Transactional
    public SireInspection uploadReport(SireInspection inspection, MultipartFile file, User user) {
        try (InputStream in = file.getInputStream()) {
            return saveReport(inspection, in, file.getOriginalFilename(), file.getSize(), user);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** As uploadReport, from any content (the dev data's sample report). Recorded as "SIRE 2.0 report uploaded". */
    @Transactional
    public SireInspection saveReport(SireInspection inspection, InputStream content, String fileName, long size,
                                     User user) {
        inspection.setReportFileName(fileName == null || fileName.isBlank() ? "sire-report.pdf"
                : cut(fileName.strip(), 255));
        inspection.setReportFileSize(size);
        inspection.setReportStoragePath(fileStorage.save(content));
        inspection.setReportUploadedBy(user);
        inspection.setReportUploadedAt(Instant.now());
        inspectionRepository.save(inspection);
        auditService.record("SIRE_REPORT_UPLOADED", inspection.getShip(),
                inspection.getReportFileName() + ", inspection of " + inspection.getInspectionDate(), user);
        return inspection;
    }

    /** The inspection's report: opened in the browser, or downloaded. 404 while it has none. */
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> reportFile(SireInspection inspection, boolean download) {
        if (!inspection.hasReport()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        ContentDisposition.Builder disposition = download ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition
                        .filename(inspection.getReportFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                // A report can be replaced: the browser keeps it for an hour at most (slow links on board).
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(fileStorage.load(inspection.getReportStoragePath()));
    }

    /** Adds an observation (values: a draft from the form). Recorded as "SIRE 2.0 observation added". */
    @Transactional
    public SireObservation addObservation(SireInspection inspection, SireObservation values, User user) {
        SireObservation observation = new SireObservation();
        observation.setInspection(inspection);
        copy(values, observation);
        observation.setCreatedAt(Instant.now());
        observationRepository.save(observation);
        auditService.record("SIRE_OBSERVATION_ADDED", inspection.getShip(), describe(observation), user);
        return observation;
    }

    /** Saves the form's values on the observation. Recorded as "SIRE 2.0 observation updated". */
    @Transactional
    public SireObservation updateObservation(SireObservation observation, SireObservation values, User user) {
        copy(values, observation);
        observationRepository.save(observation);
        auditService.record("SIRE_OBSERVATION_UPDATED", observation.getInspection().getShip(), describe(observation), user);
        return observation;
    }

    private static void copy(SireObservation from, SireObservation to) {
        to.setChapter(from.getChapter());
        to.setQuestionNumber(from.getQuestionNumber());
        to.setType(from.getType());
        to.setDescription(from.getDescription());
        to.setOperatorComment(from.getOperatorComment());
        to.setCorrectiveAction(from.getCorrectiveAction());
        to.setOwner(from.getOwner());
        to.setTargetDate(from.getTargetDate());
        to.setClosedOn(from.getClosedOn());
    }

    // For the audit log: "Inspection 2026-06-12 at Piraeus for Demo Energy Ltd, published, reference DEMO-SIRE-0001".
    static String describe(SireInspection i) {
        StringBuilder text = new StringBuilder("Inspection ").append(i.getInspectionDate())
                .append(" at ").append(i.getPort()).append(" for ").append(i.getSubmittingCompany())
                .append(", ").append(i.getStatus().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        if (i.getPublishedOn() != null) {
            text.append(" (").append(i.getPublishedOn()).append(")");
        }
        if (i.getReportReference() != null) {
            text.append(", reference ").append(i.getReportReference());
        }
        return text.toString();
    }

    // "Observation 9.1.2 (hardware) of the inspection 2026-06-12, target 2026-11-01, closed 2026-11-10".
    static String describe(SireObservation o) {
        StringBuilder text = new StringBuilder("Observation ").append(o.getQuestionNumber())
                .append(" (").append(o.getType().name().toLowerCase(java.util.Locale.ROOT)).append(")")
                .append(" of the inspection ").append(o.getInspection().getInspectionDate());
        if (o.getOwner() != null) {
            text.append(", owner ").append(o.getOwner().getFullName());
        }
        if (o.getTargetDate() != null) {
            text.append(", target ").append(o.getTargetDate());
        }
        text.append(o.getClosedOn() == null ? ", open" : ", closed " + o.getClosedOn());
        return text.toString();
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
