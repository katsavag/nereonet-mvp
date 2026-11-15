package com.nereonix.nereonet_mvp.core.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Answers instead of Claude when app.claude.fake is true: dev screens without a key, and the tests. Simple
 * rules, no model:
 *   - The text is cut into phrases at commas, semicolons, full stops (not decimal points) and "and".
 *   - A phrase's quantity is its first number, in digits ("2.5", "2,5") or English words ("thirty five", "a dozen").
 *   - Its item is the one whose whole name it contains (a plural or singular of the last word is fine): confidence
 *     0.95 (High). Otherwise the one item with a word of its name (4 letters or more) in it: 0.45 (Low).
 *   - No quantity, no item, two items equally likely, or an item already counted: the phrase is unmatched; with
 *     neither a number nor an item ("Good morning") it is left out.
 * Tests can make it fail with fail().
 */
@Component
@ConditionalOnProperty(name = "app.claude.fake", havingValue = "true")
public class FakeCountExtractor implements CountExtractor {

    static final double WHOLE_NAME = 0.95;
    static final double ONE_WORD = 0.45;

    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11),
            Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14), Map.entry("fifteen", 15),
            Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
            Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40),
            Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80),
            Map.entry("ninety", 90));

    private volatile CountExtractionException failure;
    private final List<String> transcripts = Collections.synchronizedList(new ArrayList<>());

    @Override
    public Extraction extract(String transcript, List<Item> items) {
        transcripts.add(transcript);
        if (failure != null) {
            throw failure;
        }
        if (transcript == null || transcript.isBlank()) {
            return Extraction.nothing();
        }
        List<Line> lines = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        Set<Long> counted = new HashSet<>();
        for (String phrase : transcript.split("[,;]|\\.(?!\\d)|\\band\\b")) {
            String words = phrase.strip();
            if (words.isEmpty()) {
                continue;
            }
            List<String> tokens = tokens(words);
            BigDecimal quantity = CountAnswer.storeQuantity(quantity(tokens));
            Match match = match(tokens, items);
            if (quantity == null && match == null) {
                // Neither a number nor an item ("Good morning"): not part of the count.
                continue;
            }
            if (quantity == null || match == null || !counted.add(match.item().id())) {
                unmatched.add(words);
                continue;
            }
            lines.add(new Line(match.item().id(), quantity, words, match.confidence()));
        }
        return new Extraction(List.copyOf(lines), List.copyOf(unmatched));
    }

    /** From now on, every text fails so (tests). */
    public void fail(CountExtractionException.Reason reason) {
        failure = new CountExtractionException(reason, "Fake " + reason);
    }

    /** Every text received so far. */
    public List<String> transcripts() {
        synchronized (transcripts) {
            return List.copyOf(transcripts);
        }
    }

    public void reset() {
        failure = null;
        transcripts.clear();
    }

    private record Match(Item item, double confidence) {
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9.,]+")) {
            String clean = token.replaceAll("^[.,]+|[.,]+$", "");
            if (!clean.isEmpty()) {
                tokens.add(clean);
            }
        }
        return tokens;
    }

    // The first number of the phrase; null without one.
    static BigDecimal quantity(List<String> tokens) {
        Integer total = null;
        int current = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (total == null && token.matches("[0-9]+([.,][0-9]+)?")) {
                return new BigDecimal(token.replace(',', '.'));
            }
            Integer value = NUMBERS.get(token);
            if (value != null) {
                current += value;
                total = current;
            } else if (token.equals("hundred") && total != null) {
                current *= 100;
                total = current;
            } else if (token.equals("dozen")) {
                return BigDecimal.valueOf(12L * (total == null ? 1 : total));
            } else if (token.equals("half") && total == null) {
                return new BigDecimal("0.5");
            } else if ((token.equals("a") || token.equals("an")) && total == null) {
                // "a dozen", "a kilo of": one, unless a number follows
                if (i + 1 < tokens.size() && tokens.get(i + 1).equals("dozen")) {
                    continue;
                }
                total = 1;
                current = 1;
            } else if (total != null) {
                break;
            }
        }
        return total == null ? null : BigDecimal.valueOf(total);
    }

    private static Match match(List<String> tokens, List<Item> items) {
        Item whole = null;
        int wholeLength = 0;
        List<Item> partial = new ArrayList<>();
        for (Item item : items) {
            List<String> name = tokens(item.name());
            if (name.isEmpty()) {
                continue;
            }
            if (containsName(tokens, name)) {
                if (name.size() > wholeLength) {
                    whole = item;
                    wholeLength = name.size();
                }
            } else if (name.stream().anyMatch(word -> word.length() >= 4
                    && tokens.stream().anyMatch(token -> same(token, word)))) {
                partial.add(item);
            }
        }
        if (whole != null) {
            return new Match(whole, WHOLE_NAME);
        }
        return partial.size() == 1 ? new Match(partial.get(0), ONE_WORD) : null;
    }

    private static boolean containsName(List<String> tokens, List<String> name) {
        for (int start = 0; start + name.size() <= tokens.size(); start++) {
            boolean all = true;
            for (int j = 0; j < name.size() && all; j++) {
                all = same(tokens.get(start + j), name.get(j));
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    // The same word, or its plural with -s or -es.
    private static boolean same(String a, String b) {
        return a.equals(b) || a.equals(b + "s") || a.equals(b + "es") || b.equals(a + "s") || b.equals(a + "es");
    }
}
