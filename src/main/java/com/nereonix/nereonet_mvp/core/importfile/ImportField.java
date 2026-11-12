package com.nereonix.nereonet_mvp.core.importfile;

import java.util.List;

/**
 * A field the import fills, e.g. the crew member's email (P24). The column holding it is guessed from the file's
 * column names (aliases, normalized as ImportNames.normalize, most likely first) and confirmed by the user.
 *
 * key          the field's key, also the property name on the review form ("fullName")
 * labelKey     its label, a message key
 * required     the mapping step blocks Continue while it has no column
 * firstAliases a field that can also come from two columns joined with a space (first and last name): the names of
 *              the first column; lastAliases those of the second. Empty for an ordinary field.
 * values       for a field whose values are matched to a fixed list (the rank), how; otherwise null
 */
public record ImportField(String key, String labelKey, boolean required, List<String> aliases,
                          List<String> firstAliases, List<String> lastAliases, ValueMatcher values) {

    /** The key of the second column of a two-column field: "fullName2". */
    public static final String SECOND = "2";

    public static ImportField of(String key, String labelKey, boolean required, List<String> aliases) {
        return new ImportField(key, labelKey, required, aliases, List.of(), List.of(), null);
    }

    public boolean isTwoColumns() {
        return !lastAliases.isEmpty();
    }

    public String secondKey() {
        return key + SECOND;
    }

    public ImportField withValues(ValueMatcher matcher) {
        return new ImportField(key, labelKey, required, aliases, firstAliases, lastAliases, matcher);
    }

    public ImportField withTwoColumns(List<String> first, List<String> last) {
        return new ImportField(key, labelKey, required, aliases, first, last, values);
    }

    /**
     * Matches the values of one field to a fixed list (P24, e.g. "C/E" to Chief Engineer). A value matched exactly is
     * used as it is; every other distinct value is asked about once, with the suggestion chosen.
     */
    public interface ValueMatcher {

        /** The canonical value if raw is exactly one of the list (ignoring case and spacing), otherwise null. */
        String exact(String raw);

        /** The most likely canonical value for a value that is not exact, or null when there is no good guess. */
        String suggest(String raw);

        /** The choices, in groups (one group with a null label when not grouped). */
        List<ChoiceGroup> choices();
    }

    /** labelKey may be null for an ungrouped list. */
    public record ChoiceGroup(String labelKey, List<Choice> choices) {
    }

    /** value: the canonical value; labelKey: its label, a message key. */
    public record Choice(String value, String labelKey) {
    }
}
