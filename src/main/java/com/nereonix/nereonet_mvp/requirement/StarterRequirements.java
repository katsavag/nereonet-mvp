package com.nereonix.nereonet_mvp.requirement;

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
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirementRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Rank;

/**
 * The starter template of required documents (P42), a suggestion for the office to review, never a statement of what
 * any regulation requires. P58: it is the defaults of each vessel type (defaults(type)): every vessel starts with
 * the defaults of its type, copied when it is created (RequirementService.startWithDefaults) and marked as a
 * suggestion until the office saves that vessel's grid; "Reset to Bulk carrier defaults" copies them again. There is
 * no screen to edit the defaults.
 * On startup, when there is no requirement at all, the template was never added (the audit entry REQUIREMENTS_SEEDED)
 * and there are vessels, every vessel gets the defaults of its type, so a template the office cleared stays clear.
 * Runs after the dev data (DevData, @Order(2)), which adds the demo vessels, and after VesselRequirementsMigration.
 * app.requirements.starter-template=false (the tests) leaves this startup step out; RequirementsFlowTest calls seed().
 * The rows: STCW Basic Safety Training, Medical Fitness Certificate, Seaman's Book, Passport and Security Awareness for
 * every rank; Certificate of Competency and Flag State Endorsement for officers and engineers (OFFICERS); GMDSS Operator
 * for the Master and the deck officers; Advanced Fire Fighting and Medical First Aid for officers (OFFICERS); Tanker
 * Endorsement for every rank on tankers.
 */
@Order(3)
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

    /** One default requirement: the certificate type and the ranks that need it (required, no note). */
    public record Default(String type, Set<Rank> ranks) {
    }

    // shipType null: the default of every vessel type, and of vessels without a type.
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
    private final ShipRepository shipRepository;
    private final RequirementService requirementService;
    private final AuditRepository auditRepository;
    private final AuditService auditService;
    private final boolean enabled;

    public StarterRequirements(DocumentRequirementRepository requirementRepository, ShipRepository shipRepository,
                               RequirementService requirementService, AuditRepository auditRepository,
                               AuditService auditService,
                               @Value("${app.requirements.starter-template:true}") boolean enabled) {
        this.requirementRepository = requirementRepository;
        this.shipRepository = shipRepository;
        this.requirementService = requirementService;
        this.auditRepository = auditRepository;
        this.auditService = auditService;
        this.enabled = enabled;
    }

    /** The defaults of a vessel type (null: a vessel without a type), in the template's order. */
    public static List<Default> defaults(ShipType type) {
        return ROWS.stream().filter(r -> r.shipType() == null || r.shipType() == type)
                .map(r -> new Default(r.type(), EnumSet.copyOf(r.ranks()))).toList();
    }

    /** The certificate types of the template, every vessel type, in its order (the grid lists them first). */
    public static List<String> types() {
        return ROWS.stream().map(Row::type).toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) {
            seed();
        }
    }

    /**
     * Gives every vessel the defaults of its type, unless a requirement exists, the template was added before or
     * there is no vessel yet. Returns whether it was added.
     */
    @Transactional
    public boolean seed() {
        if (requirementRepository.count() > 0 || auditRepository.existsByAction(SEEDED)) {
            return false;
        }
        List<Ship> vessels = shipRepository.findAllByOrderByNameAsc();
        if (vessels.isEmpty()) {
            return false;
        }
        int rows = 0;
        for (Ship vessel : vessels) {
            rows += requirementService.startWithDefaults(vessel);
        }
        auditService.record(SEEDED, null, rows + " suggested requirements on " + vessels.size() + " vessels", null);
        log.info("Added the starter template of required documents ({} rows on {} vessels, to be reviewed)", rows,
                vessels.size());
        return true;
    }
}
