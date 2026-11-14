package com.nereonix.nereonet_mvp.core.vesseldata;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A vessel's stored VesselAPI data for the pages (P45): the Vessel data tab and the Overview's summary line. Built from
 * the VesselDataSnapshot rows only: opening a page never calls VesselAPI. Each value is parsed from rawJson on read.
 */
public class VesselDataView {

    /** One call's row; snapshot is null when the call was never made for this vessel. */
    public record Card(VesselEndpoint endpoint, VesselDataSnapshot snapshot) {

        /** A call worked at least once: there is data to show. */
        public boolean hasData() {
            return snapshot != null && snapshot.getRawJson() != null;
        }

        public Instant providerReportedAt() {
            return snapshot == null ? null : snapshot.getProviderReportedAt();
        }

        public Instant fetchedAt() {
            return snapshot == null ? null : snapshot.getLastSuccessAt();
        }

        public Instant lastAttemptAt() {
            return snapshot == null ? null : snapshot.getLastAttemptAt();
        }

        public VesselApiResult lastAttemptResult() {
            return snapshot == null ? null : snapshot.getLastAttemptResult();
        }

        /** The last call failed (not OK, not 404): the card says so under the data it still shows. */
        public boolean lastAttemptFailed() {
            return snapshot != null && snapshot.getLastAttemptResult().failed();
        }

        /** The last call was a 404: no such vessel, no recent position, no ETA report. */
        public boolean notFound() {
            return snapshot != null && snapshot.getLastAttemptResult() == VesselApiResult.NOT_FOUND;
        }

        /** The body indented, for "Full VesselAPI response"; null without data. */
        public String prettyJson() {
            return hasData() ? VesselJson.pretty(snapshot.getRawJson()) : null;
        }
    }

    private final Map<VesselEndpoint, Card> cards = new EnumMap<>(VesselEndpoint.class);

    public VesselDataView(List<VesselDataSnapshot> snapshots) {
        for (VesselEndpoint endpoint : VesselEndpoint.values()) {
            cards.put(endpoint, new Card(endpoint, null));
        }
        for (VesselDataSnapshot snapshot : snapshots) {
            cards.put(snapshot.getEndpoint(), new Card(snapshot.getEndpoint(), snapshot));
        }
    }

    public Card card(VesselEndpoint endpoint) {
        return cards.get(endpoint);
    }

    public Card getParticularsCard() {
        return card(VesselEndpoint.PARTICULARS);
    }

    public Card getPositionCard() {
        return card(VesselEndpoint.POSITION);
    }

    public Card getEtaCard() {
        return card(VesselEndpoint.ETA);
    }

    /** The cards in order, for "Full VesselAPI response". */
    public List<Card> getCards() {
        return List.copyOf(cards.values());
    }

    /** Nothing was ever fetched for this vessel. */
    public boolean isEmpty() {
        return cards.values().stream().allMatch(card -> card.snapshot() == null);
    }

    public VesselParticulars getParticulars() {
        Card card = getParticularsCard();
        return card.hasData() ? VesselParticulars.parse(card.snapshot().getRawJson()) : null;
    }

    public AisPosition getPosition() {
        Card card = getPositionCard();
        return card.hasData() ? AisPosition.parse(card.snapshot().getRawJson()) : null;
    }

    public EtaReport getEta() {
        Card card = getEtaCard();
        return card.hasData() ? EtaReport.parse(card.snapshot().getRawJson()) : null;
    }
}
