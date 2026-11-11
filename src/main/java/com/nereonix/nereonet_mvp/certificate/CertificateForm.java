package com.nereonix.nereonet_mvp.certificate;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The add and edit certificate form.
public class CertificateForm {

    @NotBlank(message = "{certificate.error.typeRequired}")
    @Size(max = Certificate.TYPE_MAX_LENGTH, message = "{certificate.error.tooLong}")
    private String type;

    @Size(max = Certificate.NUMBER_MAX_LENGTH, message = "{certificate.error.tooLong}")
    private String number;

    @NotNull(message = "{certificate.error.expiresOnRequired}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expiresOn;

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
}
