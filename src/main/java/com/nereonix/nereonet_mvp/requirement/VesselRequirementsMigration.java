package com.nereonix.nereonet_mvp.requirement;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.certificate.CertificateTypes;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirement;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirementRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;

/**
 * Required documents move from vessel types to vessels. Before, a row had a vessel type (null: all vessels) and
 * no vessel; Hibernate adds document_requirement.ship_id (null on those rows). On startup, in one transaction, every
 * vessel (active or not) gets its own copy of the rules that applied to it: its type's rows and the all-vessels rows
 * (a vessel without a type: the all-vessels rows), merged per certificate type (CertificateTypes.sameTypeKey) as RequirementRules
 * merged them, so no seafarer's compliance changes:
 * <ul>
 *   <li>ranks: those for which any of the rows is required (not optional); when none is, the ranks of the optional
 *   rows, and the row stays optional. A rank required on one level and optional on the other stays required, as
 *   before (an optional document never counted);</li>
 *   <li>the note: the vessel type's, else the all-vessels one; suggestion: only when every row still was one;</li>
 *   <li>a vessel that already has a row of that type keeps its own (a run that stopped half way can run again).</li>
 * </ul>
 * Then the old rows are deleted, so it runs once; nothing to do on a new database. Recorded as REQUIREMENTS_MIGRATED
 * ("11 rules by vessel type copied to 4 vessels as 41 rows"). Runs before StarterRequirements.
 */
@Order(1)
@Component
public class VesselRequirementsMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VesselRequirementsMigration.class);

    private final DocumentRequirementRepository requirementRepository;
    private final ShipRepository shipRepository;
    private final AuditService auditService;

    public VesselRequirementsMigration(DocumentRequirementRepository requirementRepository,
                                       ShipRepository shipRepository, AuditService auditService) {
        this.requirementRepository = requirementRepository;
        this.shipRepository = shipRepository;
        this.auditService = auditService;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** Copies the rows without a vessel to the vessels they applied to, then deletes them. Returns the rows added. */
    @Transactional
    public int migrate() {
        List<DocumentRequirement> legacy = requirementRepository.findLegacyWithRanks();
        if (legacy.isEmpty()) {
            return 0;
        }
        List<Ship> vessels = shipRepository.findAllByOrderByNameAsc();
        List<DocumentRequirement> added = new ArrayList<>();
        for (Ship vessel : vessels) {
            Map<String, DocumentRequirement> own = new LinkedHashMap<>();
            requirementRepository.findAllByShipIdWithRanks(vessel.getId())
                    .forEach(r -> own.putIfAbsent(CertificateTypes.sameTypeKey(r.getCertificateType()), r));
            // The rows that applied to this vessel, by type: all vessels first, then its type's (oldest first).
            Map<String, List<DocumentRequirement>> byType = new LinkedHashMap<>();
            legacy.stream().filter(r -> r.getShipType() == null).forEach(r -> byType
                    .computeIfAbsent(CertificateTypes.sameTypeKey(r.getCertificateType()), k -> new ArrayList<>()).add(r));
            legacy.stream().filter(r -> r.getShipType() != null && r.getShipType() == vessel.getType()).forEach(r -> byType
                    .computeIfAbsent(CertificateTypes.sameTypeKey(r.getCertificateType()), k -> new ArrayList<>()).add(r));
            for (Map.Entry<String, List<DocumentRequirement>> entry : byType.entrySet()) {
                if (!own.containsKey(entry.getKey())) {
                    added.add(merged(vessel, entry.getValue()));
                }
            }
        }
        requirementRepository.saveAll(added);
        requirementRepository.deleteAll(legacy);
        String details = legacy.size() + " rules by vessel type copied to " + vessels.size() + " vessels as "
                + added.size() + " rows";
        auditService.record("REQUIREMENTS_MIGRATED", null, details);
        log.info("Required documents moved to vessels: {}", details);
        return added.size();
    }

    // One type's rows that applied to a vessel (all vessels first), as that vessel's one row.
    private static DocumentRequirement merged(Ship vessel, List<DocumentRequirement> rows) {
        Set<Rank> required = EnumSet.noneOf(Rank.class);
        Set<Rank> optional = EnumSet.noneOf(Rank.class);
        for (DocumentRequirement r : rows) {
            (r.isOptional() ? optional : required).addAll(r.getRanks());
        }
        optional.removeAll(required);
        DocumentRequirement row = new DocumentRequirement();
        row.setCertificateType(rows.getFirst().getCertificateType());
        row.setShip(vessel);
        if (!required.isEmpty()) {
            row.setRanks(required);
            row.setOptional(false);
        } else {
            row.setRanks(optional);
            row.setOptional(rows.stream().allMatch(DocumentRequirement::isOptional));
        }
        // The vessel type's own note wins over the all-vessels one (the rows come all vessels first).
        String note = null;
        for (DocumentRequirement r : rows) {
            if (r.getNote() != null && (note == null || r.getShipType() != null)) {
                note = r.getNote();
            }
        }
        row.setNote(note);
        row.setSuggestion(rows.stream().allMatch(DocumentRequirement::isSuggestion));
        return row;
    }
}
