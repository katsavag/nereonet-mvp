package com.nereonix.nereonet_mvp.notification;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;

// Adds the unread count for the bell in the shared layout to every page of a logged-in user.
@ControllerAdvice
public class NotificationBellAdvice {

    static final String UNREAD = "unreadNotifications";

    private final NotificationInboxService inboxService;

    public NotificationBellAdvice(NotificationInboxService inboxService) {
        this.inboxService = inboxService;
    }

    // Null when signed out.
    @ModelAttribute(UNREAD)
    public Long unreadNotifications(@AuthenticationPrincipal CurrentUser currentUser) {
        return currentUser == null ? null : inboxService.countUnread(currentUser.getUsername());
    }
}
