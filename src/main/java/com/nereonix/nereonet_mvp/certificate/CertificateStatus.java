package com.nereonix.nereonet_mvp.certificate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * The Status filter of the Certificates list (P20): ?status=expired, 30, 60, 90, valid or all, and combinations of
 * the states joined with a comma, so the Overview and the sidebar counter can link to exactly what they count
 * (?status=expired,30). Without the parameter, or with a value it does not know: expired and within 90 days.
 * <ul>
 *   <li>expired: the expiry date has passed (UTC days, as the badges and the reminder job);</li>
 *   <li>30, 60, 90: not expired, expiring within that many days, today included ("Within 60 days" includes the
 *       ones within 30);</li>
 *   <li>valid: more than 90 days left, the "Valid" badge;</li>
 *   <li>all: every certificate.</li>
 * </ul>
 * Labels: certificate.filter.status.&lt;key&gt; (a combination: its own key with "-" for ",", else the states'
 * labels joined).
 */
public record CertificateStatus(Set<State> states) {

    /** One state of the filter. Days are counted from today (UTC). */
    public enum State {
        EXPIRED("expired"),
        DAYS_30("30"),
        DAYS_60("60"),
        DAYS_90("90"),
        VALID("valid");

        private final String key;

        State(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        static Optional<State> of(String key) {
            return Arrays.stream(values()).filter(state -> state.key.equals(key)).findFirst();
        }

        boolean matches(LocalDate expiresOn, LocalDate today) {
            return switch (this) {
                case EXPIRED -> expiresOn.isBefore(today);
                case DAYS_30, DAYS_60, DAYS_90 -> !expiresOn.isBefore(today) && !expiresOn.isAfter(today.plusDays(days()));
                case VALID -> expiresOn.isAfter(today.plusDays(DAYS_90.days()));
            };
        }

        Predicate predicate(CriteriaBuilder cb, Expression<LocalDate> expiresOn, LocalDate today) {
            return switch (this) {
                case EXPIRED -> cb.lessThan(expiresOn, today);
                case DAYS_30, DAYS_60, DAYS_90 -> cb.between(expiresOn, today, today.plusDays(days()));
                case VALID -> cb.greaterThan(expiresOn, today.plusDays(DAYS_90.days()));
            };
        }

        private int days() {
            return Integer.parseInt(key);
        }
    }

    /** "all": every certificate. */
    public static final String ALL = "all";

    /** Without a status: expired and within 90 days, what the list showed before P20. */
    public static final CertificateStatus DEFAULT = new CertificateStatus(EnumSet.of(State.EXPIRED, State.DAYS_90));

    /** Expired or within 30 days: what the Overview and the sidebar counter count (?status=expired,30). */
    public static final CertificateStatus OPEN_ITEMS = new CertificateStatus(EnumSet.of(State.EXPIRED, State.DAYS_30));

    /** The options of the Status select, in order, after the default ("" ). */
    public static final List<String> OPTIONS = List.of("expired", "30", "60", "90", "valid", ALL);

    /**
     * The filter in the URL: null or blank is the default, "all" every certificate, else one or more state keys
     * joined with a comma. Anything it does not know is the default, so any URL shows a list.
     */
    public static CertificateStatus of(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        if (value.strip().equals(ALL)) {
            return new CertificateStatus(EnumSet.allOf(State.class));
        }
        Set<State> states = EnumSet.noneOf(State.class);
        for (String key : value.split(",")) {
            Optional<State> state = State.of(key.strip());
            if (state.isEmpty()) {
                return DEFAULT;
            }
            states.add(state.get());
        }
        return new CertificateStatus(states);
    }

    public CertificateStatus {
        states = states.isEmpty() ? EnumSet.allOf(State.class) : EnumSet.copyOf(states);
    }

    public boolean isDefault() {
        return states.equals(DEFAULT.states);
    }

    public boolean isAll() {
        return states.equals(EnumSet.allOf(State.class));
    }

    /** The value of ?status=, in a fixed order: "expired,30"; "all"; null for the default (no parameter). */
    public String key() {
        if (isDefault()) {
            return null;
        }
        if (isAll()) {
            return ALL;
        }
        return states.stream().map(State::key).collect(Collectors.joining(","));
    }

    /** One of OPTIONS (or the default): the select can show it; otherwise it gets an option of its own. */
    public boolean isOption() {
        return isDefault() || OPTIONS.contains(key());
    }

    /** The message key of this filter's label: certificate.filter.status.default, .expired, .expired-30, ... */
    public String labelKey() {
        return "certificate.filter.status." + (isDefault() ? "default" : key().replace(',', '-'));
    }

    /** The message keys of the single states, to join when a combination has no label of its own. */
    public List<String> stateLabelKeys() {
        List<String> keys = new ArrayList<>();
        states.forEach(state -> keys.add("certificate.filter.status." + state.key()));
        return keys;
    }

    public boolean matches(LocalDate expiresOn, LocalDate today) {
        return states.stream().anyMatch(state -> state.matches(expiresOn, today));
    }

    /** The condition on the expiry date; null when every certificate matches. */
    Predicate predicate(CriteriaBuilder cb, Expression<LocalDate> expiresOn, LocalDate today) {
        if (isAll()) {
            return null;
        }
        return cb.or(states.stream().map(state -> state.predicate(cb, expiresOn, today)).toArray(Predicate[]::new));
    }
}
