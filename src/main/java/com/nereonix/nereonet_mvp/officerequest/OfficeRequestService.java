package com.nereonix.nereonet_mvp.officerequest;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestExport;
import com.nereonix.nereonet_mvp.core.request.RequestExportRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

// Approved item requests as the office sees them, and their CSV export. An export (P21) is recorded (RequestExport),
// marks its requests exported with the time and the office user, and its file is downloaded from its own URL.
@Service
public class OfficeRequestService {

    private static final String[] CSV_HEADER =
            {"imo_number", "ship_name", "request_id", "approved_at", "approved_by", "item_name", "unit", "quantity"};

    public record CsvFile(String filename, byte[] content) {
    }

    private final ItemRequestRepository requestRepository;
    private final RequestExportRepository exportRepository;
    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ListQueries listQueries;

    public OfficeRequestService(ItemRequestRepository requestRepository, RequestExportRepository exportRepository,
                                ShipRepository shipRepository, UserRepository userRepository,
                                AuditService auditService, ListQueries listQueries) {
        this.requestRepository = requestRepository;
        this.exportRepository = exportRepository;
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.listQueries = listQueries;
    }

    // "12" or "#12": a request number.
    private static final Pattern REQUEST_NUMBER = Pattern.compile("#?(\\d{1,18})");

