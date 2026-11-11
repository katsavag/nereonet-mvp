package com.nereonix.nereonet_mvp.core.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Access denied (P12). A form whose CSRF token does not match was sent after the session expired (the token lived in
 * it) or from a page of an older session: the user is signed out, if still signed in, and sent to /login?expired,
 * which says "You were signed out. Sign in to continue."; after signing in they are back on the page the form was on
 * (ReturnTo). Everything else is a 403, shown by templates/error/403.html.
 */
public class SignedOutHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SignedOutHandler.class);

    private final AccessDeniedHandler forbidden = new AccessDeniedHandlerImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException, ServletException {
        if (!(exception instanceof CsrfException) || response.isCommitted()) {
            forbidden.handle(request, response, exception);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = authentication != null && !(authentication instanceof AnonymousAuthenticationToken);
        log.info("Form refused ({}) on {} {}, user {}: sent to sign in", exception.getClass().getSimpleName(),
                request.getMethod(), request.getServletPath(), signedIn ? authentication.getName() : "-");
        if (signedIn) {
            // Also ends the session, so "You were signed out" is true.
            new SecurityContextLogoutHandler().logout(request, response, authentication);
        }
        ReturnTo.rememberReferer(request);
        response.sendRedirect(request.getContextPath() + "/login?expired");
    }
}
