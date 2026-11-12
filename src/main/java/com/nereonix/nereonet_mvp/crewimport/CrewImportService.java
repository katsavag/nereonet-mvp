package com.nereonix.nereonet_mvp.crewimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.importfile.ImportAction;
import com.nereonix.nereonet_mvp.core.importfile.ImportField;
import com.nereonix.nereonet_mvp.core.importfile.ImportMappingService;
import com.nereonix.nereonet_mvp.core.importfile.ImportNames;
import com.nereonix.nereonet_mvp.core.importfile.ImportResult;
import com.nereonix.nereonet_mvp.core.importfile.ImportSheet;
import com.nereonix.nereonet_mvp.core.importfile.ImportTarget;
import com.nereonix.nereonet_mvp.core.importfile.ImportUpload;
import com.nereonix.nereonet_mvp.core.invitation.InvitationService;
import com.nereonix.nereonet_mvp.core.ship.ImoNumber;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.ShipRoleTakenException;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

import jakarta.validation.Validator;

// Crew list import (P24): map the columns of any crew file (the template, or a crewing system's export), validate
// every row, then invite the included rows. Every row is a new crew member (an invitation); an email that already has
// a user is an error. Nothing is saved and no email is sent before importRows. Never log file contents.
@Service
public class CrewImportService implements ImportTarget<CrewImportForm> {

    static final String FULL_NAME = "fullName";
    static final String EMAIL = "email";
    static final String RANK = "rank";
    static final String SHIP = "shipImo";

    // Column names as crewing systems and spreadsheets write them, normalized (ImportNames.normalize), most likely
    // first. The template's own names come first.
    private static final List<ImportField> FIELDS = List.of(
            ImportField.of(FULL_NAME, "user.fullName", true,
                            List.of("full name", "seafarer name", "crew name", "employee name", "seaman name",
                                    "name and surname", "name surname", "seafarer", "seaman", "employee",
                                    "crew member", "ονοματεπωνυμο"))
                    .withTwoColumns(
                            List.of("first name", "given name", "given names", "forename", "first names", "name",
                                    "ονομα"),
                            List.of("last name", "surname", "family name", "επωνυμο")),
            ImportField.of(EMAIL, "user.email", true,
                    List.of("email", "e mail", "email address", "e mail address", "mail", "personal email",
                            "email personal", "ηλεκτρονικο ταχυδρομειο")),
            ImportField.of(RANK, "user.rank", true,
                    List.of("rank", "position", "capacity", "rank position", "job title", "duty", "title", "βαθμος",
                            "ειδικοτητα", "θεση")).withValues(RankMatcher.INSTANCE),
            ImportField.of(SHIP, "crewImport.field.ship", true,
                    List.of("ship imo", "imo", "imo number", "imo no", "vessel imo", "imo nr", "ship imo number",
                            "vessel imo number", "ship", "vessel", "vessel name", "ship name", "πλοιο")));

    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final InvitationService invitationService;
    private final AuditService auditService;
    private final ImportMappingService mappingService;
    private final MessageSource messageSource;
    private final Validator validator;

    public CrewImportService(ShipRepository shipRepository, UserRepository userRepository, UserService userService,
                             InvitationService invitationService, AuditService auditService,
                             ImportMappingService mappingService, MessageSource messageSource, Validator validator) {
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.invitationService = invitationService;
        this.auditService = auditService;
        this.mappingService = mappingService;
        this.messageSource = messageSource;
        this.validator = validator;
    }

    @Override
    public String kind() {
        return "crew";
    }

    @Override
    public List<ImportField> fields() {
        return FIELDS;
    }

    @Override
    public List<String> templateHeader() {
        return CrewTemplate.HEADERS;
    }

