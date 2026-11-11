package com.nereonix.nereonet_mvp.registration;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.invitation.InvitationTokenService;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

import io.jsonwebtoken.JwtException;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final InvitationRepository invitationRepository;
    private final InvitationTokenService tokenService;
    private final UserService userService;
    private final AuditService auditService;

    public RegistrationService(InvitationRepository invitationRepository, InvitationTokenService tokenService,
                               UserService userService, AuditService auditService) {
        this.invitationRepository = invitationRepository;
        this.tokenService = tokenService;
        this.userService = userService;
        this.auditService = auditService;
    }

    /**
     * The PENDING invitation for this token, or empty if the signature is wrong, the token expired,
     * or the invitation was deleted or already accepted.
     */
    @Transactional(readOnly = true)
    public Optional<Invitation> findValidInvitation(String token) {
        UUID tokenId;
        try {
            tokenId = tokenService.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
        return invitationRepository.findByTokenId(tokenId)
                .filter(invitation -> invitation.getStatus() == Invitation.Status.PENDING);
    }

    /** Creates the user and accepts the invitation. Returns the new user, or empty if the token is not valid. */
    @Transactional
    public Optional<User> register(String token, String rawPassword) {
        Optional<Invitation> found = findValidInvitation(token);
        if (found.isEmpty()) {
            // Never the token.
            log.info("Registration with an invalid, expired or used invitation link");
            return Optional.empty();
        }
        Invitation invitation = found.get();
        User user = userService.createUser(invitation.getEmail(), invitation.getFullName(), rawPassword,
                invitation.getRole(), invitation.getShip(), invitation.getRank());
        invitation.setStatus(Invitation.Status.ACCEPTED);
        invitation.setAcceptedAt(Instant.now());
        // Nobody is logged in during registration: the new user is the actor.
        auditService.record("USER_REGISTERED", user.getShip(), user.getEmail() + " as " + user.getRole(), user);
        return Optional.of(user);
    }
}
