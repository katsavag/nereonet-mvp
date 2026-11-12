package com.nereonix.nereonet_mvp.core.importfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The best guess of which column holds which field, and of which row holds the column names (P24).
// A column name scores when it is one of the field's aliases (exact, ignoring case, accents and punctuation), or less
// when it contains all words of one ("Vessel IMO No." contains "imo"). Earlier aliases score higher. Each column goes
// to one field at most, the best scores first.
public final class ColumnGuesser {

    // Rows looked at for the column names: a title or a few lines of notes above the table are common.
    static final int HEADER_ROWS = 20;

    private static final int EXACT = 200;
    private static final int CONTAINS = 100;

    private ColumnGuesser() {
    }

    /** Field key (or ImportField.secondKey) -> column index, for the fields that have a likely column. */
    public static Map<String, Integer> guess(List<String> header, List<ImportField> fields) {
        record Candidate(ImportField field, int column, int score, boolean first) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ImportField field : fields) {
            for (int column = 0; column < header.size(); column++) {
                String name = header.get(column);
                int whole = score(name, field.aliases());
                int first = score(name, field.firstAliases());
                if (whole > 0 || first > 0) {
                    candidates.add(new Candidate(field, column, Math.max(whole, first), first > whole));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(Candidate::column));

        Map<String, Integer> mapping = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        List<ImportField> firstOnly = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (mapping.containsKey(candidate.field().key()) || used.contains(candidate.column())) {
                continue;
            }
            mapping.put(candidate.field().key(), candidate.column());
            used.add(candidate.column());
            if (candidate.first()) {
                firstOnly.add(candidate.field());
            }
        }
        // "First name" found: the last name is the best remaining column named like one.
        for (ImportField field : firstOnly) {
            int best = -1;
            int bestScore = 0;
            for (int column = 0; column < header.size(); column++) {
                int score = score(header.get(column), field.lastAliases());
                if (!used.contains(column) && score > bestScore) {
                    best = column;
                    bestScore = score;
                }
            }
            if (best >= 0) {
                mapping.put(field.secondKey(), best);
                used.add(best);
            }
        }
        return mapping;
    }

    /**
     * The index of the row holding the column names: among the first rows, the one where the most fields find a column;
     * when no row names any field, the first of the fullest rows (a title above the table has fewer cells).
     */
    public static int headerIndex(List<ImportSheet.Row> rows, List<ImportField> fields) {
        int limit = Math.min(rows.size(), HEADER_ROWS);
        int best = 0;
        int bestFields = 0;
        for (int i = 0; i < limit; i++) {
            int found = (int) guess(rows.get(i).cells(), fields).keySet().stream()
                    .filter(key -> fields.stream().anyMatch(field -> field.key().equals(key)))
                    .count();
            if (found > bestFields) {
                best = i;
                bestFields = found;
            }
        }
        if (bestFields > 0) {
            return best;
        }
        int fullest = 0;
        for (int i = 0; i < limit; i++) {
            if (rows.get(i).filledCount() > rows.get(fullest).filledCount()) {
                fullest = i;
            }
        }
        return fullest;
    }

    static int score(String columnName, List<String> aliases) {
        String name = ImportNames.normalize(columnName);
        if (name.isEmpty()) {
            return 0;
        }
        List<String> words = Arrays.asList(name.split(" "));
        int best = 0;
        for (int i = 0; i < aliases.size(); i++) {
            String alias = aliases.get(i);
            int score = 0;
            if (name.equals(alias)) {
                score = EXACT - i;
            } else if (words.containsAll(Arrays.asList(alias.split(" ")))) {
                score = CONTAINS - i;
            }
            best = Math.max(best, score);
        }
        return best;
    }
}
