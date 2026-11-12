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
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

// Approved item requests as the office sees them, and their CSV export.
@Service
public class OfficeRequestService {

    private static final String[] CSV_HEADER =
            {"imo_number", "ship_name", "request_id", "approved_at", "approved_by", "item_name", "unit", "quantity"};

    public record CsvFile(String filename, byte[] content) {
    }

    private final ItemRequestRepository requestRepository;
    private final ShipRepository shipRepository;
    private final AuditService auditService;
    private final ListQueries listQueries;

    public OfficeRequestService(ItemRequestRepository requestRepository, ShipRepository shipRepository,
                                AuditService auditService, ListQueries listQueries) {
        this.requestRepository = requestRepository;
        this.shipRepository = shipRepository;
        this.auditService = auditService;
        this.listQueries = listQueries;
    }

    // "12" or "#12": a request number.
    private static final Pattern REQUEST_NUMBER = Pattern.compile("#?(\\d{1,18})");

    /**
     * One page of the approved requests (P19), paginated in the database. shipId null means every ship. The search
     * is a request number ("12" or "#12"), who requested or approved it, or the ship's name or IMO number. Sort keys:
     * approvedAt (default, newest first), number, ship, requestedBy, approvedBy.
     */
    @Transactional(readOnly = true)
    public Page<ItemRequest> findApproved(Long shipId, boolean notExported, ListQuery list) {
        return listQueries.page(ItemRequest.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("status"), RequestStatus.APPROVED));
            if (shipId != null) {
                where.add(cb.equal(root.get("ship").get("id"), shipId));
            }
            if (notExported) {
                where.add(cb.isNull(root.get("exportedAt")));
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
     * CSV of the approved requests among ids, one row per request line, UTF-8 with a BOM (so Excel reads
     * Greek names correctly). Sets exportedAt on them; a request can be exported again.
     * Ids that are not approved requests are ignored.
     */
    @Transactional
    public CsvFile export(List<Long> ids) {
        List<ItemRequest> requests =
                requestRepository.findAllByIdInAndStatusOrderByDecidedAtDescIdDesc(ids, RequestStatus.APPROVED);
        Instant now = Instant.now();
        StringWriter csv = new StringWriter();
        csv.write('﻿');
        int lineCount = 0;
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
                    lineCount++;
                }
                request.setExportedAt(now);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // never happens with a StringWriter
        }

        // One audit entry per export, with the ship when all requests are from one ship.
        List<Ship> ships = requests.stream().map(ItemRequest::getShip).distinct().toList();
        auditService.record("REQUESTS_EXPORTED", ships.size() == 1 ? ships.getFirst() : null,
                requests.size() + " requests, " + lineCount + " lines, ids "
                        + requests.stream().map(r -> r.getId().toString()).toList());

        String filename = "approved-requests-" + LocalDate.ofInstant(now, ZoneOffset.UTC) + ".csv";
        return new CsvFile(filename, csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
