package com.nereonix.nereonet_mvp.vetting;

import org.springframework.web.multipart.MultipartFile;

import com.nereonix.nereonet_mvp.core.certificate.CertificateFileStorage;
import com.nereonix.nereonet_mvp.core.library.LibraryItemForm;

import jakarta.validation.constraints.AssertTrue;

/**
 * "Upload report" (P41): the SIRE 2.0 report of an inspection, a PDF of at most 10 MB (the library's limit). The type
 * is checked by the extension and the file's first bytes (P39), so a renamed file of another kind is refused.
 */
public class SireReportForm {

    public static final long MAX_FILE_SIZE = LibraryItemForm.MAX_FILE_SIZE;

    private MultipartFile file;

    @AssertTrue(message = "{vetting.report.error.fileRequired}")
    public boolean isFilePresent() {
        return hasFile();
    }

    @AssertTrue(message = "{sire.report.error.fileType}")
    public boolean isFileTypeAllowed() {
        return !hasFile() || "application/pdf".equals(CertificateFileStorage.contentTypeOf(file));
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

    public MultipartFile getFile() { return file; }
    public void setFile(MultipartFile file) { this.file = file; }
}
