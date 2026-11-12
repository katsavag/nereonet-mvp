package com.nereonix.nereonet_mvp.item;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.importfile.ImportAction;
import com.nereonix.nereonet_mvp.core.importfile.ImportField;
import com.nereonix.nereonet_mvp.core.importfile.ImportMappingService;
import com.nereonix.nereonet_mvp.core.importfile.ImportResult;
import com.nereonix.nereonet_mvp.core.importfile.ImportSheet;
import com.nereonix.nereonet_mvp.core.importfile.ImportTarget;
import com.nereonix.nereonet_mvp.core.importfile.ImportUpload;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;

// Item list import for one ship (P24): map the columns of any item file (the template, or a purchasing system's
// export), validate every row, then save. A name the ship does not have is a new item; a name it has (ignoring case)
// updates that item's unit, category and place, or is skipped when they are the same. The category must be
// "Provisions" or "Personal items" (ignoring case); the place is kept for provisions only. Nothing is saved before
// importRows.
// Never log file contents.
@Service
public class ItemImportService implements ImportTarget<ItemImportForm> {

    // The template's columns. The file format, so never translated.
    static final List<String> HEADERS = List.of("Name", "Unit", "Category", "Place");

    static final String NAME = "name";
    static final String UNIT = "unit";
    static final String CATEGORY = "category";
    static final String PLACE = "place";

    // Column names as purchasing systems and spreadsheets write them, normalized (ImportNames.normalize), most likely
    // first.
    private static final List<ImportField> FIELDS = List.of(
            ImportField.of(NAME, "item.name", true,
                    List.of("name", "item name", "item", "description", "item description", "product",
                            "product name", "article", "material", "material description", "περιγραφη", "ειδος",
                            "ονομασια")),
            ImportField.of(UNIT, "item.unit", true,
                    List.of("unit", "uom", "unit of measure", "unit of measurement", "units", "measure", "μοναδα",
                            "μοναδα μετρησης", "μ μ")),
            ImportField.of(CATEGORY, "item.category", true,
                    List.of("category", "item category", "group", "item group", "product group", "type", "class",
                            "κατηγορια", "ομαδα")),
            ImportField.of(PLACE, "item.place", false,
                    List.of("place", "storage place", "storage", "store", "storage location", "location", "stowage",
                            "θεση", "αποθηκη", "χωρος αποθηκευσης")));

    private final ItemRepository itemRepository;
    private final ItemService itemService;
    private final AuditService auditService;
    private final ImportMappingService mappingService;
    private final MessageSource messageSource;

    public ItemImportService(ItemRepository itemRepository, ItemService itemService, AuditService auditService,
                             ImportMappingService mappingService, MessageSource messageSource) {
        this.itemRepository = itemRepository;
        this.itemService = itemService;
        this.auditService = auditService;
        this.mappingService = mappingService;
        this.messageSource = messageSource;
    }

    @Override
    public String kind() {
        return "item";
    }

    @Override
    public List<ImportField> fields() {
        return FIELDS;
    }

    @Override
    public List<String> templateHeader() {
        return HEADERS;
    }

    /** The blank template: sheet "Items" with the header row, and sheet "Instructions" in the given language. */
    public byte[] template(Locale locale) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            CellStyle bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            Sheet sheet = workbook.createSheet("Items");
            addRow(sheet, HEADERS, bold);
            int[] widths = {32, 10, 18, 20};
            for (int i = 0; i < HEADERS.size(); i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }

