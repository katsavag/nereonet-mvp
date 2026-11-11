package com.nereonix.nereonet_mvp.crewimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.importfile.TableFile;
import com.nereonix.nereonet_mvp.core.importfile.TableFileReader;
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

// Crew list import: read the uploaded template, validate every row, then invite the included rows.
// Nothing is saved and no email is sent before importAndInvite. Never log file contents.
@Service
public class CrewImportService {

    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final InvitationService invitationService;
    private final AuditService auditService;
    private final MessageSource messageSource;
    private final Validator validator;

    public CrewImportService(ShipRepository shipRepository, UserRepository userRepository, UserService userService,
                             InvitationService invitationService, AuditService auditService,
                             MessageSource messageSource, Validator validator) {
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.invitationService = invitationService;
        this.auditService = auditService;
        this.messageSource = messageSource;
        this.validator = validator;
    }

    /** The blank template, with the instructions in the given language. */
    @Transactional(readOnly = true)
    public byte[] template(Locale locale) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CrewTemplate.write(out, List.of(), findActiveShips(), messageSource, locale);
        return out.toByteArray();
    }

    /**
     * Reads an uploaded .xlsx or .csv file. Empty if its header row is not the template's
     * (ignoring case and surrounding spaces). The template from before P18, with a Role column, is accepted too: that
     * column is ignored, the rank sets the role, and the form says so (roleColumnIgnored).
     * Throws IOException if the file cannot be read.
     */
    public Optional<CrewImportForm> read(String filename, InputStream in) throws IOException {
        TableFile file = TableFileReader.read(filename, in);
        boolean withRole = file.hasHeader(CrewTemplate.HEADERS_WITH_ROLE);
        if (!withRole && !file.hasHeader(CrewTemplate.HEADERS)) {
            return Optional.empty();
        }
        int shipColumn = withRole ? 4 : 3;
        CrewImportForm form = new CrewImportForm();
        form.setRoleColumnIgnored(withRole);
        for (TableFile.Row fileRow : file.rows()) {
            CrewImportRow row = new CrewImportRow();
            row.setRowNumber(fileRow.number());
            row.setFullName(fileRow.cell(0));
            row.setEmail(fileRow.cell(1));
            row.setRank(canonical(fileRow.cell(2), Arrays.stream(Rank.values()).map(Rank::name).toList()));
            row.setShipImo(ImoNumber.normalize(fileRow.cell(shipColumn)));
            form.getRows().add(row);
        }
        return Optional.of(form);
    }

    /** Checks every included row and sets its errors. Excluded rows get no errors. */
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
     * Validates again and invites every included row (one email each). Returns the number of invitations.
     * Throws IllegalStateException if a row has errors: the review screen only allows this when there are none.
     */
    @Transactional
    public int importAndInvite(CrewImportForm form, String actorEmail) {
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
        // Counts only, never file contents.
        auditService.record("CREW_IMPORTED", null, included.size() + " invited of " + form.getRows().size()
                + " rows (" + form.getExcludedCount() + " excluded)");
        return included.size();
    }

    @Transactional(readOnly = true)
    public List<Ship> findActiveShips() {
        return shipRepository.findAllByOrderByNameAsc().stream().filter(Ship::isActive).toList();
    }

    // "Chief cook", "chief_cook" or "CHIEF COOK" -> "CHIEF_COOK" if that is one of names, else the value unchanged.
    private static String canonical(String value, List<String> names) {
        String key = value.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return names.contains(key) ? key : value;
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
