package com.nereonix.nereonet_mvp.notification;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.nereonix.nereonet_mvp.core.notification.Notification;

/**
 * The notifications of one UTC day on a page of /notifications (P23), under the heading Today, Yesterday or the date.
 * A day split over two pages starts the next page with its heading again.
 */
public record NotificationDay(LocalDate date, List<Notification> notifications) {

    /** notifications: newest first, as listed. Returns the days in the same order. */
    public static List<NotificationDay> of(List<Notification> notifications) {
        List<NotificationDay> days = new ArrayList<>();
        for (Notification n : notifications) {
            LocalDate date = LocalDate.ofInstant(n.getCreatedAt(), ZoneOffset.UTC);
            if (days.isEmpty() || !days.getLast().date().equals(date)) {
                days.add(new NotificationDay(date, new ArrayList<>()));
            }
            days.getLast().notifications().add(n);
        }
        return days;
    }
}
