package com.nereonix.nereonet_mvp.vetting;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import com.nereonix.nereonet_mvp.core.certificate.CertificateFileStorage;
import com.nereonix.nereonet_mvp.core.form.PlausibleDate;
import com.nereonix.nereonet_mvp.core.library.LibraryItemForm;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * "Upload report" (P40): an official RightShip document of the vessel, a PDF, PNG or JPEG of at most 10 MB (the
 * library's limit; the multipart limits in application.yml match it), and the date on it. The type is checked by the
 * extension and the file's first bytes (the certificate scans' check, P39), so a renamed file of another kind is refused.
 */
public class RightShipReportForm {

    public static final long MAX_FILE_SIZE = LibraryItemForm.MAX_FILE_SIZE;

    private MultipartFile file;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @NotNull(message = "{vetting.report.error.dateRequired}")
    @PlausibleDate(yearsBefore = 20, yearsAfter = 0)
    private LocalDate documentDate;

    @AssertTrue(message = "{vetting.report.error.fileRequired}")
    public boolean isFilePresent() {
        return hasFile();
    }

    @AssertTrue(message = "{vetting.report.error.fileType}")
    public boolean isFileTypeAllowed() {
        return !hasFile() || contentType() != null;
    }

    @AssertTrue(message = "{vetting.report.error.fileTooLarge}")
    public boolean isFileSizeAllowed() {
        return !hasFile() || file.getSize() <= MAX_FILE_SIZE;
    }

    // For the file input (data-max-size), which warns before the upload.
    public long getMaxFileSize() {
        return MAX_FILE_SIZE;
    }

    public boolean hasFile() {
        return file != null && !file.isEmpty();
    }

    /** application/pdf, image/png or image/jpeg; null for anything else. */
    String contentType() {
        return CertificateFileStorage.contentTypeOf(file);
    }

    public MultipartFile getFile() { return file; }
    public void setFile(MultipartFile file) { this.file = file; }

    public LocalDate getDocumentDate() { return documentDate; }
    public void setDocumentDate(LocalDate documentDate) { this.documentDate = documentDate; }
}
