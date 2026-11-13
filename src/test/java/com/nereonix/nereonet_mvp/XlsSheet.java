package com.nereonix.nereonet_mvp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFName;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;

/**
 * The one sheet of an exported workbook (P39; Excel 97-2003, .xls, since P40), read back with POI's HSSFWorkbook for
 * the export tests: its name, and each row's cells as values: text as a String, a number as a Double, a date cell as
 * a LocalDate (date only) or LocalDateTime (with a time), an empty cell as null. of(...) also checks the export's
 * form: an .xls file with one sheet, a bold header row that is frozen and has the autofilter, dates shown
 * "dd mmm yyyy", every column wide enough for its header.
 */
public record XlsSheet(String name, List<List<Object>> rows) {

    public static XlsSheet of(byte[] xls) throws IOException {
        // HSSFWorkbook reads only the Excel 97-2003 format: an .xlsx file fails here.
        try (HSSFWorkbook workbook = new HSSFWorkbook(new ByteArrayInputStream(xls))) {
            assertThat(workbook.getNumberOfSheets()).as("sheets").isEqualTo(1);
            HSSFSheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(sheet.getPaneInformation()).as("frozen header").isNotNull();
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
            // The autofilter is the sheet's built-in name _FilterDatabase over the header row.
            assertThat(workbook.getAllNames()).as("autofilter")
                    .anySatisfy(name -> assertAutoFilter((HSSFName) name, sheet.getSheetName()));
            List<List<Object>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < header.getLastCellNum(); i++) {
                    Cell cell = row.getCell(i);
                    if (row.getRowNum() == 0) {
                        assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndex()).getBold())
                                .as("bold header " + cell.getStringCellValue()).isTrue();
                        assertThat(sheet.getColumnWidth(i)).as("width of " + cell.getStringCellValue())
                                .isGreaterThanOrEqualTo(cell.getStringCellValue().length() * 256);
                    }
                    values.add(value(cell));
                }
                rows.add(values);
            }
            return new XlsSheet(sheet.getSheetName(), rows);
        }
    }

    private static void assertAutoFilter(HSSFName name, String sheetName) {
        assertThat(name.getNameName()).isEqualTo("_FilterDatabase");
        // "Orders!$A$1:$L$3", or quoted when the name has a space: "'Certificate matrix'!$A$1:$E$5".
        assertThat(name.getRefersToFormula().replace("'", "")).as("from A1").startsWith(sheetName + "!$A$1:");
    }

    public List<Object> header() {
        return rows.getFirst();
    }

    /** The rows after the header. */
    public List<List<Object>> body() {
        return rows.subList(1, rows.size());
    }

    private static Object value(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        }
        if (DateUtil.isCellDateFormatted(cell)) {
            String format = cell.getCellStyle().getDataFormatString();
            LocalDateTime value = cell.getLocalDateTimeCellValue();
            if (format.equals("dd mmm yyyy")) {
                return value.toLocalDate();
            }
            assertThat(format).isEqualTo("dd mmm yyyy hh:mm");
            return value;
        }
        return cell.getNumericCellValue();
    }
}
