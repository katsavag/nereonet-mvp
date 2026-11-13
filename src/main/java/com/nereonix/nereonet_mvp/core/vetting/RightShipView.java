package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.nereonix.nereonet_mvp.core.vetting.Subscore.Impact;

/**
 * A vessel's RightShip standing as the pages show it (P40), worked out from its current row (null: no RightShip data)
 * and today's UTC date. The rules, in one place:
 * <ul>
 *   <li>Safety Score: "3/5"; "0/5" sanctioned (no safety score); "N/A" not rated. Tone: 2 or below danger, else ok.</li>
 *   <li>Subscores: the lowest known one(s) limit the score: warning tone and "Lowest", unless every known one is 5/5.</li>
 *   <li>Inspection: InspectionState (expiring within 60 days, expired, unacceptable).</li>
 *   <li>Needs attention: the inspection needs attention, or the Safety Score is 2 or below.</li>
 * </ul>
 */
public record RightShipView(ShipRightShipStatus status, LocalDate today) {

    /** A Safety Score at or below this needs attention (the Overview, the Vetting list's filter). */
    public static final int LOW_SCORE = 2;
    public static final int MAX_SCORE = 5;

    /** The Vetting list's Status filter: needs attention, valid, not rated. */
    public static final String ATTENTION = "attention";
    public static final String VALID = "valid";
    public static final String NOT_RATED = "notrated";
    public static final List<String> STATUSES = List.of(ATTENTION, VALID, NOT_RATED);

    /** One subscore row: value 1 to 5, or null when not known. */
    public record SubscoreRow(Subscore subscore, Integer value, boolean lowest) {

        public boolean known() {
            return value != null;
        }

        /** The bar's tone: the lowest one(s) warning, the others ok. */
        public String tone() {
            return lowest ? "warning" : "ok";
        }
    }

    public record SubscoreGroup(Impact impact, List<SubscoreRow> rows) {
    }

    /** There is RightShip data for the vessel (a row, even one without a score). */
    public boolean hasData() {
        return status != null;
    }

    public Integer score() {
        return status == null ? null : status.getSafetyScore();
    }

    public boolean rated() {
        return score() != null;
    }

    /** Safety Score 0: the vessel is sanctioned and has no safety score. */
    public boolean sanctioned() {
        return Objects.equals(score(), 0);
    }

    public boolean indicative() {
        return status != null && status.isIndicative() && rated();
    }

    public boolean lowScore() {
        return rated() && score() <= LOW_SCORE;
    }

    /** The colour of the score's bar: danger at 2 or below, ok above, neutral when not rated. */
    public String scoreTone() {
        return !rated() ? "neutral" : lowScore() ? "danger" : "ok";
    }

    public LocalDate calculatedAt() {
        return status == null ? null : status.getCalculatedAt();
    }

    public boolean fromApi() {
        return status != null && status.getSource() == RightShipSource.API;
    }

    // Subscores

    /** The value of the lowest known subscore, or null when none is known or all are 5/5 (nothing limits). */
    public Integer lowestSubscore() {
        if (status == null) {
            return null;
        }
        Integer lowest = Arrays.stream(Subscore.values()).map(status::subscore).filter(Objects::nonNull)
                .min(Integer::compare).orElse(null);
        return lowest == null || lowest == MAX_SCORE ? null : lowest;
    }

    /** The subscores in their three impact groups, highest impact first. */
    public List<SubscoreGroup> groups() {
        Integer lowest = lowestSubscore();
        return Arrays.stream(Impact.values()).map(impact -> new SubscoreGroup(impact, impact.subscores().stream()
                .map(s -> {
                    Integer value = status == null ? null : status.subscore(s);
                    return new SubscoreRow(s, value, value != null && value.equals(lowest));
                }).toList())).toList();
    }

    // GHG Rating

    public boolean ghgRated() {
        return status != null && status.getGhgRating() != null && status.getGhgRating().rated();
    }

    /** "C", "A+"; null when not rated (shown "N/A"). */
    public String ghgLetter() {
        if (!ghgRated()) {
            return null;
        }
        return status.getGhgRating().name() + (status.isGhgPlus() && status.getGhgRating() == GhgRating.A ? "+" : "");
    }

    /** The letter's colour class, ghg-a ... ghg-e (the icon only), or ghg-u. */
    public String ghgClass() {
        return ghgRated() ? "ghg-" + status.getGhgRating().name().toLowerCase(java.util.Locale.ROOT) : "ghg-u";
    }

    public boolean ghgVerified() {
        return ghgRated() && status.isGhgVerified();
    }

    // Inspection

    public InspectionState inspection() {
        return status == null ? InspectionState.NONE : status.inspectionState(today);
    }

    public LocalDate inspectionValidUntil() {
        return status == null ? null : status.getInspectionValidUntil();
    }

    /** Days from today to the valid-until date (negative once expired); 0 without a date. */
    public long inspectionDaysLeft() {
        LocalDate until = inspectionValidUntil();
        return until == null ? 0 : ChronoUnit.DAYS.between(today, until);
    }

    // The list

    /** The inspection needs attention, or the Safety Score is 2 or below. */
    public boolean needsAttention() {
        return inspection().needsAttention() || lowScore();
    }

    /** The Status filter this vessel falls under: needs attention, else not rated, else valid. */
    public String filterStatus() {
        return needsAttention() ? ATTENTION : !rated() ? NOT_RATED : VALID;
    }

    /** The default order of the Vetting list: expired (or unacceptable) 0, expiring 1, the rest 2. */
    public int urgency() {
        return switch (inspection()) {
            case EXPIRED, UNACCEPTABLE -> 0;
            case EXPIRING -> 1;
            default -> 2;
        };
    }
}
