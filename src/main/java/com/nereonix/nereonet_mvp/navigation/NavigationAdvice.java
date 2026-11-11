package com.nereonix.nereonet_mvp.navigation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Role;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The sidebar of the shared layout (layout/layout.html), on every page of a signed-in user:
 *   navSection  the key of the active entry (NavSection.key()), from the URL; null when no entry matches.
 *   navCounts   the office counters (NavCounts); null for crew and when signed out.
 * The one place that decides both, so no controller sets them.
 */
@ControllerAdvice
public class NavigationAdvice {

    static final String SECTION = "navSection";
    static final String COUNTS = "navCounts";

    private final NavigationService navigationService;

    public NavigationAdvice(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    @ModelAttribute(SECTION)
    public String navSection(HttpServletRequest request) {
        // On an error page (404 under /office/crew/...) the URL that failed keeps its section.
        Object errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String uri = errorUri != null ? errorUri.toString() : request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        NavSection section = NavSection.of(uri);
        return section == null ? null : section.key();
    }

    @ModelAttribute(COUNTS)
    public NavCounts navCounts(@AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null || currentUser.getRole() != Role.OFFICE_USER) {
            return null;
        }
        return navigationService.officeCounts();
    }
}
