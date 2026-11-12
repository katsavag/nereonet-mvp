package com.nereonix.nereonet_mvp.notification;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;

/**
 * The logged-in user's notifications. Not named NotificationService: that is the core service that creates them.
 * A notification stays unread until the user opens it or marks it as read (P23); showing the list changes nothing.
 * Reading a notification is the user's own bookkeeping, like a read receipt, and is not recorded in the audit log.
 */
@Service
public class NotificationInboxService {

    private final NotificationRepository notificationRepository;
    private final ListQueries listQueries;

    public NotificationInboxService(NotificationRepository notificationRepository, ListQueries listQueries) {
        this.notificationRepository = notificationRepository;
        this.listQueries = listQueries;
    }

    @Transactional(readOnly = true)
    public long countUnread(String email) {
        return notificationRepository.countByUserEmailAndReadAtIsNull(email);
    }

    /** One page of the user's notifications, newest first. Marks nothing as read. */
    @Transactional(readOnly = true)
    public Page<Notification> page(String email, ListQuery list) {
        return listQueries.page(Notification.class, list,
                (root, cb) -> List.of(cb.equal(root.get("user").get("email"), email)),
                (root, cb) -> List.of(root.get("createdAt")),
                null);
    }

    /**
     * Opening a notification: marks it as read and returns the app path it opens, or null when it has none.
     * A link that is not a path within the app (it never is) gives null, so the redirect never leaves the app.
     */
    @Transactional
    public String open(String email, Long id) {
        Notification notification = markRead(email, id);
        String link = notification.getLink();
        return link != null && link.startsWith("/") && !link.startsWith("//") && !link.contains("\\") ? link : null;
    }

    /** Marks one of the user's notifications as read (again: nothing changes); someone else's gives 404. */
    @Transactional
    public Notification markRead(String email, Long id) {
        Notification notification = notificationRepository.findByIdAndUserEmail(id, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
        }
        return notification;
    }

    /** Marks every unread notification of the user as read; returns how many. */
    @Transactional
    public int markAllRead(String email) {
        return notificationRepository.markAllRead(email, Instant.now());
    }
}
