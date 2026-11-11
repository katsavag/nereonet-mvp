package com.nereonix.nereonet_mvp.notification;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;

// For every logged-in user (SecurityConfig: any other URL requires login).
@Controller
public class NotificationController {

    private final NotificationInboxService inboxService;

    public NotificationController(NotificationInboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping("/notifications")
    public String list(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        model.addAttribute("notifications", inboxService.open(currentUser.getUsername()));
        // NotificationBellAdvice counted before they were marked as read.
        model.addAttribute(NotificationBellAdvice.UNREAD, 0L);
        return "notification/list";
    }
}
