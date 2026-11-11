package com.nereonix.nereonet_mvp.item;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.importfile.TableFile;
import com.nereonix.nereonet_mvp.core.importfile.TableFileReader;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;

// Item list import for one ship: read the uploaded template, validate every row, then save the included rows.
// Names the ship already has are skipped. Nothing is saved before importItems. Never log file contents.
@Service
public class ItemImportService {

    // The file format, so never translated. Uploaded files must have exactly these columns, in this order.
    static final List<String> HEADERS = List.of("Name", "Unit", "Category");

    private final ItemRepository itemRepository;
    private final ItemService itemService;
    private final AuditService auditService;

    public ItemImportService(ItemRepository itemRepository, ItemService itemService, AuditService auditService) {
        this.itemRepository = itemRepository;
        this.itemService = itemService;
        this.auditService = auditService;
    }

    public record Result(int added, List<String> skippedNames) {
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

    /**
     * Reads an uploaded .xlsx or .csv file for the given ship. Empty if its header row is not the template's.
     * Throws IOException if the file cannot be read.
     */
    public Optional<ItemImportForm> read(Long shipId, String filename, InputStream in) throws IOException {
        TableFile file = TableFileReader.read(filename, in);
        if (!file.hasHeader(HEADERS)) {
            return Optional.empty();
        }
        ItemImportForm form = new ItemImportForm();
        form.setShipId(shipId);
        for (TableFile.Row fileRow : file.rows()) {
            ItemImportRow row = new ItemImportRow();
            row.setRowNumber(fileRow.number());
            row.setName(fileRow.cell(0));
            row.setUnit(fileRow.cell(1));
            row.setCategory(fileRow.cell(2));
            form.getRows().add(row);
        }
        return Optional.of(form);
    }

    /** Marks the rows whose name the ship already has, and sets the errors of the other included rows. */
    @Transactional(readOnly = true)
    public void validate(ItemImportForm form) {
        Set<String> existingNames = itemRepository.findAllByShipIdOrderByNameAsc(form.getShipId()).stream()
                .map(item -> key(item.getName()))
                .collect(Collectors.toSet());
        for (ItemImportRow row : form.getRows()) {
            row.getErrors().clear();
            row.setExisting(!row.getName().isEmpty() && existingNames.contains(key(row.getName())));
        }
        Map<String, Long> nameCounts = form.getRowsToAdd().stream()
                .collect(Collectors.groupingBy(row -> key(row.getName()), Collectors.counting()));

        for (ItemImportRow row : form.getRowsToAdd()) {
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
     * Validates again and saves the rows to add; rows whose name the ship already has are skipped.
     * Throws IllegalStateException if a row has errors: the review screen only allows this when there are none.
     */
    @Transactional
    public Result importItems(ItemImportForm form) {
        validate(form);
        if (!form.isReady()) {
            throw new IllegalStateException("The item import has errors or no rows to add");
        }
        Ship ship = itemService.getShip(form.getShipId());
        List<ItemImportRow> toAdd = form.getRowsToAdd();
        for (ItemImportRow row : toAdd) {
            itemRepository.save(ItemService.newItem(ship, row.getName(), row.getUnit(), row.getCategory()));
        }
        List<String> skippedNames = form.getSkippedRows().stream().map(ItemImportRow::getName).toList();
        // Counts only, never file contents.
        auditService.record("ITEMS_IMPORTED", ship, toAdd.size() + " added, " + skippedNames.size()
                + " skipped (already on the ship), " + form.getExcludedCount() + " excluded, of "
                + form.getRows().size() + " rows");
        return new Result(toAdd.size(), skippedNames);
    }

    // Names are unique per ship, ignoring case.
    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
