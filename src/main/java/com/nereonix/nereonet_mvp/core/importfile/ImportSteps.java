package com.nereonix.nereonet_mvp.core.importfile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The steps of an import from a file, shared by the crew and the item import (P24): Upload (with the sheet choice for
 * a workbook), Map columns (with the value mapping), Review, Done. The controllers keep the ImportForm in the HTTP
 * session and call these; the review screen itself is each import's own page. Nothing is saved before
 * ImportTarget.importRows. Never log file contents.
 *
 * Request parameters of the mapping steps: "column.<field key>" (a column index, "" for none) and "value.<n>"
 * (a canonical value, "" to decide per row). A parameter that is not sent keeps the current choice.
 */
@Service
public class ImportSteps {

    private static final Logger log = LoggerFactory.getLogger(ImportSteps.class);

    public static final String UPLOAD_VIEW = "importfile/upload";

    private final ImportMappingService mappingService;
    private final MessageSource messageSource;

    public ImportSteps(ImportMappingService mappingService, MessageSource messageSource) {
        this.mappingService = mappingService;
        this.messageSource = messageSource;
    }

    /** A distinct value of a matched field that is not exact (P24, "C/E"), asked about once for every row with it. */
    public record ValueQuestion(int index, ImportField field, String value, int rows, String chosen, boolean saved,
                                boolean suggested) {
    }

    // ---- Step 1: upload ------------------------------------------------------------------------------------------

    public String uploadPage(ImportFlow flow, Model model) {
        model.addAttribute("flow", flow);
        model.addAttribute("step", 1);
        return UPLOAD_VIEW;
    }

    /** Reads the file into a new upload on the form, then goes to the sheet choice, the mapping or the review. */
    public <F extends ImportForm<?>> String upload(ImportFlow flow, ImportTarget<F> target, F form,
                                                   MultipartFile file, Model model) {
        List<ImportSheet> sheets;
        try (InputStream in = file.getInputStream()) {
            sheets = TableFileReader.read(file.getOriginalFilename(), in);
        } catch (IOException e) {
            // The reason only, never file contents.
            log.info("Import file {} could not be read: {}", file.getOriginalFilename(), e.getMessage());
            model.addAttribute("unreadable", true);
            return uploadPage(flow, model);
        }
        if (sheets.stream().allMatch(sheet -> sheet.rows().size() < 2)) {
            model.addAttribute("noRows", true);
            return uploadPage(flow, model);
        }
        ImportUpload upload = new ImportUpload(file.getOriginalFilename(), sheets);
        form.setUpload(upload);
        chooseSheet(target, upload, bestSheet(sheets, target.fields()));
        // A sheet made from the template needs no choice and no mapping (the template has an Instructions sheet).
        if (upload.isColumnsConfirmed()) {
            return afterColumns(flow, target, form);
        }
        return redirect(flow, upload.isWorkbookWithSheets() ? "/sheet" : "/map");
    }

    public String sheetPage(ImportFlow flow, ImportForm<?> form, Model model) {
        if (form.getUpload() == null) {
            return redirect(flow, "");
        }
        model.addAttribute("flow", flow);
        model.addAttribute("step", 1);
        model.addAttribute("upload", form.getUpload());
        return "importfile/sheet";
    }

    public <F extends ImportForm<?>> String sheet(ImportFlow flow, ImportTarget<F> target, F form, int sheet) {
        ImportUpload upload = form.getUpload();
        if (upload == null) {
            return redirect(flow, "");
        }
        form.clearRows();
        chooseSheet(target, upload, sheet);
        return upload.isColumnsConfirmed() ? afterColumns(flow, target, form) : redirect(flow, "/map");
    }

    // ---- Step 2: map columns and values ----------------------------------------------------------------------------

    public String mapPage(ImportFlow flow, ImportTarget<?> target, ImportForm<?> form, Model model) {
        if (form.getUpload() == null) {
            return redirect(flow, "");
        }
        return showMap(flow, target, form.getUpload(), Map.of(), model);
    }

    /** Another row holds the column names: guesses the mapping again for it. */
    public <F extends ImportForm<?>> String header(ImportFlow flow, ImportTarget<F> target, F form, int header) {
        ImportUpload upload = form.getUpload();
        if (upload == null) {
            return redirect(flow, "");
        }
        form.clearRows();
        upload.choose(upload.getSheetIndex(), header);
        guessColumns(target, upload);
        return redirect(flow, "/map");
    }

