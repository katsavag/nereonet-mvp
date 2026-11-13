package com.nereonix.nereonet_mvp.vetting;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.form.PlausibleDate;
import com.nereonix.nereonet_mvp.core.vetting.SireInspection;
import com.nereonix.nereonet_mvp.core.vetting.SireStatus;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * "Add inspection" and "Edit inspection" (P41): a SIRE 2.0 inspection as the report gives it. The inspection date,
 * port, submitting company and status are required; the dates follow the P39 plausible-date rule (an inspection or
 * publication within the last 20 years, not in the future), and the report is not published before the inspection.
 */
public class SireInspectionForm {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @NotNull(message = "{sire.error.inspectionDate}")
    @PlausibleDate(yearsBefore = 20, yearsAfter = 0)
    private LocalDate inspectionDate;

    @NotBlank(message = "{sire.error.port}")
    @Size(max = SireInspection.PORT_MAX_LENGTH, message = "{sire.error.tooLong}")
    private String port;

    @NotBlank(message = "{sire.error.company}")
    @Size(max = SireInspection.COMPANY_MAX_LENGTH, message = "{sire.error.tooLong}")
    private String submittingCompany;

    @Size(max = SireInspection.INSPECTOR_MAX_LENGTH, message = "{sire.error.tooLong}")
    private String inspectorName;

    @Size(max = SireInspection.REFERENCE_MAX_LENGTH, message = "{sire.error.tooLong}")
    private String reportReference;

    @NotNull(message = "{sire.error.status}")
    private SireStatus status;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @PlausibleDate(yearsBefore = 20, yearsAfter = 0)
    private LocalDate publishedOn;

    @AssertTrue(message = "{sire.error.publishedBeforeInspection}")
    public boolean isPublishedAfterInspection() {
        return publishedOn == null || inspectionDate == null || !publishedOn.isBefore(inspectionDate);
    }

    /** A new inspection: a draft has just come in. */
    static SireInspectionForm empty() {
        SireInspectionForm form = new SireInspectionForm();
        form.status = SireStatus.DRAFT_RECEIVED;
        return form;
    }

    static SireInspectionForm of(SireInspection inspection) {
        SireInspectionForm form = new SireInspectionForm();
        form.inspectionDate = inspection.getInspectionDate();
        form.port = inspection.getPort();
        form.submittingCompany = inspection.getSubmittingCompany();
        form.inspectorName = inspection.getInspectorName();
        form.reportReference = inspection.getReportReference();
        form.status = inspection.getStatus();
        form.publishedOn = inspection.getPublishedOn();
        return form;
    }

    /** The values as a draft for SireService.addInspection / updateInspection. */
    SireInspection toInspection() {
        SireInspection values = new SireInspection();
        values.setInspectionDate(inspectionDate);
        values.setPort(port.strip());
        values.setSubmittingCompany(submittingCompany.strip());
        values.setInspectorName(blankToNull(inspectorName));
        values.setReportReference(blankToNull(reportReference));
        values.setStatus(status);
        values.setPublishedOn(publishedOn);
        return values;
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    public LocalDate getInspectionDate() { return inspectionDate; }
    public void setInspectionDate(LocalDate inspectionDate) { this.inspectionDate = inspectionDate; }

    public String getPort() { return port; }
    public void setPort(String port) { this.port = port; }

    public String getSubmittingCompany() { return submittingCompany; }
    public void setSubmittingCompany(String submittingCompany) { this.submittingCompany = submittingCompany; }

    public String getInspectorName() { return inspectorName; }
    public void setInspectorName(String inspectorName) { this.inspectorName = inspectorName; }

    public String getReportReference() { return reportReference; }
    public void setReportReference(String reportReference) { this.reportReference = reportReference; }

    public SireStatus getStatus() { return status; }
    public void setStatus(SireStatus status) { this.status = status; }

    public LocalDate getPublishedOn() { return publishedOn; }
    public void setPublishedOn(LocalDate publishedOn) { this.publishedOn = publishedOn; }
}
