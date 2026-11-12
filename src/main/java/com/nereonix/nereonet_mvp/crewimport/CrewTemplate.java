package com.nereonix.nereonet_mvp.crewimport;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;

// The crew import template (.xlsx): sheet "Crew" with the header row and the given rows,
// and sheet "Instructions". Used for the download and for samples/crew-sample.xlsx.
public final class CrewTemplate {

    // The file format, so never translated. A file with exactly these columns, in this order, needs no column mapping
    // (P24); any other file is mapped on the import's Map columns step. There is no Role column: the rank sets the
    // role (P18); a Role column in a file is ignored.
    public static final List<String> HEADERS = List.of("Full name", "Email", "Rank", "Ship IMO");
    private static final int[] COLUMN_WIDTHS = {28, 34, 24, 12};

    private CrewTemplate() {
    }

    /** rows: one list of cell values per crew member, in HEADERS order; empty for the blank template. */
    public static void write(OutputStream out, List<List<String>> rows, List<Ship> activeShips,
                             MessageSource messages, Locale locale) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            CellStyle bold = workbook.createCellStyle();
            bold.setFont(boldFont);

            Sheet crew = workbook.createSheet("Crew");
            addRow(crew, HEADERS, bold);
            for (List<String> row : rows) {
                addRow(crew, row, null);
            }
            for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
                crew.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
            }

            Sheet help = workbook.createSheet("Instructions");
            help.setColumnWidth(0, 30 * 256);
            help.setColumnWidth(1, 90 * 256);
            help.setColumnWidth(2, 24 * 256);
            addRow(help, List.of(text(messages, "crewImport.template.title", locale)), bold);
            addRow(help, List.of(text(messages, "crewImport.template.intro", locale)), null);
            addRow(help, List.of(), null);

            addRow(help, List.of(text(messages, "crewImport.template.column", locale),
                    text(messages, "crewImport.template.meaning", locale)), bold);
            String[] keys = {"fullName", "email", "rank", "shipImo"};
            for (int i = 0; i < HEADERS.size(); i++) {
                addRow(help, List.of(HEADERS.get(i), text(messages, "crewImport.template." + keys[i], locale)), null);
            }
            addRow(help, List.of(), null);

            // Each rank with the access it sets (P18).
            addRow(help, List.of(text(messages, "crewImport.template.ranks", locale)), bold);
            addRow(help, List.of(text(messages, "crewImport.template.accessHint", locale)), null);
            addRow(help, List.of(text(messages, "crewImport.template.value", locale), text(messages, "user.rank", locale),
                    text(messages, "crewImport.template.access", locale)), bold);
            for (Rank rank : Rank.values()) {
                addRow(help, List.of(rank.name(), text(messages, "rank." + rank, locale),
                        text(messages, "role." + rank.role(), locale)), null);
            }
            addRow(help, List.of(), null);

            addRow(help, List.of(text(messages, "crewImport.template.ships", locale)), bold);
            addRow(help, List.of(text(messages, "crewImport.template.imo", locale),
                    text(messages, "crewImport.template.shipName", locale)), bold);
            for (Ship ship : activeShips) {
                addRow(help, List.of(ship.getImoNumber(), ship.getName()), null);
            }

            workbook.write(out);
        }
    }

    // Adds a row after the last one. style may be null.
    private static void addRow(Sheet sheet, List<String> values, CellStyle style) {
        Row row = sheet.createRow(sheet.getPhysicalNumberOfRows());
        for (int i = 0; i < values.size(); i++) {
            var cell = row.createCell(i);
            // Strings, so an IMO number is not turned into a number.
            cell.setCellValue(values.get(i));
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    private static String text(MessageSource messages, String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }
}