    /**
     * Applies the chosen columns. A required field without a column, or a column chosen for two fields, keeps the
     * user on the mapping step with the reason next to the field.
     */
    public <F extends ImportForm<?>> String map(ImportFlow flow, ImportTarget<F> target, F form,
                                                Map<String, String> params, Model model) {
        ImportUpload upload = form.getUpload();
        if (upload == null) {
            return redirect(flow, "");
        }
        form.clearRows();
        int width = upload.sheet().width();
        for (ImportField field : target.fields()) {
            List<String> keys = field.isTwoColumns() ? List.of(field.key(), field.secondKey()) : List.of(field.key());
            for (String key : keys) {
                String value = params.get("column." + key);
                if (value == null) {
                    continue;
                }
                Integer column = parse(value);
                if (column == null || column < 0 || column >= width) {
                    upload.getColumns().remove(key);
                } else {
                    upload.getColumns().put(key, column);
                }
            }
        }
        upload.setColumnsConfirmed(false);

        Map<String, String> errors = new LinkedHashMap<>();
        Map<Integer, String> usedBy = new HashMap<>();
        for (ImportField field : target.fields()) {
            List<String> keys = field.isTwoColumns() ? List.of(field.key(), field.secondKey()) : List.of(field.key());
            for (String key : keys) {
                Integer column = upload.column(key);
                if (column != null && usedBy.putIfAbsent(column, field.labelKey()) != null) {
                    errors.put(key, "importFile.map.error.columnTwice");
                }
            }
            if (field.required() && upload.column(field.key()) == null) {
                errors.put(field.key(), "importFile.map.error.required");
            }
        }
        if (!errors.isEmpty()) {
            return showMap(flow, target, upload, errors, model);
        }
        upload.setColumnsConfirmed(true);
        return afterColumns(flow, target, form);
    }

    public <F extends ImportForm<?>> String valuesPage(ImportFlow flow, ImportTarget<F> target, F form, Model model) {
        ImportUpload upload = form.getUpload();
        if (upload == null) {
            return redirect(flow, "");
        }
        if (!upload.isColumnsConfirmed()) {
            return redirect(flow, "/map");
        }
        List<ValueQuestion> questions = questions(upload, target.fields());
        if (questions.isEmpty()) {
            target.buildRows(form);
            return redirect(flow, "/review");
        }
        model.addAttribute("flow", flow);
        model.addAttribute("step", 2);
        model.addAttribute("upload", upload);
        model.addAttribute("questions", questions);
        return "importfile/values";
    }

    /** Applies the chosen values to every row with them, then builds the review rows. */
    public <F extends ImportForm<?>> String values(ImportFlow flow, ImportTarget<F> target, F form,
                                                   Map<String, String> params) {
        ImportUpload upload = form.getUpload();
        if (upload == null) {
            return redirect(flow, "");
        }
        if (!upload.isColumnsConfirmed()) {
            return redirect(flow, "/map");
        }
        for (ValueQuestion question : questions(upload, target.fields())) {
            String chosen = params.getOrDefault("value." + question.index(), question.chosen());
            boolean known = question.field().values().choices().stream()
                    .flatMap(group -> group.choices().stream()).anyMatch(choice -> choice.value().equals(chosen));
            upload.getValues().computeIfAbsent(question.field().key(), key -> new HashMap<>())
                    .put(ImportNames.compact(question.value()), known ? chosen : "");
        }
        target.buildRows(form);
        return redirect(flow, "/review");
    }

    // ---- Step 3: review -------------------------------------------------------------------------------------------

    /** The shared part of the review page's model; null when there is nothing to review (the redirect to use). */
    public String reviewRedirect(ImportFlow flow, ImportForm<?> form) {
        if (form.getUpload() == null) {
            return redirect(flow, "");
        }
        if (!form.isBuilt()) {
            return redirect(flow, "/map");
        }
        return null;
    }

    public void reviewModel(ImportFlow flow, ImportForm<?> form, String show, Model model) {
        model.addAttribute("flow", flow);
        model.addAttribute("step", 3);
        model.addAttribute("upload", form.getUpload());
        model.addAttribute("show", ImportForm.show(show));
    }

    /** "Check again" and the row filters: the edited values are in the session; the review page validates them. */
    public String check(ImportFlow flow, ImportForm<?> form, String show) {
        String redirect = reviewRedirect(flow, form);
        return redirect != null ? redirect : redirectToReview(flow, show);
    }

    /** "Exclude all rows with errors". */
    public <F extends ImportForm<?>> String excludeErrors(ImportFlow flow, ImportTarget<F> target, F form,
                                                          String show) {
        String redirect = reviewRedirect(flow, form);
        if (redirect != null) {
            return redirect;
        }
        target.validate(form);
        form.getRows().stream().filter(row -> !row.isExcluded() && row.hasErrors())
                .forEach(row -> row.setExcluded(true));
        return redirectToReview(flow, show);
    }

    public <F extends ImportForm<?>> String submit(ImportFlow flow, ImportTarget<F> target, F form, String actorEmail,
                                                   SessionStatus sessionStatus, RedirectAttributes redirectAttributes) {
        String redirect = reviewRedirect(flow, form);
        if (redirect != null) {
            return redirect;
        }
        target.validate(form);
        if (!form.isReady()) {
            // The button is disabled then; this happens only if values were changed without "Check again".
            return redirect(flow, "/review");
        }
        ImportResult result = target.importRows(form, actorEmail);
        sessionStatus.setComplete();
        redirectAttributes.addFlashAttribute("importResult", result);
        redirectAttributes.addFlashAttribute("successMessage", flow.key("done.flash"));
        redirectAttributes.addFlashAttribute("successArgs",
                new Object[] {result.created(), result.updated(), result.skipped()});
        return redirect(flow, "/done");
    }

