package com.nereonix.nereonet_mvp.core.invitation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

@Service
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ShipRepository shipRepository;
    private final InvitationTokenService tokenService;
    private final MailService mailService;
    private final MessageSource messageSource;
    private final AuditService auditService;
    private final String baseUrl;
    private final int validDays;

    public InvitationService(InvitationRepository invitationRepository,
                             UserRepository userRepository,
                             UserService userService,
                             ShipRepository shipRepository,
                             InvitationTokenService tokenService,
                             MailService mailService,
                             MessageSource messageSource,
                             AuditService auditService,
                             @Value("${app.base-url}") String baseUrl,
                             @Value("${app.invitation.valid-days:7}") int validDays) {
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.shipRepository = shipRepository;
        this.tokenService = tokenService;
        this.mailService = mailService;
        this.messageSource = messageSource;
        this.auditService = auditService;
        this.baseUrl = baseUrl;
        this.validDays = validDays;
    }

    /**
     * Saves a PENDING invitation and emails the registration link.
     * Replaces any PENDING invitation for the same email, so the old link stops working.
     * shipId must be null for OFFICE_USER and set for every other role.
     * rank is for ship roles only and may be null.
     * Throws EmailAlreadyRegisteredException if a user with that email exists, and
     * ShipRoleTakenException if the ship already has an enabled user with this role (MASTER, CHIEF_COOK).
     */
    @Transactional
    public Invitation invite(String email, String fullName, Role role, Long shipId, Rank rank,
                             String invitedByEmail) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (role == Role.OFFICE_USER && shipId != null) {
            throw new IllegalArgumentException("An office user cannot belong to a ship");
        }
        if (role != Role.OFFICE_USER && shipId == null) {
            throw new IllegalArgumentException("Role " + role + " requires a ship");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }
        // Inactive ships cannot get new crew.
        Ship ship = shipId == null ? null : shipRepository.findById(shipId).filter(Ship::isActive)
                .orElseThrow(() -> new IllegalArgumentException("No active ship " + shipId));
        if (ship != null) {
            userService.checkShipRoleFree(ship, role, null);
        }

        invitationRepository.deleteByEmailAndStatus(normalizedEmail, Invitation.Status.PENDING);

        Instant now = Instant.now();
        Invitation invitation = new Invitation();
        invitation.setEmail(normalizedEmail);
        invitation.setFullName(fullName.trim());
        invitation.setRole(role);
        invitation.setShip(ship);
        invitation.setRank(ship == null ? null : rank);
        invitation.setTokenId(UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        invitation.setExpiresAt(now.plus(Duration.ofDays(validDays)));
        invitation.setInvitedBy(userRepository.findByEmail(invitedByEmail).orElseThrow());
        invitation.setCreatedAt(now);
        invitationRepository.save(invitation);

        sendEmail(invitation);
        // Never the link or token.
        auditService.record("INVITATION_SENT", invitation.getShip(), normalizedEmail + " as " + role);

        return invitation;
    }

    /**
     * Gives a PENDING invitation (expired or not) a new tokenId and expiresAt and emails the new link.
     * The old link stops working. Returns false if there is no PENDING invitation with this id.
     */
    @Transactional
    public boolean resend(Long id) {
        Optional<Invitation> found = findPending(id);
        if (found.isEmpty()) {
            return false;
        }
        Invitation invitation = found.get();
        invitation.setTokenId(UUID.randomUUID());
        invitation.setExpiresAt(Instant.now().plus(Duration.ofDays(validDays)));
        sendEmail(invitation);
        auditService.record("INVITATION_RESENT", invitation.getShip(),
                invitation.getEmail() + " as " + invitation.getRole());
        return true;
    }

    /** Cancels a PENDING invitation, so its link stops working. Returns false if there is none with this id. */
    @Transactional
    public boolean cancel(Long id) {
        Optional<Invitation> found = findPending(id);
        if (found.isEmpty()) {
            return false;
        }
        Invitation invitation = found.get();
        invitation.setStatus(Invitation.Status.CANCELLED);
        auditService.record("INVITATION_CANCELLED", invitation.getShip(),
                invitation.getEmail() + " as " + invitation.getRole());
        return true;
    }

    /** PENDING office invitations, expired or not, by name. Listed on the Office users page. */
    @Transactional(readOnly = true)
    public List<Invitation> findPendingOfficeInvitations() {
        return invitationRepository.findAllByStatusAndRoleOrderByFullNameAsc(Invitation.Status.PENDING,
                Role.OFFICE_USER);
    }

    /** The PENDING invitation with this id (expired or not), if there is one. */
    @Transactional(readOnly = true)
    public Optional<Invitation> findPending(Long id) {
        return invitationRepository.findById(id)
                .filter(invitation -> invitation.getStatus() == Invitation.Status.PENDING);
    }

    private void sendEmail(Invitation invitation) {
        String url = baseUrl + "/register?token=" + tokenService.create(invitation);
        // The invitee's language is unknown and crew screens are English only, so the email is English.
        String subject = messageSource.getMessage("invitation.email.subject", null, Locale.ENGLISH);
        String text = messageSource.getMessage("invitation.email.text",
                new Object[] {invitation.getFullName(), url, validDays}, Locale.ENGLISH);
        mailService.send(invitation.getEmail(), subject, text);
    }

    // Inactive ships cannot get new crew, so they are not found here.
    @Transactional(readOnly = true)
    public Optional<Ship> findActiveShip(Long id) {
        return shipRepository.findById(id).filter(Ship::isActive);
    }
}
