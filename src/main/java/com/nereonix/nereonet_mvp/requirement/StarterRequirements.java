package com.nereonix.nereonet_mvp.requirement;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirement;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirementRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Rank;

/**
 * The starter template of required documents (P42), added once on startup: a suggestion for the office to review,
 * never a statement of what any regulation requires. Every row is marked as a suggestion until the office saves its
 * vessel type. Added when there is no requirement yet and it was never added before (the audit entry
 * REQUIREMENTS_SEEDED), so a template the office cleared stays clear. app.requirements.starter-template=false (the
 * tests) leaves it out; RequirementsFlowTest adds it with seed().
 * The rows: STCW Basic Safety Training, Medical Fitness Certificate, Seaman's Book, Passport and Security Awareness for
 * every rank; Certificate of Competency and Flag State Endorsement for officers and engineers (OFFICERS); GMDSS Operator
 * for the Master and the deck officers; Advanced Fire Fighting and Medical First Aid for officers (OFFICERS); Tanker
 * Endorsement for every rank on tankers.
 */
@Order(1)
@Component
public class StarterRequirements implements ApplicationRunner {

    static final String SEEDED = "REQUIREMENTS_SEEDED";

    private static final Logger log = LoggerFactory.getLogger(StarterRequirements.class);

    /** The Master, the deck officers, the engineers and the electro-technical officer; cadets and ratings are not. */
    static final Set<Rank> OFFICERS = EnumSet.of(Rank.MASTER, Rank.CHIEF_OFFICER, Rank.SECOND_OFFICER,
            Rank.THIRD_OFFICER, Rank.CHIEF_ENGINEER, Rank.SECOND_ENGINEER, Rank.THIRD_ENGINEER, Rank.FOURTH_ENGINEER,
            Rank.ELECTRO_TECHNICAL_OFFICER);
    static final Set<Rank> DECK_OFFICERS = EnumSet.of(Rank.MASTER, Rank.CHIEF_OFFICER, Rank.SECOND_OFFICER,
            Rank.THIRD_OFFICER);

    private record Row(String type, ShipType shipType, Set<Rank> ranks) {
    }

    private static final List<Row> ROWS = List.of(
            new Row("STCW Basic Safety Training", null, EnumSet.allOf(Rank.class)),
            new Row("Medical Fitness Certificate", null, EnumSet.allOf(Rank.class)),
            new Row("Seaman's Book", null, EnumSet.allOf(Rank.class)),
            new Row("Passport", null, EnumSet.allOf(Rank.class)),
            new Row("Security Awareness", null, EnumSet.allOf(Rank.class)),
            new Row("Certificate of Competency", null, OFFICERS),
            new Row("Flag State Endorsement", null, OFFICERS),
            new Row("GMDSS Operator", null, DECK_OFFICERS),
            new Row("Advanced Fire Fighting", null, OFFICERS),
            new Row("Medical First Aid", null, OFFICERS),
            new Row("Tanker Endorsement", ShipType.TANKER, EnumSet.allOf(Rank.class)));

    private final DocumentRequirementRepository requirementRepository;
    private final AuditRepository auditRepository;
    private final AuditService auditService;
    private final boolean enabled;

    public StarterRequirements(DocumentRequirementRepository requirementRepository, AuditRepository auditRepository,
                               AuditService auditService,
                               @Value("${app.requirements.starter-template:true}") boolean enabled) {
        this.requirementRepository = requirementRepository;
        this.auditRepository = auditRepository;
        this.auditService = auditService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) {
            seed();
        }
    }

    /** Adds the starter template unless a requirement exists or it was added before. Returns whether it was added. */
    @Transactional
    public boolean seed() {
        if (requirementRepository.count() > 0 || auditRepository.existsByAction(SEEDED)) {
            return false;
        }
        List<DocumentRequirement> rows = new ArrayList<>();
        for (Row row : ROWS) {
            DocumentRequirement r = new DocumentRequirement();
            r.setCertificateType(row.type());
            r.setShipType(row.shipType());
            r.setRanks(EnumSet.copyOf(row.ranks()));
            r.setSuggestion(true);
            rows.add(r);
        }
        requirementRepository.saveAll(rows);
        auditService.record(SEEDED, null, rows.size() + " suggested requirements", null);
        log.info("Added the starter template of required documents ({} rows, to be reviewed)", rows.size());
        return true;
    }
}
