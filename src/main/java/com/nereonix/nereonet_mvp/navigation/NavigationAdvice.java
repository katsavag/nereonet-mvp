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
 *   navTab      the crew's active phone tab (NavSection.tab(), P31: More for My profile and My crew); null the same way.
 *   navCounts   the office counters (NavCounts); null for crew and when signed out.
 * The one place that decides both, so no controller sets them.
 */
@ControllerAdvice
public class NavigationAdvice {

    static final String SECTION = "navSection";
    static final String COUNTS = "navCounts";
    static final String TAB = "navTab";

    private final NavigationService navigationService;

    public NavigationAdvice(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    @ModelAttribute(SECTION)
    public String navSection(HttpServletRequest request) {
        NavSection section = section(request);
        return section == null ? null : section.key();
    }

    @ModelAttribute(TAB)
    public String navTab(HttpServletRequest request) {
        NavSection section = section(request);
        return section == null ? null : section.tab();
    }

    private static NavSection section(HttpServletRequest request) {
        // On an error page (404 under /office/crew/...) the URL that failed keeps its section.
        Object errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String uri = errorUri != null ? errorUri.toString() : request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return NavSection.of(uri);
    }

    @ModelAttribute(COUNTS)
    public NavCounts navCounts(@AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null || currentUser.getRole() != Role.OFFICE_USER) {
            return null;
        }
        return navigationService.officeCounts();
    }
}