            Sheet help = workbook.createSheet("Instructions");
            help.setColumnWidth(0, 20 * 256);
            help.setColumnWidth(1, 90 * 256);
            addRow(help, List.of(text("itemImport.template.title", locale)), bold);
            addRow(help, List.of(text("itemImport.template.intro", locale)), null);
            addRow(help, List.of(), null);
            addRow(help, List.of(text("itemImport.template.column", locale),
                    text("itemImport.template.meaning", locale)), bold);
            for (String column : HEADERS) {
                addRow(help, List.of(column, text("itemImport.template." + column.toLowerCase(Locale.ROOT), locale)),
                        null);
            }
            addRow(help, List.of(), null);
            // The two values of the Category column, with what each holds.
            addRow(help, List.of(text("itemImport.template.categories", locale)), bold);
            addRow(help, List.of(text("itemImport.template.value", locale),
                    text("itemImport.template.meaning", locale)), bold);
            for (ItemCategory category : ItemCategory.values()) {
                addRow(help, List.of(category.fileValue(), text(category.labelKey() + ".hint", locale)), null);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Override
    public void buildRows(ItemImportForm form) {
        ImportUpload upload = form.getUpload();
        List<ItemImportRow> rows = new ArrayList<>();
        for (ImportSheet.Row fileRow : upload.dataRows()) {
            ItemImportRow row = new ItemImportRow();
            row.setRowNumber(fileRow.number());
            row.setName(upload.value(fileRow, field(NAME)));
            row.setUnit(upload.value(fileRow, field(UNIT)));
            row.setCategory(upload.value(fileRow, field(CATEGORY)));
            row.setPlace(upload.value(fileRow, field(PLACE)));
            rows.add(row);
        }
        form.rowsBuilt(rows);
    }

    /** Sets each included row's action (new, update, no change) and the errors of the rows that change something. */
    @Override
    @Transactional(readOnly = true)
    public void validate(ItemImportForm form) {
        Map<String, Item> existing = existingItems(form.getShipId());
        for (ItemImportRow row : form.getRows()) {
            row.getErrors().clear();
            Item item = row.getName().isEmpty() ? null : existing.get(key(row.getName()));
            if (item == null) {
                row.setAction(ImportAction.CREATE);
            } else if (item.getUnit().equals(row.getUnit())
                    && item.getCategory() == ItemCategory.fromFileValue(row.getCategory())
                    && Objects.equals(item.getPlace(), place(row))) {
                row.setAction(ImportAction.UNCHANGED);
            } else {
                row.setAction(ImportAction.UPDATE);
            }
        }
        List<ItemImportRow> changing = changingRows(form);
        Map<String, Long> nameCounts = changing.stream()
                .collect(Collectors.groupingBy(row -> key(row.getName()), Collectors.counting()));

        for (ItemImportRow row : changing) {
            Map<String, String> errors = row.getErrors();
            if (row.getName().isEmpty()) {
                errors.put("name", "item.error.nameRequired");
            } else if (row.getName().length() > Item.NAME_MAX_LENGTH) {
                errors.put("name", "item.error.tooLong");
            } else if (nameCounts.get(key(row.getName())) > 1) {
                errors.put("name", "itemImport.error.nameRepeated");
            }
            if (row.getUnit().isEmpty()) {
                errors.put("unit", "item.error.unitRequired");
            } else if (row.getUnit().length() > Item.UNIT_MAX_LENGTH) {
                errors.put("unit", "item.error.tooLong");
            }
            if (row.getCategory().isEmpty()) {
                errors.put("category", "item.error.categoryRequired");
            } else if (ItemCategory.fromFileValue(row.getCategory()) == null) {
                errors.put("category", "itemImport.error.categoryInvalid");
            }
            if (place(row) != null && place(row).length() > Item.PLACE_MAX_LENGTH) {
                errors.put("place", "item.error.tooLong");
            }
        }
    }

    /**
     * Validates again, saves the new items and the updates, and saves the confirmed mapping (not for a file made from
     * the template). Rows that change nothing are skipped. Throws IllegalStateException if a row has errors: the
     * review screen only allows this when there are none.
     */
    @Override
    @Transactional
    public ImportResult importRows(ItemImportForm form, String actorEmail) {
        validate(form);
        if (!form.isReady()) {
            throw new IllegalStateException("The item import has errors or nothing to add or update");
        }
        Ship ship = itemService.getShip(form.getShipId());
        Map<String, Item> existing = existingItems(form.getShipId());
        int created = 0;
        int updated = 0;
        for (ItemImportRow row : changingRows(form)) {
            if (row.getAction() == ImportAction.CREATE) {
                itemRepository.save(ItemService.newItem(ship, row.getName(), row.getUnit(),
                        ItemCategory.fromFileValue(row.getCategory()), row.getPlace()));
                created++;
            } else {
                // The name stays as the ship has it; only the unit, the category and the place change.
                Item item = existing.get(key(row.getName()));
                item.setUnit(row.getUnit());
                item.setCategory(ItemCategory.fromFileValue(row.getCategory()));
                item.setPlace(place(row));
                updated++;
            }
        }
        int skipped = form.getRows().size() - created - updated;
        if (!form.getUpload().isTemplate()) {
            mappingService.save(kind(), form.getUpload());
        }
        // Counts only, never file contents.
        auditService.record("ITEMS_IMPORTED", ship, created + " added, " + updated + " updated, "
                + form.getUnchangedCount() + " unchanged, " + form.getExcludedCount() + " excluded, of "
                + form.getRows().size() + " rows");
        return new ImportResult(created, updated, skipped);
    }

    private Map<String, Item> existingItems(Long shipId) {
        return itemRepository.findAllByShipIdOrderByNameAsc(shipId).stream()
                .collect(Collectors.toMap(item -> key(item.getName()), Function.identity(), (a, b) -> a));
    }

    private static List<ItemImportRow> changingRows(ItemImportForm form) {
        return form.getRows().stream()
                .filter(row -> !row.isExcluded() && row.getAction() != ImportAction.UNCHANGED).toList();
    }

    private static ImportField field(String key) {
        return FIELDS.stream().filter(field -> field.key().equals(key)).findFirst().orElseThrow();
    }

    // The row's place as the item would store it: null when empty or when the row is not a provisions item.
    private static String place(ItemImportRow row) {
        return ItemService.place(ItemCategory.fromFileValue(row.getCategory()), row.getPlace());
    }

    // Adds a row after the last one. style may be null.
    private static void addRow(Sheet sheet, List<String> values, CellStyle style) {
        Row row = sheet.createRow(sheet.getPhysicalNumberOfRows());
        for (int i = 0; i < values.size(); i++) {
            row.createCell(i).setCellValue(values.get(i));
            if (style != null) {
                row.getCell(i).setCellStyle(style);
            }
        }
    }

    private String text(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }

    // Names are unique per ship, ignoring case.
    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