    /**
     * One page of the approved requests (P19), paginated in the database. shipId null means every ship; state: not
     * exported yet (the default), exported, or all (P21); export: only the requests of that export, or null. The
     * search is a request number ("12" or "#12"), who requested or approved it, or the ship's name or IMO number.
     * Sort keys: approvedAt (default, newest first), number, ship, requestedBy, approvedBy.
     */
    @Transactional(readOnly = true)
    public Page<ItemRequest> findApproved(Long shipId, ExportState state, RequestExport export, ListQuery list) {
        return listQueries.page(ItemRequest.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("status"), RequestStatus.APPROVED));
            if (shipId != null) {
                where.add(cb.equal(root.get("ship").get("id"), shipId));
            }
            switch (state) {
                case NOT_EXPORTED -> where.add(cb.isNull(root.get("exportedAt")));
                case EXPORTED -> where.add(cb.isNotNull(root.get("exportedAt")));
                case ALL -> {
                }
            }
            if (export != null) {
                where.add(export.getRequestIds().isEmpty() ? cb.disjunction()
                        : root.get("id").in(export.getRequestIds()));
            }
            String q = list.getContains();
            if (q != null) {
                Join<ItemRequest, Ship> ship = root.join("ship");
                List<Predicate> any = new ArrayList<>(List.of(
                        ListQueries.contains(cb, root.join("requestedBy", JoinType.LEFT).get("fullName"), q),
                        ListQueries.contains(cb, root.join("decidedBy", JoinType.LEFT).get("fullName"), q),
                        ListQueries.contains(cb, ship.get("name"), q),
                        ListQueries.contains(cb, ship.get("imoNumber"), q)));
                Matcher number = REQUEST_NUMBER.matcher(list.getQ());
                if (number.matches()) {
                    any.add(cb.equal(root.get("id"), Long.parseLong(number.group(1))));
                }
                where.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return where;
        }, (root, cb) -> {
            Expression<Instant> approvedAt = root.get("decidedAt");
            return switch (list.getSort()) {
                case "number" -> List.of(root.get("id"));
                case "ship" -> List.of(cb.lower(root.join("ship").get("name")), approvedAt);
                case "requestedBy" -> List.of(cb.lower(root.join("requestedBy", JoinType.LEFT).get("fullName")),
                        approvedAt);
                case "approvedBy" -> List.of(cb.lower(root.join("decidedBy", JoinType.LEFT).get("fullName")),
                        approvedAt);
                default -> List.of(approvedAt);
            };
        }, null);
    }

    // 404 if there is no such approved request.
    @Transactional(readOnly = true)
    public ItemRequest getApproved(Long id) {
        return requestRepository.findByIdAndStatus(id, RequestStatus.APPROVED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Ship> findAllShips() {
        return shipRepository.findAllByOrderByNameAsc();
    }

    /**
     * Whether there is any approved request, of one ship or (shipId null) every ship: an empty list then says "Every
     * approved request has been exported" rather than "No requests yet" (P25).
     */
    @Transactional(readOnly = true)
    public boolean anyApproved(Long shipId) {
        return shipId == null ? requestRepository.existsByStatus(RequestStatus.APPROVED)
                : requestRepository.countByStatusAndShipId(RequestStatus.APPROVED, shipId) > 0;
    }

    /** The approved requests not exported yet, of one ship or (shipId null) every ship: "Export all not exported (N)". */
    @Transactional(readOnly = true)
    public long countNotExported(Long shipId) {
        return shipId == null ? requestRepository.countByStatusAndExportedAtIsNull(RequestStatus.APPROVED)
                : requestRepository.countByStatusAndShipIdAndExportedAtIsNull(RequestStatus.APPROVED, shipId);
    }

    /** How many of the approved requests among ids were exported before: exporting them again asks first. */
    @Transactional(readOnly = true)
    public long countExported(List<Long> ids) {
        return requestRepository.findAllByIdInAndStatusOrderByDecidedAtDescIdDesc(ids, RequestStatus.APPROVED)
                .stream().filter(r -> r.getExportedAt() != null).count();
    }

    /** The export, if there is one with this id (null id: none). */
    @Transactional(readOnly = true)
    public Optional<RequestExport> findExport(Long id) {
        return id == null ? Optional.empty() : exportRepository.findById(id);
    }

    // 404 if there is no such export.
    @Transactional(readOnly = true)
    public RequestExport getExport(Long id) {
        return findExport(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Exports the approved requests among ids (ids that are not approved requests are ignored): records the export,
     * marks them exported now by userId (also those exported before: a request can be exported again) and writes
     * the audit entry. Empty when none of them is an approved request.
     */
    @Transactional
    public Optional<RequestExport> export(List<Long> ids, Long userId) {
        return record(requestRepository.findAllByIdInAndStatusOrderByDecidedAtDescIdDesc(ids, RequestStatus.APPROVED),
                userId);
    }

    /** Exports every approved request not exported yet, of one ship or (shipId null) every ship. Empty when none. */
    @Transactional
    public Optional<RequestExport> exportAllNotExported(Long shipId, Long userId) {
        return record(requestRepository.findNotExported(RequestStatus.APPROVED, shipId), userId);
    }

    private Optional<RequestExport> record(List<ItemRequest> requests, Long userId) {
        if (requests.isEmpty()) {
            return Optional.empty();
        }
        RequestExport export = new RequestExport();
        export.setCreatedAt(Instant.now());
        export.setCreatedBy(userRepository.getReferenceById(userId));
        requests.forEach(r -> export.getRequestIds().add(r.getId()));
        exportRepository.save(export);
        for (ItemRequest request : requests) {
            request.setExportedAt(export.getCreatedAt());
            request.setExportedBy(export.getCreatedBy());
        }

        // One audit entry per export, with the ship when all requests are from one ship.
        int lineCount = requests.stream().mapToInt(r -> r.getLines().size()).sum();
        List<Ship> ships = requests.stream().map(ItemRequest::getShip).distinct().toList();
        auditService.record("REQUESTS_EXPORTED", ships.size() == 1 ? ships.getFirst() : null,
                requests.size() + " requests, " + lineCount + " lines, ids "
                        + requests.stream().map(r -> r.getId().toString()).toList());
        return Optional.of(export);
    }

    /**
     * The file of an export: one row per line of its approved requests, newest approval first, UTF-8 with a BOM (so
     * Excel reads Greek names correctly). Downloading it changes nothing. A request deleted since is left out.
     */
    @Transactional(readOnly = true)
    public CsvFile csv(Long exportId) {
        RequestExport export = getExport(exportId);
        List<ItemRequest> requests = requestRepository.findAllByIdInAndStatusOrderByDecidedAtDescIdDesc(
                export.getRequestIds(), RequestStatus.APPROVED);
        StringWriter csv = new StringWriter();
        csv.write('﻿');
        try (CSVPrinter printer = new CSVPrinter(csv, CSVFormat.DEFAULT.builder().setHeader(CSV_HEADER).get())) {
            for (ItemRequest request : requests) {
                for (RequestLine line : request.getLines()) {
                    printer.printRecord(
                            request.getShip().getImoNumber(),
                            request.getShip().getName(),
                            request.getId(),
                            request.getDecidedAt().truncatedTo(ChronoUnit.SECONDS), // e.g. 2026-11-11T09:30:00Z
                            request.getDecidedBy() == null ? "" : request.getDecidedBy().getFullName(),
                            line.getItem().getName(),
                            line.getItem().getUnit(),
                            line.getQuantity().stripTrailingZeros().toPlainString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // never happens with a StringWriter
        }
        String filename = "approved-requests-" + LocalDate.ofInstant(export.getCreatedAt(), ZoneOffset.UTC) + ".csv";
        return new CsvFile(filename, csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
