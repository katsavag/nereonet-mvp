package com.nereonix.nereonet_mvp.core.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * How the app shows dates and times (P11), used as {@code @dates} in Thymeleaf. Office and ship read the same time
 * for the same event, so every time is shown in UTC and says so: "21 Oct 2026 · 12:40 UTC". A date without a time
 * (a certificate's expiry) has no zone: "21 Oct 2026". One format in both languages; month names follow the page
 * language. Relative hints ("in 23 days", "expired 5 days ago") count whole UTC days from today (UTC).
 */
@Component("dates")
public class Dates {

    /** The one date format of the app, also in emails and notifications. */
    public static final String DATE_PATTERN = "d MMM yyyy";
    private static final String DATE_TIME_PATTERN = DATE_PATTERN + " · HH:mm 'UTC'";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final MessageSource messageSource;
    private final Clock clock;

    @Autowired
    public Dates(MessageSource messageSource) {
        this(messageSource, Clock.systemUTC());
    }

    Dates(MessageSource messageSource, Clock clock) {
        this.messageSource = messageSource;
        this.clock = clock;
    }

    /** "21 Oct 2026" in the page language; empty for null. */
    public String date(LocalDate date) {
        return date == null ? "" : DateTimeFormatter.ofPattern(DATE_PATTERN, locale()).format(date);
    }

    /** The UTC date of an instant, "21 Oct 2026", where the time would be noise (a library list row, P22); empty for null. */
    public String utcDate(Instant instant) {
        return instant == null ? "" : date(LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    /** The UTC date of an instant without the year when it is this year (UTC), "13 Nov", else "13 Nov 2025": a news
     *  item's meta line on the crew's News (P47); empty for null. */
    public String shortDate(Instant instant) {
        if (instant == null) {
            return "";
        }
        LocalDate date = LocalDate.ofInstant(instant, ZoneOffset.UTC);
        return date.getYear() == today().getYear()
                ? DateTimeFormatter.ofPattern("d MMM", locale()).format(date) : date(date);
    }

    /** "21 Oct 2026 · 12:40 UTC" in the page language; empty for null. */
    public String dateTime(Instant instant) {
        return instant == null ? ""
                : DateTimeFormatter.ofPattern(DATE_TIME_PATTERN, locale()).withZone(ZoneOffset.UTC).format(instant);
    }

    /** "21 Oct 2026 · 12:40 UTC" in the given language, outside a request (an email, P29); empty for null. */
    public static String dateTime(Instant instant, Locale locale) {
        return instant == null ? ""
                : DateTimeFormatter.ofPattern(DATE_TIME_PATTERN, locale).withZone(ZoneOffset.UTC).format(instant);
    }

    /** "12:40 UTC", under a heading that gives the day (a notification, P23); empty for null. */
    public String utcTime(Instant instant) {
        return instant == null ? "" : TIME.format(instant);
    }

    /** "2026-10-21T12:40:00Z", for the datetime attribute of a time element; empty for null. */
    public String iso(Instant instant) {
        return instant == null ? "" : ISO_INSTANT.format(instant);
    }

    /** Today's date in UTC. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Whole days from today (UTC) to date: 0 today, negative once it has passed. */
    public long daysUntil(LocalDate date) {
        return ChronoUnit.DAYS.between(today(), date);
    }

    /** After a certificate's expiry date: "in 23 days", "expires today", "expired 5 days ago". */
    public String certificateHint(LocalDate expiresOn) {
        long days = daysUntil(expiresOn);
        if (days > 0) {
            return message("time.hint.inDays", days);
        }
        return days == 0 ? message("time.hint.expiresToday") : message("time.hint.expiredDaysAgo", -days);
    }

    /** After an invitation: "expires in 2 days", "expires today", "expired today", "expired 5 days ago". */
    public String invitationHint(Instant expiresAt) {
        long days = daysUntil(LocalDate.ofInstant(expiresAt, ZoneOffset.UTC));
        if (days > 0) {
            return message("time.hint.expiresInDays", days);
        }
        if (days == 0) {
            return message(expiresAt.isAfter(clock.instant()) ? "time.hint.expiresToday" : "time.hint.expiredToday");
        }
        return message("time.hint.expiredDaysAgo", -days);
    }

    /** After "Invitation expired" (P17), how long ago it expired: "today", "1 day ago", "5 days ago". */
    public String invitationExpiredHint(Instant expiresAt) {
        long days = -daysUntil(LocalDate.ofInstant(expiresAt, ZoneOffset.UTC));
        return days <= 0 ? message("time.hint.today") : message("time.hint.daysAgo", days);
    }

    /**
     * How old a report is (P45, a vessel's last AIS position): "just now" (under a minute, or a time in the future),
     * "35 minutes ago", "2 hours ago", "3 days ago"; empty for null.
     */
    public String age(Instant instant) {
        if (instant == null) {
            return "";
        }
        long minutes = java.time.Duration.between(instant, clock.instant()).toMinutes();
        if (minutes < 1) {
            return message("time.age.justNow");
        }
        if (minutes < 60) {
            return message("time.age.minutes", minutes);
        }
        if (minutes < 48 * 60) {
            return message("time.age.hours", minutes / 60);
        }
        return message("time.age.days", minutes / (24 * 60));
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, locale());
    }

    private static Locale locale() {
        return LocaleContextHolder.getLocale();
    }
}
