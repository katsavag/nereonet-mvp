package com.nereonix.nereonet_mvp.core.ai;

import java.util.List;

/**
 * Says which item an invoice line is when the word matching (InvoiceMatcher) could not place it ("MINCED BEEF FROZEN"
 * for the vessel's "Minced meat"), called by the server only, once per reading of an invoice (InvoiceReadingService).
 * ClaudeInvoiceLineMatcher is the real one; FakeInvoiceLineMatcher answers in dev (app.claude.fake) and in the tests.
 * Only the lines' descriptions and units and the candidates' names and units are sent anywhere: no prices, no
 * quantities, no name of a person or a vessel.
 */
public interface InvoiceLineMatcher {

    /**
     * One invoice line not placed: its number in this request, its product with the pack size after it
     * (InvoiceMatcher.productAndPack: "Black pepper, 500 g") and the unit as the invoice has it.
     */
    record Line(int no, String description, String unit) {
    }

    /** One item the lines may be: its number in this request, the item's name and unit. The order's first. */
    record Candidate(int no, String name, String unit) {
    }

    /** The line is the candidate, and how sure the matcher is, from 0 to 1. */
    record Match(int line, int candidate, double confidence) {
    }

    /**
     * At most one match per line, only for lines and candidates of this request; a line the matcher places nowhere
     * has none. Never null. A RuntimeException when the provider fails or takes longer than its timeout: its message
     * names only what went wrong, never the key or the lines.
     */
    List<Match> match(List<Line> lines, List<Candidate> candidates);
}