    /** The blank template, with the instructions in the given language. */
    @Transactional(readOnly = true)
    public byte[] template(Locale locale) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CrewTemplate.write(out, List.of(), findActiveShips(), messageSource, locale);
        return out.toByteArray();
    }

    /**
     * One row per data row of the file, with the mapped columns. The rank is the matched rank (exact, or as chosen in
     * the value mapping); the ship is matched by IMO number or by exact name (ignoring case) of one active ship.
     */
    @Override
    @Transactional(readOnly = true)
    public void buildRows(CrewImportForm form) {
        ImportUpload upload = form.getUpload();
        List<Ship> ships = findActiveShips();
        List<CrewImportRow> rows = new ArrayList<>();
        for (ImportSheet.Row fileRow : upload.dataRows()) {
            CrewImportRow row = new CrewImportRow();
            row.setRowNumber(fileRow.number());
            row.setFullName(upload.value(fileRow, field(FULL_NAME)));
            row.setEmail(upload.value(fileRow, field(EMAIL)));
            row.setRank(upload.matchedValue(fileRow, field(RANK)));
            row.setShipImo(shipImo(upload.value(fileRow, field(SHIP)), ships));
            rows.add(row);
        }
        // P18: a Role column (the template from before) is ignored; the review page says so.
        List<String> header = upload.header();
        boolean roleIgnored = false;
        for (int i = 0; i < header.size(); i++) {
            if (ImportNames.normalize(header.get(i)).equals("role") && !upload.getColumns().containsValue(i)) {
                roleIgnored = true;
            }
        }
        form.setRoleColumnIgnored(roleIgnored);
        form.rowsBuilt(rows);
    }

    /** Checks every included row and sets its errors. Excluded rows get none. Every row creates an invitation. */
    @Override
    @Transactional(readOnly = true)
    public void validate(CrewImportForm form) {
        Map<String, Ship> ships = findActiveShips().stream()
                .collect(Collectors.toMap(Ship::getImoNumber, Function.identity()));
        List<CrewImportRow> included = form.getIncludedRows();
        Map<String, Long> emailCounts = included.stream()
                .collect(Collectors.groupingBy(row -> normalizeEmail(row.getEmail()), Collectors.counting()));
        // Only the one-per-ship roles (MASTER, CHIEF_COOK), from the rank.
        Map<String, Long> shipRoleCounts = included.stream()
                .filter(row -> row.getRole() != null && row.getRole().isOnePerShip())
                .collect(Collectors.groupingBy(row -> row.getShipImo() + "/" + row.getRole(), Collectors.counting()));

        for (CrewImportRow row : form.getRows()) {
            row.getErrors().clear();
            row.setAction(ImportAction.CREATE);
            if (row.isExcluded()) {
                continue;
            }
            Map<String, String> errors = row.getErrors();

            if (row.getFullName().isBlank()) {
                errors.put("fullName", "user.error.fullNameRequired");
            } else if (!validator.validateProperty(row, "fullName").isEmpty()) {
                errors.put("fullName", "crewImport.error.tooLong");
            }

            String email = normalizeEmail(row.getEmail());
            if (email.isEmpty()) {
                errors.put("email", "invitation.error.emailRequired");
            } else if (!validator.validateProperty(row, "email").isEmpty()) {
                errors.put("email", "invitation.error.emailInvalid");
            } else if (userRepository.existsByEmail(email)) {
                errors.put("email", "invitation.error.userExists");
            } else if (emailCounts.get(email) > 1) {
                errors.put("email", "crewImport.error.emailRepeated");
            }

            // The rank sets the role (P18), so it is required.
            Role role = row.getRole();
            if (row.getRank().isEmpty()) {
                errors.put("rank", "user.error.rankRequired");
            } else if (role == null) {
                errors.put("rank", "crewImport.error.rankUnknown");
            }

            Ship ship = ships.get(row.getShipImo());
            if (row.getShipImo().isEmpty()) {
                errors.put("shipImo", "user.error.shipRequired");
            } else if (ship == null) {
                errors.put("shipImo", "crewImport.error.shipUnknown");
            }

            // On the rank, which sets the role.
            if (role != null && ship != null && role.isOnePerShip()) {
                if (shipRoleCounts.get(row.getShipImo() + "/" + role) > 1) {
                    errors.put("rank", "crewImport.error.shipRoleRepeated");
                } else {
                    try {
                        userService.checkShipRoleFree(ship, role, null);
                    } catch (ShipRoleTakenException e) {
                        errors.put("rank", "user.error.shipRoleTaken");
                    }
                }
            }
        }
    }

    /**
     * Validates again, invites every included row (one email each) and saves the confirmed mapping (not for a file
     * made from the template, which needs none). Throws IllegalStateException if a row has errors: the review screen
     * only allows this when there are none.
     */
    @Override
    @Transactional
    public ImportResult importRows(CrewImportForm form, String actorEmail) {
        validate(form);
        if (!form.isReady()) {
            throw new IllegalStateException("The crew import has errors or no rows to import");
        }
        Map<String, Ship> ships = findActiveShips().stream()
                .collect(Collectors.toMap(Ship::getImoNumber, Function.identity()));
        List<CrewImportRow> included = form.getIncludedRows();
        for (CrewImportRow row : included) {
            Rank rank = Rank.valueOf(row.getRank());
            // Records INVITATION_SENT for each row. The role is the rank's (P18).
            invitationService.invite(row.getEmail(), row.getFullName(), rank.role(),
                    ships.get(row.getShipImo()).getId(), rank, actorEmail);
        }
        if (!form.getUpload().isTemplate()) {
            mappingService.save(kind(), form.getUpload());
        }
        // Counts only, never file contents.
        auditService.record("CREW_IMPORTED", null, included.size() + " invited of " + form.getRows().size()
                + " rows (" + form.getExcludedCount() + " excluded)");
        return new ImportResult(included.size(), 0, form.getExcludedCount());
    }

    @Transactional(readOnly = true)
    public List<Ship> findActiveShips() {
        return shipRepository.findAllByOrderByNameAsc().stream().filter(Ship::isActive).toList();
    }

    // The IMO number of the active ship the value names: by IMO number ("IMO 907 4729"), or by its exact name when
    // only one active ship has it. Otherwise the value as in the file (an IMO number without spaces).
    static String shipImo(String value, List<Ship> ships) {
        String imo = ImoNumber.normalize(value);
        if (ships.stream().anyMatch(ship -> ship.getImoNumber().equals(imo))) {
            return imo;
        }
        List<Ship> named = ships.stream().filter(ship -> ship.getName().strip().equalsIgnoreCase(value.strip()))
                .toList();
        if (named.size() == 1) {
            return named.getFirst().getImoNumber();
        }
        return imo.matches("[0-9]*") ? imo : value.strip();
    }

    private static ImportField field(String key) {
        return FIELDS.stream().filter(field -> field.key().equals(key)).findFirst().orElseThrow();
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
