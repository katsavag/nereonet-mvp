package com.nereonix.nereonet_mvp.vetting;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.form.PlausibleDate;
import com.nereonix.nereonet_mvp.core.vetting.GhgRating;
import com.nereonix.nereonet_mvp.core.vetting.InspectionOutcome;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatus;
import com.nereonix.nereonet_mvp.core.vetting.Subscore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * "Edit RightShip data" (P40): every field of a vessel's RightShip standing, as the office reads it off the RightShip
 * report or platform. Each field may stay empty (not known). Saving adds a row (VettingService.saveManual).
 * The fields are in the order of the page: Safety Score, the six subscores in their impact groups, GHG Rating,
 * inspection, restrictions, the platform link.
 */
public class RightShipForm {

    // Safety Score: empty not rated, 0 sanctioned, 1 to 5.
    @Min(value = 0, message = "{vetting.error.score}")
    @Max(value = 5, message = "{vetting.error.score}")
    private Integer safetyScore;

    private boolean indicative;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @PlausibleDate(yearsBefore = 20, yearsAfter = 0)
    private LocalDate calculatedAt;

    @Min(value = 1, message = "{vetting.error.subscore}")
    @Max(value = 5, message = "{vetting.error.subscore}")
    private Integer vesselIncident;

    @Min(value = 1, message = "{vetting.error.subscore}")
    @Max(value = 5, message = "{vetting.error.subscore}")
    private Integer doc;

    @Min(value = 1, message = "{vetting.error.subscore}")
    @Max(value = 5, message = "{vetting.error.subscore}")
    private Integer pscDeficiencies;

    @Min(value = 1, message = "{vetting.error.subscore}")
    @Max(value = 5, message = "{vetting.error.subscore}")
    private Integer detentions;

    @Min(value = 1, message = "{vetting.error.subscore}")
    @Max(value = 5, message = "{vetting.error.subscore}")
    private Integer flag;

    @Min(value = 1, message = "{vetting.error.subscore}")
    @Max(value = 5, message = "{vetting.error.subscore}")
    private Integer classPerformance;

    private GhgRating ghgRating;

    private boolean ghgPlus;

    private boolean ghgVerified;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @PlausibleDate(yearsBefore = 20, yearsAfter = 0)
    private LocalDate ghgRatedAt;

    // Empty: not known.
    private Boolean inspectionRequired;

    private InspectionOutcome lastInspectionOutcome;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @PlausibleDate(yearsBefore = 10, yearsAfter = 5)
    private LocalDate inspectionValidUntil;

    private boolean restricted;

    @Size(max = ShipRightShipStatus.COMMENTS_MAX_LENGTH, message = "{vetting.error.tooLong}")
    private String restrictionComments;

    @Size(max = ShipRightShipStatus.LINK_MAX_LENGTH, message = "{vetting.error.tooLong}")
    private String platformLink;

    /** A + exists for A only. */
    @AssertTrue(message = "{vetting.error.ghgPlus}")
    public boolean isGhgPlusValid() {
        return !ghgPlus || ghgRating == GhgRating.A;
    }

    /** The vessel's page on the RightShip platform: an https:// address, or nothing. */
    @AssertTrue(message = "{vetting.error.link}")
    public boolean isPlatformLinkValid() {
        return platformLink == null || platformLink.isBlank()
                || (platformLink.strip().startsWith("https://") && !platformLink.strip().contains(" "));
    }

    /** Filled from the vessel's current row, or empty without one. */
    static RightShipForm of(ShipRightShipStatus status) {
        RightShipForm form = new RightShipForm();
        if (status == null) {
            return form;
        }
        form.safetyScore = status.getSafetyScore();
        form.indicative = status.isIndicative();
        form.calculatedAt = status.getCalculatedAt();
        form.vesselIncident = status.subscore(Subscore.VESSEL_INCIDENT);
        form.doc = status.subscore(Subscore.DOC);
        form.pscDeficiencies = status.subscore(Subscore.PSC_DEFICIENCIES);
        form.detentions = status.subscore(Subscore.DETENTIONS);
        form.flag = status.subscore(Subscore.FLAG);
        form.classPerformance = status.subscore(Subscore.CLASS);
        form.ghgRating = status.getGhgRating();
        form.ghgPlus = status.isGhgPlus();
        form.ghgVerified = status.isGhgVerified();
        form.ghgRatedAt = status.getGhgRatedAt();
        form.inspectionRequired = status.getInspectionRequired();
        form.lastInspectionOutcome = status.getLastInspectionOutcome();
        form.inspectionValidUntil = status.getInspectionValidUntil();
        form.restricted = status.isRestricted();
        form.restrictionComments = status.getRestrictionComments();
        form.platformLink = status.getPlatformLink();
        return form;
    }

