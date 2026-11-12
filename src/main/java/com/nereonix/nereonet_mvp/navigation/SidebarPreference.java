package com.nereonix.nereonet_mvp.navigation;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The sidebar's state from 768px (P29), used as {@code @sidebarPreference} in layout/layout.html: a 64px icon rail
 * (collapsed, the default on a first visit) or the full sidebar with labels (expanded). The choice is remembered per
 * browser in the cookie {@code sidebar=expanded|collapsed}: js/sidebar.js writes it when the toggle is pressed, and
 * SidebarPreferenceController when the toggle is sent as a form (no JavaScript). The server reads it here, so the
 * page is rendered in the right state and never flashes the other one first.
 */
@Component("sidebarPreference")
public class SidebarPreference {

    public static final String COOKIE = "sidebar";
    public static final String EXPANDED = "expanded";
    public static final String COLLAPSED = "collapsed";
    static final Duration MAX_AGE = Duration.ofDays(365);

    /** True when this browser chose the expanded sidebar; false on a first visit and for any other value. */
    public boolean expanded() {
        HttpServletRequest request = currentRequest();
        if (request == null || request.getCookies() == null) {
            return false;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName())) {
                return EXPANDED.equals(cookie.getValue());
            }
        }
        return false;
    }

    /**
     * The current page as an app path with its query string ("/office/crew?shipId=3"), for the toggle's form to
     * return to. On an error page, the URL that failed.
     */
    public String returnTo() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "/";
        }
        UrlPathHelper paths = UrlPathHelper.defaultInstance;
        String uri = paths.getOriginatingRequestUri(request);
        String contextPath = paths.getOriginatingContextPath(request);
        if (!contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        String query = paths.getOriginatingQueryString(request);
        return query == null || query.isEmpty() ? uri : uri + "?" + query;
    }

    /** returnTo when it is a path of this app; "/" for anything else (another host, a scheme, a line break). */
    static String safeReturnTo(String returnTo) {
        if (returnTo == null || !returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.startsWith("/\\")
                || returnTo.chars().anyMatch(c -> c < 0x20 || c == '\\')) {
            return "/";
        }
        return returnTo;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
