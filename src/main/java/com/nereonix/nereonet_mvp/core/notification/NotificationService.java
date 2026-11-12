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

    /**
     * Saves an unread notification (P23). subject: the ship or person it concerns, shown above the text ("MV Demo",
     * "Jane Doe · MV Demo"). link: an app path such as /ship/library/3, or null; opening the notification follows it.
     */
    @Transactional
    public Notification notify(User user, NotificationType type, String subject, String text, String link) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(type);
        notification.setSubject(cut(subject, Notification.SUBJECT_MAX_LENGTH));
        notification.setText(cut(text, Notification.TEXT_MAX_LENGTH));
        notification.setLink(link);
        notification.setCreatedAt(Instant.now());
        return notificationRepository.save(notification);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
