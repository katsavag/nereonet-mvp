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
 *   approvalsWaiting  the Master's counter of Approvals (P33): requests of their ship waiting for them; null for
 *               everyone else.
 *   orderLines  the Master's counter of Ship order (P35): lines in their ship's OPEN order; null for everyone else.
 *   announcementsToAcknowledge  the crew's counter of Announcements (P37, every ship role): the ones they have not
 *               acknowledged yet; null for office users and when signed out.
 *   currentPhoto  the signed-in ship user's profile photo URL (96px, CrewProfile.photoUrl, P43) for the user block's
 *               picture; null without a photo, for office users and when signed out.
 * The one place that decides both, so no controller sets them.
 */
@ControllerAdvice
public class NavigationAdvice {

    static final String SECTION = "navSection";
    static final String COUNTS = "navCounts";
    static final String TAB = "navTab";
    static final String APPROVALS_WAITING = "approvalsWaiting";
    static final String ORDER_LINES = "orderLines";
    static final String ANNOUNCEMENTS_TO_ACKNOWLEDGE = "announcementsToAcknowledge";
    static final String CURRENT_PHOTO = "currentPhoto";

    private final NavigationService navigationService;

    public NavigationAdvice(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    @ModelAttribute(CURRENT_PHOTO)
    public String currentPhoto(@AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null || currentUser.getRole() == Role.OFFICE_USER) {
            return null;
        }
        return navigationService.photoUrl(currentUser.getId());
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

    @ModelAttribute(APPROVALS_WAITING)
    public Long approvalsWaiting(@AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null || currentUser.getRole() != Role.MASTER) {
            return null;
        }
        return navigationService.approvalsWaiting(currentUser.getUsername());
    }

    @ModelAttribute(ORDER_LINES)
    public Long orderLines(@AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null || currentUser.getRole() != Role.MASTER) {
            return null;
        }
        return navigationService.orderLines(currentUser.getUsername());
    }

    @ModelAttribute(ANNOUNCEMENTS_TO_ACKNOWLEDGE)
    public Long announcementsToAcknowledge(@AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null || currentUser.getRole() == Role.OFFICE_USER) {
            return null;
        }
        return navigationService.announcementsToAcknowledge(currentUser.getUsername());
    }
}
