package com.nereonix.nereonet_mvp.crew;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.invitation.InvitationService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

// Crew members (every ship role) and office users. Users are deactivated first and can then be deleted.
@Service
public class CrewService {

    private final UserRepository userRepository;
    private final ShipRepository shipRepository;
    private final UserService userService;
    private final InvitationRepository invitationRepository;
    private final InvitationService invitationService;
    private final AuditService auditService;
    private final AuditRepository auditRepository;
    private final CertificateRepository certificateRepository;

    public CrewService(UserRepository userRepository, ShipRepository shipRepository, UserService userService,
                       InvitationRepository invitationRepository, InvitationService invitationService,
                       AuditService auditService, AuditRepository auditRepository,
                       CertificateRepository certificateRepository) {
        this.userRepository = userRepository;
        this.shipRepository = shipRepository;
        this.userService = userService;
        this.invitationRepository = invitationRepository;
        this.invitationService = invitationService;
        this.auditService = auditService;
        this.auditRepository = auditRepository;
        this.certificateRepository = certificateRepository;
    }

    /** Users with a ship role, all ships or one ship (shipId may be null). */
    @Transactional(readOnly = true)
    public List<User> findCrew(Long shipId) {
        if (shipId == null) {
            return userRepository.findAllByRoleNotOrderByFullNameAsc(Role.OFFICE_USER);
        }
        return shipRepository.findById(shipId)
                .map(userRepository::findAllByShipOrderByFullNameAsc)
                .orElse(List.of());
    }

    /** PENDING invitations for ship roles, all ships or one ship (shipId may be null). Shown as "Invited". */
    @Transactional(readOnly = true)
    public List<Invitation> findInvited(Long shipId) {
        if (shipId == null) {
            return invitationRepository.findAllByStatusAndRoleNotOrderByFullNameAsc(Invitation.Status.PENDING,
                    Role.OFFICE_USER);
        }
        return shipRepository.findById(shipId)
                .map(ship -> invitationRepository.findAllByStatusAndShipOrderByFullNameAsc(Invitation.Status.PENDING,
                        ship))
                .orElse(List.of());
    }

    /** findCrew with the Crew list's Status filter (P17): enabled users for ACTIVE, the others for INACTIVE, none for
     *  the invitation statuses; every user when status is null. */
    @Transactional(readOnly = true)
    public List<User> findCrew(Long shipId, CrewStatus status) {
        if (status == null) {
            return findCrew(shipId);
        }
        if (!status.users()) {
            return List.of();
        }
        boolean enabled = status == CrewStatus.ACTIVE;
        return findCrew(shipId).stream().filter(user -> user.isEnabled() == enabled).toList();
    }

    /** findInvited with the Status filter: not yet expired for INVITED, past expiry for EXPIRED, none for the user
     *  statuses; every pending invitation when status is null. */
    @Transactional(readOnly = true)
    public List<Invitation> findInvited(Long shipId, CrewStatus status) {
        if (status == null) {
            return findInvited(shipId);
        }
        if (status.users()) {
            return List.of();
        }
        Invitation.DisplayStatus wanted = status == CrewStatus.EXPIRED
                ? Invitation.DisplayStatus.EXPIRED : Invitation.DisplayStatus.PENDING;
        return findInvited(shipId).stream().filter(invitation -> invitation.getDisplayStatus() == wanted).toList();
    }

    /**
     * Adds a crew member by inviting them: they get an email and set their own password. The role is the rank's
     * (Rank.role(), P18). Throws EmailAlreadyRegisteredException or ShipRoleTakenException (see InvitationService.invite).
     */
    @Transactional
    public Invitation addCrewMember(String fullName, String email, Rank rank, Long shipId, String actorEmail) {
        // Records INVITATION_SENT in the audit log.
        return invitationService.invite(email, fullName, rank.role(), shipId, rank, actorEmail);
    }


    @Transactional(readOnly = true)
    public List<User> findOfficeUsers() {
        return userRepository.findAllByRoleOrderByFullNameAsc(Role.OFFICE_USER);
    }

