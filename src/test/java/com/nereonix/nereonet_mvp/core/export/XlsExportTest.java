package com.nereonix.nereonet_mvp.core.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.Test;

/**
 * P40: the office exports are .xls files (Excel 97-2003): at most 65,535 data rows and 256 columns, else the export is
 * refused ("Too many rows for one file: narrow the filters"); a text over 32,767 characters is shortened with "…".
 */
class XlsExportTest {

    @Test
    void anExportIsAnXlsWorkbookWithItsHeaderAndRows() throws Exception {
        XlsExport.File file = XlsExport.sheet("Certificates", List.of("Type", "Expires on"))
                .row(List.of("STCW Basic Safety", LocalDate.of(2026, 12, 1)))
                .file("certificates-2026-11-13");

        assertThat(file.filename()).isEqualTo("certificates-2026-11-13.xls");
        assertThat(XlsExport.CONTENT_TYPE.toString()).isEqualTo("application/vnd.ms-excel");
        assertThat(file.response().getHeaders().getContentType()).isEqualTo(XlsExport.CONTENT_TYPE);
        try (HSSFWorkbook workbook = new HSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Certificates");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Type");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("STCW Basic Safety");
        }
    }

    @Test
    void moreRowsOrColumnsThanOneSheetHoldsAreRefused() {
        assertThat(XlsExport.MAX_ROWS).isEqualTo(65_535);
        assertThat(XlsExport.MAX_COLUMNS).isEqualTo(256);

        XlsExport full = XlsExport.sheet("Rows", List.of("Number"));
        for (int i = 0; i < XlsExport.MAX_ROWS; i++) {
            full.row(List.of(i));
        }
        assertThat(full.file("rows").content()).isNotEmpty();

        full.row(List.of(XlsExport.MAX_ROWS));
        assertThatThrownBy(() -> full.file("rows")).isInstanceOf(XlsExport.TooManyRowsException.class);
        assertThat(XlsExport.TooManyRowsException.MESSAGE).isEqualTo("export.tooManyRows");

        List<String> wide = Collections.nCopies(XlsExport.MAX_COLUMNS + 1, "Column");
        assertThatThrownBy(() -> XlsExport.sheet("Wide", wide).file("wide"))
                .isInstanceOf(XlsExport.TooManyRowsException.class);
    }

    @Test
    void aTextLongerThanACellHoldsIsShortenedWithAnEllipsis() throws Exception {
        assertThat(XlsExport.MAX_TEXT_LENGTH).isEqualTo(32_767);
        String longText = "a".repeat(XlsExport.MAX_TEXT_LENGTH + 10);
        String shortened = XlsExport.text(longText);
        assertThat(shortened).hasSize(XlsExport.MAX_TEXT_LENGTH).endsWith("…");
        assertThat(XlsExport.text("short")).isEqualTo("short");
        // A character outside the BMP is never cut in half.
        String emoji = "a".repeat(XlsExport.MAX_TEXT_LENGTH - 2) + "😀" + "bbb";
        assertThat(Character.isHighSurrogate(XlsExport.text(emoji).charAt(XlsExport.text(emoji).length() - 2))).isFalse();

        XlsExport.File file = XlsExport.sheet("Notes", List.of("Note")).row(List.of(longText)).file("notes");
        try (HSSFWorkbook workbook = new HSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
                    .hasSize(XlsExport.MAX_TEXT_LENGTH).endsWith("…");
        }
    }
}
