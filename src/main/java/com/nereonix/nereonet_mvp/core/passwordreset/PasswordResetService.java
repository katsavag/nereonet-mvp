package com.nereonix.nereonet_mvp.core.passwordreset;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.mail.ActionEmail;
import com.nereonix.nereonet_mvp.core.mail.HtmlEmails;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

import io.jsonwebtoken.JwtException;

/**
 * Forgot password (P14). A user asks for a link on /password/forgot; the email holds a link to /password/reset that
 * works once and for 60 minutes; the new password is set there.
 * The request never says whether an address has an account: the page is the same, and the email is sent on another
 * thread, so the answer takes as long for a known address as for an unknown one. No email goes to an unknown or
 * deactivated address, nor after 5 links to the same address within an hour.
 */
@Service
public class PasswordResetService {

    public static final Duration VALID_FOR = Duration.ofMinutes(60);
    public static final int MAX_PER_HOUR = 5;

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final PasswordResetRepository resetRepository;
    private final PasswordResetTokenService tokenService;
    private final UserRepository userRepository;
    private final UserService userService;
    private final AuditService auditService;
    private final MailService mailService;
    private final PasswordResetEmails resetEmails;
    private final HtmlEmails htmlEmails;
    private final TaskExecutor mailExecutor;
    private final String baseUrl;

    public PasswordResetService(PasswordResetRepository resetRepository, PasswordResetTokenService tokenService,
                                UserRepository userRepository, UserService userService, AuditService auditService,
                                MailService mailService, PasswordResetEmails resetEmails, HtmlEmails htmlEmails,
                                @Qualifier("applicationTaskExecutor") TaskExecutor mailExecutor,
                                @Value("${app.base-url}") String baseUrl) {
        this.resetRepository = resetRepository;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.userService = userService;
        this.auditService = auditService;
        this.mailService = mailService;
        this.resetEmails = resetEmails;
        this.htmlEmails = htmlEmails;
        this.mailExecutor = mailExecutor;
        this.baseUrl = baseUrl;
    }

    /**
     * Emails a reset link to the enabled user with this address, in the language of the page (locale); for any other
     * address nothing happens. Returns nothing on purpose: the caller must answer the same either way.
     */
    @Transactional
    public void request(String email, Locale locale) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Optional<User> found = userRepository.findByEmail(normalized).filter(User::isEnabled);
        if (found.isEmpty()) {
            // Unknown or deactivated: no email. Logged without saying which, as the page does.
            log.info("Password reset requested for {}: no email (no active account)", normalized);
            return;
        }
        Instant now = Instant.now();
        if (resetRepository.countByEmailAndCreatedAtAfter(normalized, now.minus(Duration.ofHours(1))) >= MAX_PER_HOUR) {
            log.info("Password reset requested for {}: no email ({} links within the hour)", normalized, MAX_PER_HOUR);
            return;
        }
        User user = found.get();
        PasswordReset reset = new PasswordReset();
        reset.setTokenId(UUID.randomUUID());
        reset.setUserId(user.getId());
        reset.setEmail(normalized);
        reset.setCreatedAt(now);
        reset.setExpiresAt(now.plus(VALID_FOR));
        resetRepository.save(reset);

        String url = baseUrl + "/password/reset?token=" + tokenService.create(reset);
        String fullName = user.getFullName();
        Instant expiresAt = reset.getExpiresAt();
        // On another thread: sending takes seconds over SMTP, which would tell a known address from an unknown one. The
        // HTML version (P29, the invitation's template) is rendered there too, in the language of the page.
        mailExecutor.execute(() -> {
            try {
                ActionEmail message = resetEmails.email(fullName, normalized, url, expiresAt, locale);
                mailService.send(normalized, message.subject(), resetEmails.text(fullName, url, locale),
                        htmlEmails.render(message));
            } catch (RuntimeException e) {
                log.warn("Password reset email to {} failed: {}", normalized, e.getMessage());
            }
        });
    }

    /**
     * The reset for this token while it still works: signature and audience right, not expired, not used, and its
     * user still exists, is the same user and is enabled.
     */
    @Transactional(readOnly = true)
    public Optional<PasswordReset> findValid(String token) {
        UUID tokenId;
        try {
            tokenId = tokenService.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
        return resetRepository.findByTokenId(tokenId)
                .filter(reset -> reset.getUsedAt() == null && reset.getExpiresAt().isAfter(Instant.now()))
                .filter(reset -> user(reset).isPresent());
    }

    /**
     * Sets the new password and stops this link and every other open link of the user. Records PASSWORD_RESET
     * (the email only, never the password or the link). Returns the user, or empty if the link does not work.
     */
    @Transactional
    public Optional<User> reset(String token, String rawPassword) {
        Optional<PasswordReset> found = findValid(token);
        if (found.isEmpty()) {
            // Never the token.
            log.info("Password reset with an invalid, expired or used link");
            return Optional.empty();
        }
        User user = user(found.get()).orElseThrow();
        userService.changePassword(user, rawPassword);
        Instant now = Instant.now();
        resetRepository.findAllByUserIdAndUsedAtIsNull(user.getId()).forEach(reset -> reset.setUsedAt(now));
        auditService.record("PASSWORD_RESET", user.getShip(), user.getEmail(), user);
        return Optional.of(user);
    }

    private Optional<User> user(PasswordReset reset) {
        return userRepository.findById(reset.getUserId())
                .filter(user -> user.getEmail().equals(reset.getEmail()))
                .filter(User::isEnabled);
    }
}