    // For the crew list filter: crew can still be on an inactive ship.
    @Transactional(readOnly = true)
    public List<Ship> findAllShips() {
        return shipRepository.findAllByOrderByNameAsc();
    }

    // For the edit form: crew can only be moved to an active ship.
    @Transactional(readOnly = true)
    public List<Ship> findActiveShips() {
        return shipRepository.findAllByOrderByNameAsc().stream().filter(Ship::isActive).toList();
    }

    // 404 if there is no such user or it is an office user.
    @Transactional(readOnly = true)
    public User getCrewMember(Long id) {
        return userRepository.findById(id)
                .filter(user -> user.getRole() != Role.OFFICE_USER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // Any user, crew or office (the deactivate and delete confirmations). 404 if there is none.
    @Transactional(readOnly = true)
    public User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // For the edit page. They are added, edited and deleted in the certificate feature.
    @Transactional(readOnly = true)
    public List<Certificate> findCertificates(Long userId) {
        return certificateRepository.findAllByUserIdOrderByExpiresOnAsc(userId);
    }

    // The UTC date, for the expiry badges.
    public LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /**
     * Saves a crew member. Changing the ship moves them to that ship (it must be active). The role becomes the rank's
     * (Rank.role(), P18), also for a user whose rank and role disagreed until now.
     * Throws ShipRoleTakenException if the ship already has an enabled MASTER or CHIEF_COOK and that is the role.
     */
    @Transactional
    public void update(Long id, String fullName, Rank rank, Long shipId) {
        User user = getCrewMember(id);
        Role role = rank.role();
        Ship ship = shipRepository.findById(shipId).filter(Ship::isActive)
                .orElseThrow(() -> new IllegalArgumentException("No active ship " + shipId));
        userService.checkShipRoleFree(ship, role, user);

        Ship oldShip = user.getShip();
        Role oldRole = user.getRole();
        user.setFullName(fullName.trim());
        user.setRank(rank);
        user.setRole(role);
        user.setShip(ship);

        // e.g. "jane@example.com: CREW_MEMBER, SECOND_OFFICER, moved from MV Demo"; "role MASTER -> CREW_MEMBER" when
        // the rank changed the role.
        String details = user.getEmail() + ": " + role
                + ", " + user.getRank()
                + (oldRole == role ? "" : ", role " + oldRole + " -> " + role)
                + (oldShip.getId().equals(ship.getId()) ? "" : ", moved from " + oldShip.getName());
        auditService.record("USER_UPDATED", ship, details);
    }

    /**
     * Activates or deactivates any user, crew or office. A user cannot deactivate themselves.
     * Returns the user.
     */
    @Transactional
    public User setEnabled(Long id, boolean enabled, String actorEmail) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!enabled && user.getEmail().equals(actorEmail)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A user cannot deactivate themselves");
        }
        user.setEnabled(enabled);
        auditService.record(enabled ? "USER_ACTIVATED" : "USER_DEACTIVATED", user.getShip(), user.getEmail());
        return user;
    }

    /**
     * Deletes a deactivated user, crew or office, and every invitation for their email, so they can be
     * invited again. Their certificates, notifications and announcement recipient rows are deleted by the database (cascade);
     * announcements they sent stay, with no sender. Their audit entries stay (shown as "Deleted user"); invitations they sent stay, with
     * no sender. A user cannot delete themselves. Returns the deleted user.
     */
    @Transactional
    public User delete(Long id, String actorEmail) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.isEnabled() || user.getEmail().equals(actorEmail)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only another, deactivated user can be deleted");
        }
        invitationRepository.deleteByEmail(user.getEmail());
        invitationRepository.clearInvitedBy(user);
        auditRepository.detachActor(user.getId());
        userRepository.delete(user);
        auditService.record("USER_DELETED", user.getShip(), user.getEmail() + " (" + user.getRole() + ")");
        return user;
    }
}
