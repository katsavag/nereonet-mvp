package com.nereonix.nereonet_mvp.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;

// Reads the audit log. Entries are written by core AuditService only.
@Service
public class AuditLogService {

    static final int PAGE_SIZE = 50;

    // Used when a date filter is empty.
    private static final Instant EARLIEST = Instant.EPOCH;
    private static final Instant LATEST = Instant.parse("3000-01-01T00:00:00Z");

    private final AuditRepository auditRepository;
    private final ShipRepository shipRepository;

    public AuditLogService(AuditRepository auditRepository, ShipRepository shipRepository) {
        this.auditRepository = auditRepository;
        this.shipRepository = shipRepository;
    }

    /** Newest first. shipId, from and to may be null; from and to are UTC days, both inclusive. */
    @Transactional(readOnly = true)
    public Page<AuditEntry> search(Long shipId, LocalDate from, LocalDate to, int page) {
        Instant start = from == null ? EARLIEST : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to == null ? LATEST : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return auditRepository.search(shipId, start, end, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    // Every ship, active or not, for the ship filter.
    @Transactional(readOnly = true)
    public List<Ship> findShips() {
        return shipRepository.findAllByOrderByNameAsc();
    }
}
