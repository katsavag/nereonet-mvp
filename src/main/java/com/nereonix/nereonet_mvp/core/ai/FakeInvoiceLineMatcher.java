package com.nereonix.nereonet_mvp.core.ai;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Answers instead of Claude when app.claude.fake is true (dev screens without a key, and the tests): it matches
 * nothing, so every line the word matching could not place stays a new item's row.
 */
@Component
@ConditionalOnProperty(name = "app.claude.fake", havingValue = "true")
public class FakeInvoiceLineMatcher implements InvoiceLineMatcher {

    @Override
    public List<Match> match(List<Line> lines, List<Candidate> candidates) {
        return List.of();
    }
}
