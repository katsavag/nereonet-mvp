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
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;

// Item list import for one ship (P24): map the columns of any item file (the template, or a purchasing system's
// export), validate every row, then save. A name the ship does not have is a new item; a name it has (ignoring case)
// updates that item's unit and category, or is skipped when they are the same. Nothing is saved before importRows.
// Never log file contents.
@Service
public class ItemImportService implements ImportTarget<ItemImportForm> {

    // The template's columns. The file format, so never translated.
    static final List<String> HEADERS = List.of("Name", "Unit", "Category");

    static final String NAME = "name";
    static final String UNIT = "unit";
    static final String CATEGORY = "category";

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
            ImportField.of(CATEGORY, "item.category", false,
                    List.of("category", "item category", "group", "item group", "product group", "type", "class",
                            "κατηγορια", "ομαδα")));

    private final ItemRepository itemRepository;
    private final ItemService itemService;
    private final AuditService auditService;
    private final ImportMappingService mappingService;

    public ItemImportService(ItemRepository itemRepository, ItemService itemService, AuditService auditService,
                             ImportMappingService mappingService) {
        this.itemRepository = itemRepository;
        this.itemService = itemService;
        this.auditService = auditService;
        this.mappingService = mappingService;
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

    /** The blank template: sheet "Items" with the header row. */
    public byte[] template() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            CellStyle bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            Sheet sheet = workbook.createSheet("Items");
            Row header = sheet.createRow(0);
            int[] widths = {32, 10, 24};
            for (int i = 0; i < HEADERS.size(); i++) {
                header.createCell(i).setCellValue(HEADERS.get(i));
                header.getCell(i).setCellStyle(bold);
                sheet.setColumnWidth(i, widths[i] * 256);
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
                    && Objects.equals(item.getCategory(), category(row.getCategory()))) {
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
            if (row.getCategory().length() > Item.CATEGORY_MAX_LENGTH) {
                errors.put("category", "item.error.tooLong");
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
                itemRepository.save(ItemService.newItem(ship, row.getName(), row.getUnit(), row.getCategory()));
                created++;
            } else {
                // The name stays as the ship has it; only the unit and the category change.
                Item item = existing.get(key(row.getName()));
                item.setUnit(row.getUnit());
                item.setCategory(category(row.getCategory()));
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

    // As the item stores it: null when empty.
    private static String category(String category) {
        return category.isBlank() ? null : category.strip();
    }

    // Names are unique per ship, ignoring case.
    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
