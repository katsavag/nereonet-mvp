package com.nereonix.nereonet_mvp.core.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import com.nereonix.nereonet_mvp.core.user.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Where a user goes after signing in (P12): back to the page they were on, else their home page.
 *   - a form sent after the session expired (SignedOutHandler): the page the form was on, from the Referer header,
 *     kept in the new session under ATTRIBUTE;
 *   - a page opened while signed out: the request Spring Security saved before sending the user to /login.
 * Only paths of this app, never /login, /logout, /register or /error, and never a page of the other side
 * (an /office page for crew, a /ship page for the office): on a shared PC the next person may be someone else.
 */
public final class ReturnTo {

    static final String ATTRIBUTE = ReturnTo.class.getName();

    private static final List<String> NEVER = List.of("/login", "/logout", "/register", "/error");

    private static final RequestCache REQUEST_CACHE = new HttpSessionRequestCache();

    private ReturnTo() {
    }

    /** Remembers the page a failed form was on, in the current (new) session; nothing without a usable Referer. */
    static void rememberReferer(HttpServletRequest request) {
        String path = refererPath(request);
        if (path != null) {
            request.getSession(true).setAttribute(ATTRIBUTE, path);
        }
    }

    /** The app path (without context path) to send the user to right after signing in, and forgets it. */
    static String afterSignIn(HttpServletRequest request, HttpServletResponse response, CurrentUser user) {
        String path = null;
        HttpSession session = request.getSession(false);
        if (session != null) {
            path = (String) session.getAttribute(ATTRIBUTE);
            session.removeAttribute(ATTRIBUTE);
        }
        SavedRequest saved = REQUEST_CACHE.getRequest(request, response);
        if (saved != null) {
            REQUEST_CACHE.removeRequest(request, response);
            if (path == null && saved instanceof DefaultSavedRequest page && "GET".equals(page.getMethod())) {
                path = appPath(page.getRequestURI(), page.getQueryString(), request.getContextPath());
            }
        }
        return path != null && allowedFor(path, user.getRole()) ? path : user.getHomeUrl();
    }

    /** "/office/crew?ship=3" from a Referer of this same app, else null. */
    static String refererPath(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(referer);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!request.getScheme().equalsIgnoreCase(uri.getScheme())
                || !request.getServerName().equalsIgnoreCase(uri.getHost())
                || request.getServerPort() != port(uri)) {
            return null;
        }
        return appPath(uri.getRawPath(), uri.getRawQuery(), request.getContextPath());
    }

    /** The path inside the app, with its query, or null when it is not one we return to. */
    private static String appPath(String requestUri, String query, String contextPath) {
        if (requestUri == null || !requestUri.startsWith(contextPath + "/")) {
            return null;
        }
        String path = requestUri.substring(contextPath.length());
        // "//host" would leave the app; a backslash is read as a slash by some browsers.
        if (path.startsWith("//") || path.contains("\\")) {
            return null;
        }
        for (String never : NEVER) {
            if (path.equals(never) || path.startsWith(never + "/")) {
                return null;
            }
        }
        return query == null || query.isEmpty() ? path : path + "?" + query;
    }

    private static boolean allowedFor(String path, Role role) {
        boolean office = role == Role.OFFICE_USER;
        if (isUnder(path, "/office")) {
            return office;
        }
        if (isUnder(path, "/ship")) {
            return !office;
        }
        return true;
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + "?");
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