    /** The values as a draft row for VettingService.saveManual. */
    ShipRightShipStatus toStatus() {
        ShipRightShipStatus row = new ShipRightShipStatus();
        row.setSafetyScore(safetyScore);
        row.setIndicative(indicative && safetyScore != null);
        row.setCalculatedAt(calculatedAt);
        row.setSubscore(Subscore.VESSEL_INCIDENT, vesselIncident);
        row.setSubscore(Subscore.DOC, doc);
        row.setSubscore(Subscore.PSC_DEFICIENCIES, pscDeficiencies);
        row.setSubscore(Subscore.DETENTIONS, detentions);
        row.setSubscore(Subscore.FLAG, flag);
        row.setSubscore(Subscore.CLASS, classPerformance);
        row.setGhgRating(ghgRating);
        row.setGhgPlus(ghgPlus && ghgRating == GhgRating.A);
        row.setGhgVerified(ghgVerified && ghgRating != null && ghgRating.rated());
        row.setGhgRatedAt(ghgRatedAt);
        row.setInspectionRequired(inspectionRequired);
        row.setLastInspectionOutcome(lastInspectionOutcome);
        row.setInspectionValidUntil(inspectionValidUntil);
        row.setRestricted(restricted);
        row.setRestrictionComments(blankToNull(restrictionComments));
        row.setPlatformLink(blankToNull(platformLink));
        return row;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    public Integer getSafetyScore() { return safetyScore; }
    public void setSafetyScore(Integer safetyScore) { this.safetyScore = safetyScore; }

    public boolean isIndicative() { return indicative; }
    public void setIndicative(boolean indicative) { this.indicative = indicative; }

    public LocalDate getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(LocalDate calculatedAt) { this.calculatedAt = calculatedAt; }

    public Integer getVesselIncident() { return vesselIncident; }
    public void setVesselIncident(Integer vesselIncident) { this.vesselIncident = vesselIncident; }

    public Integer getDoc() { return doc; }
    public void setDoc(Integer doc) { this.doc = doc; }

    public Integer getPscDeficiencies() { return pscDeficiencies; }
    public void setPscDeficiencies(Integer pscDeficiencies) { this.pscDeficiencies = pscDeficiencies; }

    public Integer getDetentions() { return detentions; }
    public void setDetentions(Integer detentions) { this.detentions = detentions; }

    public Integer getFlag() { return flag; }
    public void setFlag(Integer flag) { this.flag = flag; }

    public Integer getClassPerformance() { return classPerformance; }
    public void setClassPerformance(Integer classPerformance) { this.classPerformance = classPerformance; }

    public GhgRating getGhgRating() { return ghgRating; }
    public void setGhgRating(GhgRating ghgRating) { this.ghgRating = ghgRating; }

    public boolean isGhgPlus() { return ghgPlus; }
    public void setGhgPlus(boolean ghgPlus) { this.ghgPlus = ghgPlus; }

    public boolean isGhgVerified() { return ghgVerified; }
    public void setGhgVerified(boolean ghgVerified) { this.ghgVerified = ghgVerified; }

    public LocalDate getGhgRatedAt() { return ghgRatedAt; }
    public void setGhgRatedAt(LocalDate ghgRatedAt) { this.ghgRatedAt = ghgRatedAt; }

    public Boolean getInspectionRequired() { return inspectionRequired; }
    public void setInspectionRequired(Boolean inspectionRequired) { this.inspectionRequired = inspectionRequired; }

    public InspectionOutcome getLastInspectionOutcome() { return lastInspectionOutcome; }
    public void setLastInspectionOutcome(InspectionOutcome lastInspectionOutcome) { this.lastInspectionOutcome = lastInspectionOutcome; }

    public LocalDate getInspectionValidUntil() { return inspectionValidUntil; }
    public void setInspectionValidUntil(LocalDate inspectionValidUntil) { this.inspectionValidUntil = inspectionValidUntil; }

    public boolean isRestricted() { return restricted; }
    public void setRestricted(boolean restricted) { this.restricted = restricted; }

    public String getRestrictionComments() { return restrictionComments; }
    public void setRestrictionComments(String restrictionComments) { this.restrictionComments = restrictionComments; }

    public String getPlatformLink() { return platformLink; }
    public void setPlatformLink(String platformLink) { this.platformLink = platformLink; }
}
