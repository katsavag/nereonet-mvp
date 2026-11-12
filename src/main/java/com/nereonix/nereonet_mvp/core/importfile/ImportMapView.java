package com.nereonix.nereonet_mvp.core.importfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;

/**
 * The mapping step's page (templates/importfile/map.html, P24), model attribute "map": every text resolved, so the
 * template only lays it out.
 *
 * rows      one per field: its select, and for a two-column field (Full name) the second one (last name)
 * columns   the file's columns, with the option text of the selects and the field each one goes to now
 * ignored   the columns no field uses, as one line ("D · Nationality, E · Date of birth"), or "None"
 * errors    the selects with an error, in the order of the fields: key -> message
 * headers   the rows that may hold the column names, as options ("Row 3: Full name, Email, Rank")
 */
public record ImportMapView(List<Row> rows, List<Column> columns, String ignored, Map<String, String> errors,
                            List<Option> headers) {

    /** One select. key: the field key or second key (its id is "column-" + key, its name "column." + key). */
    /** target: "To: Full name", what the preview says of the column chosen here (js/import-map.js). */
    public record Select(String key, String label, boolean optional, String hint, String fieldLabel, String target,
                         Integer column, String error) {
    }

    public record Row(List<Select> selects) {
    }

    /**
     * label: "B · Email"; target: "To: Email", or null when no field uses the column; hasData: it has a name or a value,
     * so it is listed as not imported when no field uses it.
     */
    public record Column(int index, String letter, String name, String label, String option, String target,
                         boolean hasData) {
    }

    public record Option(int value, String text, boolean selected) {
    }

    static ImportMapView of(ImportUpload upload, List<ImportField> fields, Map<String, String> errorKeys,
                            MessageSource messages, Locale locale) {
        List<String> header = upload.header();
        Map<Integer, String> targets = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (ImportField field : fields) {
            String label = text(messages, locale, field.labelKey());
            String goesTo = text(messages, locale, "importFile.map.goesTo", label);
            List<Select> selects = new ArrayList<>();
            selects.add(select(upload, field.key(), label, !field.required(),
                    field.isTwoColumns() ? text(messages, locale, "importFile.map.twoColumnsHint") : null, label, goesTo,
                    errorKeys, errors, messages, locale));
            target(targets, upload.column(field.key()), goesTo);
            if (field.isTwoColumns()) {
                String last = text(messages, locale, "importFile.map.lastName");
                selects.add(select(upload, field.secondKey(), last, true, null,
                        text(messages, locale, "importFile.map.lastNameOf", label), goesTo, errorKeys, errors,
                        messages, locale));
                target(targets, upload.column(field.secondKey()), goesTo);
            }
            rows.add(new Row(selects));
        }

        String noName = text(messages, locale, "importFile.map.noName");
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).isEmpty() ? noName : header.get(i);
            String example = upload.example(i);
            String option = ImportUpload.letter(i) + " · " + name + (example.isEmpty() ? ""
                    : " · " + text(messages, locale, "importFile.map.example", abbreviate(example, 40)));
            columns.add(new Column(i, ImportUpload.letter(i), name, ImportUpload.letter(i) + " · " + name, option,
                    targets.get(i), !header.get(i).isEmpty() || !example.isEmpty()));
        }

        List<String> ignoredNames = upload.ignoredColumns().stream()
                .map(i -> columns.get(i).letter() + " · " + columns.get(i).name()).toList();
        String ignored = ignoredNames.isEmpty() ? text(messages, locale, "importFile.map.ignored.none")
                : String.join(", ", ignoredNames);

        List<Option> headers = new ArrayList<>();
        List<ImportSheet.Row> candidates = upload.headerCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            ImportSheet.Row row = candidates.get(i);
            String cells = String.join(", ", row.cells().stream().filter(cell -> !cell.isEmpty()).toList());
            headers.add(new Option(i, text(messages, locale, "importFile.map.headerOption", row.number(),
                    abbreviate(cells, 60)), i == upload.getHeaderIndex()));
        }
        return new ImportMapView(rows, columns, ignored, errors, headers);
    }

    private static Select select(ImportUpload upload, String key, String label, boolean optional, String hint,
                                 String fieldLabel, String target, Map<String, String> errorKeys,
                                 Map<String, String> errors, MessageSource messages, Locale locale) {
        String error = errorKeys.containsKey(key) ? text(messages, locale, errorKeys.get(key), fieldLabel) : null;
        if (error != null) {
            errors.put(key, error);
        }
        return new Select(key, label, optional, hint, fieldLabel, target, upload.column(key), error);
    }

    private static void target(Map<Integer, String> targets, Integer column, String text) {
        if (column != null) {
            targets.putIfAbsent(column, text);
        }
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String text(MessageSource messages, Locale locale, String key, Object... args) {
        return messages.getMessage(key, args, locale);
    }
}
