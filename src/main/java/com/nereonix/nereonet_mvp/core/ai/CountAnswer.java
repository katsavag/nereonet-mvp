package com.nereonix.nereonet_mvp.core.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Extraction;
import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Item;
import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Line;
import com.nereonix.nereonet_mvp.core.stock.StockService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the model's answer (P57) and trusts none of it: the model is told the rules, our code checks them.
 * The answer asked for: {"counts": [{"item_id": 12, "quantity": 5, "words": "five kilos of tomatoes",
 * "confidence": 0.9}], "unmatched": ["three cans of herring"]}.
 *   - A count whose item_id is not one of the items sent, whose quantity is missing or fails the store's rule
 *     (StockService.isValidQuantity), or whose item was already counted in this answer goes to unmatched, by its words.
 *   - quantity: a number, or a number as text ("2.5", "2,5").
 *   - confidence: a number from 0 to 1; anything else is 0 (Low), so the cook looks at that row.
 *   - words and each unmatched phrase: stripped, at most WORDS_MAX_LENGTH characters; blank ones are dropped.
 *   - At most MAX_ENTRIES counts and unmatched phrases.
 * An answer that is not a JSON object is Extraction.nothing(): nothing understood.
 */
public final class CountAnswer {

    public static final int WORDS_MAX_LENGTH = 200;
    public static final int MAX_ENTRIES = 200;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private CountAnswer() {
    }

    public static Extraction parse(String json, List<Item> items) {
        JsonNode root = read(json);
        if (root == null || !root.isObject()) {
            return Extraction.nothing();
        }
        Set<Long> sent = new HashSet<>();
        items.forEach(item -> sent.add(item.id()));
        Set<Long> counted = new HashSet<>();
        List<Line> lines = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        JsonNode counts = root.get("counts");
        if (counts != null && counts.isArray()) {
            for (JsonNode count : counts) {
                if (lines.size() + unmatched.size() >= MAX_ENTRIES) {
                    break;
                }
                if (!count.isObject()) {
                    continue;
                }
                String words = words(count.get("words"));
                Long itemId = id(count.get("item_id"));
                BigDecimal quantity = quantity(count.get("quantity"));
                if (itemId == null || !sent.contains(itemId) || quantity == null || !counted.add(itemId)) {
                    if (words != null) {
                        unmatched.add(words);
                    }
                    continue;
                }
                lines.add(new Line(itemId, quantity, words == null ? "" : words, confidence(count.get("confidence"))));
            }
        }
        JsonNode phrases = root.get("unmatched");
        if (phrases != null && phrases.isArray()) {
            for (JsonNode phrase : phrases) {
                if (lines.size() + unmatched.size() >= MAX_ENTRIES) {
                    break;
                }
                String words = words(phrase);
                if (words != null) {
                    unmatched.add(words);
                }
            }
        }
        return new Extraction(List.copyOf(lines), List.copyOf(unmatched));
    }

    /** A quantity the store accepts, without trailing zeros; null otherwise. */
    public static BigDecimal storeQuantity(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal plain = value.stripTrailingZeros();
        if (plain.scale() < 0) {
            plain = plain.setScale(0);
        }
        return StockService.isValidQuantity(plain) ? plain : null;
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

    private static Long id(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        if (node.isString() && node.asString().strip().matches("[0-9]{1,18}")) {
            return Long.valueOf(node.asString().strip());
        }
        return null;
    }

    private static BigDecimal quantity(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNumber()) {
            return storeQuantity(node.decimalValue());
        }
        if (node.isString()) {
            try {
                return storeQuantity(new BigDecimal(node.asString().strip().replace(',', '.')));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static double confidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return 0;
        }
        double value = node.doubleValue();
        return value >= 0 && value <= 1 ? value : 0;
    }

    private static String words(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        String words = node.asString().strip().replaceAll("\\s+", " ");
        if (words.isEmpty()) {
            return null;
        }
        return words.length() > WORDS_MAX_LENGTH ? words.substring(0, WORDS_MAX_LENGTH) : words;
    }
}
