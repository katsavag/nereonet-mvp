package com.nereonix.nereonet_mvp.core.notification;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.user.User;

// In-app notifications, for any feature. The caller records the change that caused it in the audit log.
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /** Saves an unread notification. link is an app path such as /ship, or null. */
    @Transactional
    public Notification notify(User user, String text, String link) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setText(text.length() > Notification.TEXT_MAX_LENGTH
                ? text.substring(0, Notification.TEXT_MAX_LENGTH) : text);
        notification.setLink(link);
        notification.setCreatedAt(Instant.now());
        return notificationRepository.save(notification);
    }
}
