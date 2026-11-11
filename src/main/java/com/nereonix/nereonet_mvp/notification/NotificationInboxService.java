package com.nereonix.nereonet_mvp.notification;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;

// The logged-in user's notifications. Not named NotificationService: that is the core service that creates them.
@Service
public class NotificationInboxService {

    private final NotificationRepository notificationRepository;

    public NotificationInboxService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional(readOnly = true)
    public long countUnread(String email) {
        return notificationRepository.countByUserEmailAndReadAtIsNull(email);
    }

    /**
     * The user's notifications, newest first, and marks them all as read.
     * The returned ones still have readAt null if they were unread, so the page can show them as new.
     * Reading a notification is not recorded in the audit log.
     */
    @Transactional
    public List<Notification> open(String email) {
        List<Notification> notifications = notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(email);
        notificationRepository.markAllRead(email, Instant.now());
        return notifications;
    }
}