    // ---- Step 4: done, and Cancel ---------------------------------------------------------------------------------

    /** Shows the counts once, right after the import (flash attribute "importResult"). */
    public String donePage(ImportFlow flow, Model model) {
        if (!(model.getAttribute("importResult") instanceof ImportResult)) {
            return "redirect:" + flow.listPath();
        }
        model.addAttribute("flow", flow);
        model.addAttribute("step", 4);
        return "importfile/done";
    }

    /** Forgets the upload; nothing was saved. */
    public String cancel(ImportFlow flow, SessionStatus sessionStatus, RedirectAttributes redirectAttributes) {
        sessionStatus.setComplete();
        redirectAttributes.addFlashAttribute("successMessage", "importFile.cancelled");
        return "redirect:" + flow.listPath();
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    private String showMap(ImportFlow flow, ImportTarget<?> target, ImportUpload upload, Map<String, String> errors,
                           Model model) {
        model.addAttribute("flow", flow);
        model.addAttribute("step", 2);
        model.addAttribute("upload", upload);
        model.addAttribute("map", ImportMapView.of(upload, target.fields(), errors, messageSource,
                LocaleContextHolder.getLocale()));
        return "importfile/map";
    }

    // After the columns are confirmed: the value questions, if any, otherwise the review.
    private <F extends ImportForm<?>> String afterColumns(ImportFlow flow, ImportTarget<F> target, F form) {
        if (!questions(form.getUpload(), target.fields()).isEmpty()) {
            return redirect(flow, "/values");
        }
        target.buildRows(form);
        return redirect(flow, "/review");
    }

    private void chooseSheet(ImportTarget<?> target, ImportUpload upload, int sheet) {
        upload.choose(sheet, 0);
        upload.choose(sheet, ColumnGuesser.headerIndex(upload.sheet().rows(), target.fields()));
        guessColumns(target, upload);
        // The easy path: a file made from the template needs no mapping.
        List<String> header = new ArrayList<>(upload.header());
        while (!header.isEmpty() && header.getLast().isEmpty()) {
            header.removeLast();
        }
        if (upload.getHeaderIndex() == 0 && header.size() == target.templateHeader().size()
                && header.stream().map(ImportNames::normalize).toList()
                .equals(target.templateHeader().stream().map(ImportNames::normalize).toList())) {
            upload.setTemplate(true);
            upload.setColumnsConfirmed(true);
        }
    }

    // The saved mapping for these column names if there is one, otherwise the best guess.
    private void guessColumns(ImportTarget<?> target, ImportUpload upload) {
        if (!mappingService.applySaved(target.kind(), upload)) {
            upload.getColumns().putAll(ColumnGuesser.guess(upload.header(), target.fields()));
        }
    }

    // The sheet where the most fields find a column; the first one on a tie.
    private static int bestSheet(List<ImportSheet> sheets, List<ImportField> fields) {
        int best = 0;
        int bestFound = -1;
        for (int i = 0; i < sheets.size(); i++) {
            List<ImportSheet.Row> rows = sheets.get(i).rows();
            if (rows.isEmpty()) {
                continue;
            }
            int header = ColumnGuesser.headerIndex(rows, fields);
            int found = ColumnGuesser.guess(rows.get(header).cells(), fields).size();
            if (found > bestFound) {
                best = i;
                bestFound = found;
            }
        }
        return best;
    }

    /** The distinct values of the matched fields that are not exact, in the order they first appear. */
    public static List<ValueQuestion> questions(ImportUpload upload, List<ImportField> fields) {
        List<ValueQuestion> questions = new ArrayList<>();
        for (ImportField field : fields) {
            if (field.values() == null || upload.column(field.key()) == null) {
                continue;
            }
            Map<String, String> firstSpelling = new LinkedHashMap<>();
            Map<String, Integer> counts = new HashMap<>();
            for (ImportSheet.Row row : upload.dataRows()) {
                String raw = upload.value(row, field);
                if (raw.isEmpty() || field.values().exact(raw) != null) {
                    continue;
                }
                String key = ImportNames.compact(raw);
                firstSpelling.putIfAbsent(key, raw);
                counts.merge(key, 1, Integer::sum);
            }
            Map<String, String> chosen = upload.getValues().getOrDefault(field.key(), Map.of());
            firstSpelling.forEach((key, raw) -> {
                String suggestion = field.values().suggest(raw);
                boolean saved = chosen.containsKey(key);
                String value = saved ? chosen.get(key) : Objects.requireNonNullElse(suggestion, "");
                questions.add(new ValueQuestion(questions.size(), field, raw, counts.get(key), value,
                        saved && upload.getSavedMappingAt() != null, !saved && suggestion != null));
            });
        }
        return questions;
    }

    private static String redirectToReview(ImportFlow flow, String show) {
        String filter = ImportForm.show(show);
        return redirect(flow, filter == null ? "/review" : "/review?show=" + filter);
    }

    private static String redirect(ImportFlow flow, String step) {
        return "redirect:" + flow.path(step);
    }

    private static Integer parse(String value) {
        try {
            return value.isBlank() ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
