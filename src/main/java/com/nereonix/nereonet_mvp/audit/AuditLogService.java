package com.nereonix.nereonet_mvp.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

// Reads the audit log. Entries are written by core AuditService only; ListQueries only reads them.
@Service
public class AuditLogService {

    private final ListQueries listQueries;
    private final ShipRepository shipRepository;

    public AuditLogService(ListQueries listQueries, ShipRepository shipRepository) {
        this.listQueries = listQueries;
        this.shipRepository = shipRepository;
    }

    /**
     * One page of the audit log (P19), paginated in the database. shipId, from and to may be null; from and to are
     * UTC days, both inclusive. The search is who did it (name or email) or the details. Sort keys: when (default,
     * newest first), who, action, ship.
     */
    @Transactional(readOnly = true)
    public Page<AuditEntry> search(Long shipId, LocalDate from, LocalDate to, ListQuery list) {
        return listQueries.page(AuditEntry.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            Expression<Instant> occurredAt = root.get("occurredAt");
            if (shipId != null) {
                where.add(cb.equal(root.get("ship").get("id"), shipId));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(occurredAt, from.atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            if (to != null) {
                where.add(cb.lessThan(occurredAt, to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            String q = list.getContains();
            if (q != null) {
                Join<AuditEntry, User> actor = root.join("actor", JoinType.LEFT);
                where.add(cb.or(ListQueries.contains(cb, actor.get("fullName"), q),
                        ListQueries.contains(cb, actor.get("email"), q),
                        ListQueries.contains(cb, root.get("details"), q)));
            }
            return where;
        }, (root, cb) -> {
            Expression<Instant> occurredAt = root.get("occurredAt");
            return switch (list.getSort()) {
                case "who" -> List.of(cb.lower(root.join("actor", JoinType.LEFT).get("fullName")), occurredAt);
                case "action" -> List.of(root.get("action"), occurredAt);
                case "ship" -> List.of(cb.lower(root.join("ship", JoinType.LEFT).get("name")), occurredAt);
                default -> List.of(occurredAt);
            };
        }, root -> {
            root.fetch("actor", JoinType.LEFT);
            root.fetch("ship", JoinType.LEFT);
        });
    }

    // Every ship, active or not, for the ship filter.
    @Transactional(readOnly = true)
    public List<Ship> findShips() {
        return shipRepository.findAllByOrderByNameAsc();
    }
}
