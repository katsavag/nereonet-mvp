package com.nereonix.nereonet_mvp.core.audit;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// Writes audit entries. Called from every service method that changes data,
// inside that method's transaction. Never pass passwords, tokens or invitation links as details.
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;
    private final UserRepository userRepository;

    public AuditService(AuditRepository auditRepository, UserRepository userRepository) {
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
    }

    /** Records an action by the logged-in user. ship and details may be null. */
    @Transactional
    public void record(String action, Ship ship, String details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("No logged-in user; pass the actor explicitly");
        }
        User actor = userRepository.findByEmail(auth.getName()).orElseThrow();
        record(action, ship, details, actor);
    }

    /** For cases with no logged-in user. actor is null for system jobs. */
    @Transactional
    public void record(String action, Ship ship, String details, User actor) {
        if (details != null && details.length() > AuditEntry.DETAILS_MAX_LENGTH) {
            details = details.substring(0, AuditEntry.DETAILS_MAX_LENGTH);
        }
        auditRepository.save(new AuditEntry(Instant.now(), actor, action, ship, details));
        // One log line per data change; details never hold secrets (see above).
        log.info("{} by {}, ship: {}, details: {}", action, actor == null ? "system" : actor.getEmail(),
                ship == null ? "-" : ship.getName(), details == null ? "-" : details);
    }
}
