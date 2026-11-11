package com.nereonix.nereonet_mvp.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Signs a user in without the sign-in form (P13: right after registration; P14: right after a password reset; the
 * user then goes to their home page). Does what a successful form login does: a new session id (no session
 * fixation), a new CSRF token, and the security context stored in the session. The caller has just checked who the
 * user is.
 */
@Component
public class SignIn {

    private static final Logger log = LoggerFactory.getLogger(SignIn.class);

    private final AppUserDetailsService users;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    // The repository Spring Security uses by default (SecurityConfig does not change it).
    private final CsrfTokenRepository csrfTokens = new HttpSessionCsrfTokenRepository();

    public SignIn(AppUserDetailsService users) {
        this.users = users;
    }

    /** Signs in the user with this email on the current session. Returns their home page, /office or /ship. */
    public String signIn(String email, HttpServletRequest request, HttpServletResponse response) {
        CurrentUser user = (CurrentUser) users.loadUserByUsername(email);
        user.eraseCredentials();
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        csrfTokens.saveToken(null, request, response);

        SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        holder.setContext(context);
        contextRepository.saveContext(context, request, response);
        log.info("Login: {} (without the sign-in form)", user.getUsername());
        return user.getHomeUrl();
    }
}
