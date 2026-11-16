package com.nereonix.nereonet_mvp.core.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nereonix.nereonet_mvp.core.ai.InvoiceLineMatcher.Candidate;
import com.nereonix.nereonet_mvp.core.ai.InvoiceLineMatcher.Line;
import com.nereonix.nereonet_mvp.core.ai.InvoiceLineMatcher.Match;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the model's answer and trusts none of it, as CountAnswer does: the model is told the rules, our code checks
 * them. The answer asked for: {"matches": [{"line": 1, "candidate": 7, "confidence": 0.9}]}, candidate 0 for none.
 *   - An entry whose line is not one of the lines sent is ignored.
 *   - An entry whose candidate is not one of the candidates sent (0 and null included) matches nothing.
 *   - A line named in two entries matches nothing: the answer contradicts itself.
 *   - confidence: a number from 0 to 1; anything else is 0, which no threshold accepts.
 * An answer that is not a JSON object with "matches" (cut short, say) matches nothing.
 */
public final class InvoiceMatchAnswer {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private InvoiceMatchAnswer() {
    }

    public static List<Match> parse(String json, List<Line> lines, List<Candidate> candidates) {
        JsonNode root = read(json);
        JsonNode entries = root == null || !root.isObject() ? null : root.get("matches");
        if (entries == null || !entries.isArray()) {
            return List.of();
        }
        Set<Integer> lineNos = new HashSet<>();
        lines.forEach(line -> lineNos.add(line.no()));
        Set<Integer> candidateNos = new HashSet<>();
        candidates.forEach(candidate -> candidateNos.add(candidate.no()));
        Set<Integer> seen = new HashSet<>();
        Set<Integer> twice = new HashSet<>();
        List<Match> matches = new ArrayList<>();
        for (JsonNode entry : entries) {
            Integer line = entry.isObject() ? number(entry.get("line")) : null;
            if (line == null || !lineNos.contains(line)) {
                continue;
            }
            if (!seen.add(line)) {
                twice.add(line);
                continue;
            }
            Integer candidate = number(entry.get("candidate"));
            if (candidate != null && candidateNos.contains(candidate)) {
                matches.add(new Match(line, candidate, confidence(entry.get("confidence"))));
            }
        }
        matches.removeIf(match -> twice.contains(match.line()));
        return List.copyOf(matches);
    }

    private static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Integer number(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
    }

    private static double confidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return 0;
        }
        double value = node.doubleValue();
        return value >= 0 && value <= 1 ? value : 0;
    }
}
