package com.nereonix.nereonet_mvp.navigation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.Role;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The sidebar of the shared layout (layout/layout.html), on every page of a signed-in user:
 *   navSection  the key of the active entry (NavSection.key()), from the URL; null when no entry matches.
 *   navTab      the ship user's active phone tab (ShipNav.tabOf, P65: the section's own tab, else More); null for
 *               office users, signed out and on a page with no section.
 *   shipNav     the ship user's navigation per role (ShipNav, P65): the sidebar's On board and Supplies groups, the
 *               phone tab bar and the More page; null for office users and when signed out.
 *   navCounts   the office counters (NavCounts); null for crew and when signed out.
 *   approvalsWaiting  the Master's counter of Approvals (P33): requests of their ship waiting for them; null for
 *               everyone else.
 *   ordersToAct  the Master's counter of Vessel order (P35; orders, not lines, since P59): their ship's OPEN order
 *               when it has a line (to send) plus its CONFIRMED orders (to receive); null for everyone else (the Chief
 *               cook reads Vessel order without it, P55).
 *   storesBelowThreshold  the Chief cook's counter of Stores (P52): provisions of their ship that are Low or Out of
 *               stock; null for everyone else.
 *   requestsWaiting  the Chief cook's and crew members' counter of Requests (P54): their own requests still waiting
 *               for the Master (also on the cook's Stores summary and the crew home's Requests card); null for the
 *               Master, office users and when signed out.
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
    static final String ORDERS_TO_ACT = "ordersToAct";
    static final String STORES_BELOW_THRESHOLD = "storesBelowThreshold";
    static final String REQUESTS_WAITING = "requestsWaiting";
    static final String ANNOUNCEMENTS_TO_ACKNOWLEDGE = "announcementsToAcknowledge";
    static final String CURRENT_PHOTO = "currentPhoto";
    static final String SHIP_NAV = "shipNav";

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

    /**
     * The ship user's counters, their navigation (shipNav, ShipNav: sidebar groups, phone tabs, More page) and the
     * active tab (navTab), in one place so each counter is one query (P65). Office users and signed-out pages get
     * nulls.
     */
    @ModelAttribute
    public void shipNavigation(@AuthenticationPrincipal CurrentUser currentUser, HttpServletRequest request,
                               Model model) {
        Role role = currentUser == null ? null : currentUser.getRole();
        String email = currentUser == null ? null : currentUser.getUsername();
        Long approvals = role == Role.MASTER ? navigationService.approvalsWaiting(email) : null;
        Long orders = role == Role.MASTER ? navigationService.ordersToAct(email) : null;
        Long stores = role == Role.CHIEF_COOK ? navigationService.storesBelowThreshold(email) : null;
        Long requests = role == Role.CHIEF_COOK || role == Role.CREW_MEMBER
                ? navigationService.requestsWaiting(email) : null;
        Long announcements = role != null && role != Role.OFFICE_USER
                ? navigationService.announcementsToAcknowledge(email) : null;
        ShipNav shipNav = role != null && role != Role.OFFICE_USER
                ? ShipNav.of(role, new ShipNav.Counts(announcements, requests, approvals, orders, stores)) : null;
        model.addAttribute(APPROVALS_WAITING, approvals);
        model.addAttribute(ORDERS_TO_ACT, orders);
        model.addAttribute(STORES_BELOW_THRESHOLD, stores);
        model.addAttribute(REQUESTS_WAITING, requests);
        model.addAttribute(ANNOUNCEMENTS_TO_ACKNOWLEDGE, announcements);
        model.addAttribute(SHIP_NAV, shipNav);
        model.addAttribute(TAB, shipNav == null ? null : shipNav.tabOf(section(request)));
    }
}
