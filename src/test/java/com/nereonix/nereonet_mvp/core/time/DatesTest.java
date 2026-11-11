package com.nereonix.nereonet_mvp.core.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/** P11: one date format in both languages, times in UTC with the zone, relative hints in whole UTC days. */
class DatesTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:40:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    private final Dates dates = new Dates(messages(), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void oneFormatWithMonthNamesInThePageLanguage() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(dates.date(TODAY)).isEqualTo("21 Oct 2026");
        assertThat(dates.dateTime(NOW)).isEqualTo("21 Oct 2026 · 12:40 UTC");
        assertThat(dates.iso(NOW)).isEqualTo("2026-10-21T12:40:00Z");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("el"));
        assertThat(dates.date(TODAY)).isEqualTo("21 Οκτ 2026");
        assertThat(dates.dateTime(NOW)).isEqualTo("21 Οκτ 2026 · 12:40 UTC");

        assertThat(dates.date(null)).isEmpty();
        assertThat(dates.dateTime(null)).isEmpty();
    }

    @Test
    void timesAreInUtcWhateverTheServerZone() {
        // 23:30 UTC is already the next day in Athens and Singapore; the text stays the UTC day and time.
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(dates.dateTime(Instant.parse("2026-10-21T23:30:00Z"))).isEqualTo("21 Oct 2026 · 23:30 UTC");
    }

    @Test
    void certificateHintCountsUtcDays() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(dates.certificateHint(TODAY.plusDays(23))).isEqualTo("in 23 days");
        assertThat(dates.certificateHint(TODAY.plusDays(1))).isEqualTo("in 1 day");
        assertThat(dates.certificateHint(TODAY)).isEqualTo("expires today");
        assertThat(dates.certificateHint(TODAY.minusDays(1))).isEqualTo("expired 1 day ago");
        assertThat(dates.certificateHint(TODAY.minusDays(5))).isEqualTo("expired 5 days ago");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("el"));
        assertThat(dates.certificateHint(TODAY.plusDays(23))).isEqualTo("σε 23 ημέρες");
        assertThat(dates.certificateHint(TODAY.minusDays(5))).isEqualTo("έληξε πριν από 5 ημέρες");
    }

    @Test
    void invitationHintSaysExpiresOrExpired() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(dates.invitationHint(NOW.plus(Duration.ofDays(2)))).isEqualTo("expires in 2 days");
        assertThat(dates.invitationHint(NOW.plus(Duration.ofDays(1)))).isEqualTo("expires in 1 day");
        assertThat(dates.invitationHint(NOW.plus(Duration.ofHours(2)))).isEqualTo("expires today");
        assertThat(dates.invitationHint(NOW.minus(Duration.ofHours(2)))).isEqualTo("expired today");
        assertThat(dates.invitationHint(NOW.minus(Duration.ofDays(5)))).isEqualTo("expired 5 days ago");
        // 11:30 UTC tomorrow is less than 24 hours away, but it is tomorrow's date: "in 1 day".
        assertThat(dates.invitationHint(Instant.parse("2026-10-22T11:30:00Z"))).isEqualTo("expires in 1 day");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("el"));
        assertThat(dates.invitationHint(NOW.plus(Duration.ofDays(2)))).isEqualTo("λήγει σε 2 ημέρες");
    }

    // P17: after "Invitation expired".
    @Test
    void invitationExpiredHintSaysHowLongAgo() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(dates.invitationExpiredHint(NOW.minus(Duration.ofHours(2)))).isEqualTo("today");
        assertThat(dates.invitationExpiredHint(NOW.minus(Duration.ofDays(1)))).isEqualTo("1 day ago");
        assertThat(dates.invitationExpiredHint(NOW.minus(Duration.ofDays(5)))).isEqualTo("5 days ago");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("el"));
        assertThat(dates.invitationExpiredHint(NOW.minus(Duration.ofDays(5)))).isEqualTo("πριν από 5 ημέρες");
    }

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
