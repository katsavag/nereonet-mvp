package com.nereonix.nereonet_mvp.certificate;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateFileStorage;
import com.nereonix.nereonet_mvp.core.form.PlausibleDate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The add and edit certificate form. P39: an optional file, the scan of the certificate (PDF, JPEG or PNG, at most
// 10 MB); on edit a new file replaces the current one, and removeFile removes it. The multipart limits in
// application.yml match CertificateFileStorage.MAX_FILE_SIZE.
public class CertificateForm {

    /** The P39 limits of Expires on, either way from today; a later date does not count for required documents (P42). */
    public static final int EXPIRES_YEARS = 30;

    @NotBlank(message = "{certificate.error.typeRequired}")
    @Size(max = Certificate.TYPE_MAX_LENGTH, message = "{certificate.error.tooLong}")
    private String type;

    @Size(max = Certificate.NUMBER_MAX_LENGTH, message = "{certificate.error.tooLong}")
    private String number;

    @NotNull(message = "{certificate.error.expiresOnRequired}")
    @PlausibleDate(yearsBefore = EXPIRES_YEARS, yearsAfter = EXPIRES_YEARS)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expiresOn;

    private MultipartFile file;

    private boolean removeFile;

    @AssertTrue(message = "{certificate.error.fileType}")
    public boolean isFileTypeAllowed() {
        return !hasFile() || CertificateFileStorage.contentTypeOf(file) != null;
    }

    @AssertTrue(message = "{certificate.error.fileTooLarge}")
    public boolean isFileSizeAllowed() {
        return !hasFile() || file.getSize() <= CertificateFileStorage.MAX_FILE_SIZE;
    }

    // For the file input (data-max-size), which warns before the upload.
    public long getMaxFileSize() {
        return CertificateFileStorage.MAX_FILE_SIZE;
    }

    public boolean hasFile() {
        return file != null && !file.isEmpty();
    }

    static CertificateForm of(Certificate certificate) {
        CertificateForm form = new CertificateForm();
        form.setType(certificate.getType());
        form.setNumber(certificate.getNumber());
        form.setExpiresOn(certificate.getExpiresOn());
        return form;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type == null ? null : type.strip(); }

    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number == null ? null : number.strip(); }

    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }

    public MultipartFile getFile() { return file; }
    public void setFile(MultipartFile file) { this.file = file; }

    public boolean isRemoveFile() { return removeFile; }
    public void setRemoveFile(boolean removeFile) { this.removeFile = removeFile; }
}
