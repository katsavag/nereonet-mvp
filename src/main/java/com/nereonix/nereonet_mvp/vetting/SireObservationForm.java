package com.nereonix.nereonet_mvp.vetting;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.form.PlausibleDate;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.vetting.ObservationType;
import com.nereonix.nereonet_mvp.core.vetting.SireObservation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * "Add observation" and an observation's edit form (P41): where in the VIQ, its type and text as the report gives
 * them, and the follow-up (operator comment, corrective action, owner, target date, closed date). A closed date
 * closes it. The dates follow the P39 plausible-date rule: a target within 5 years either way, a closed date within
 * the last 20 years and not in the future. The owner is checked by the controller (an office user).
 */
public class SireObservationForm {

    @NotNull(message = "{sire.error.chapter}")
    @Min(value = 1, message = "{sire.error.chapter}")
    @Max(value = 12, message = "{sire.error.chapter}")
    private Integer chapter;

    @NotBlank(message = "{sire.error.question}")
    @Size(max = SireObservation.QUESTION_MAX_LENGTH, message = "{sire.error.tooLong}")
    @Pattern(regexp = "\\s*[0-9]+(\\.[0-9]+)*\\s*", message = "{sire.error.question}")
    private String questionNumber;

    @NotNull(message = "{sire.error.type}")
    private ObservationType type;

    @NotBlank(message = "{sire.error.description}")
    @Size(max = SireObservation.DESCRIPTION_MAX_LENGTH, message = "{sire.error.tooLong}")
    private String description;

    @Size(max = SireObservation.TEXT_MAX_LENGTH, message = "{sire.error.tooLong}")
    private String operatorComment;

    @Size(max = SireObservation.TEXT_MAX_LENGTH, message = "{sire.error.tooLong}")
    private String correctiveAction;

    private Long ownerId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @PlausibleDate(yearsBefore = 5, yearsAfter = 5)
    private LocalDate targetDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @PlausibleDate(yearsBefore = 20, yearsAfter = 0)
    private LocalDate closedOn;

    static SireObservationForm of(SireObservation o) {
        SireObservationForm form = new SireObservationForm();
        form.chapter = o.getChapter();
        form.questionNumber = o.getQuestionNumber();
        form.type = o.getType();
        form.description = o.getDescription();
        form.operatorComment = o.getOperatorComment();
        form.correctiveAction = o.getCorrectiveAction();
        form.ownerId = o.getOwner() == null ? null : o.getOwner().getId();
        form.targetDate = o.getTargetDate();
        form.closedOn = o.getClosedOn();
        return form;
    }

    /** The values as a draft for SireService.addObservation / updateObservation; owner already checked. */
    SireObservation toObservation(User owner) {
        SireObservation values = new SireObservation();
        values.setChapter(chapter);
        values.setQuestionNumber(questionNumber.strip());
        values.setType(type);
        values.setDescription(description.strip());
        values.setOperatorComment(SireInspectionForm.blankToNull(operatorComment));
        values.setCorrectiveAction(SireInspectionForm.blankToNull(correctiveAction));
        values.setOwner(owner);
        values.setTargetDate(targetDate);
        values.setClosedOn(closedOn);
        return values;
    }

    public Integer getChapter() { return chapter; }
    public void setChapter(Integer chapter) { this.chapter = chapter; }

    public String getQuestionNumber() { return questionNumber; }
    public void setQuestionNumber(String questionNumber) { this.questionNumber = questionNumber; }

    public ObservationType getType() { return type; }
    public void setType(ObservationType type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getOperatorComment() { return operatorComment; }
    public void setOperatorComment(String operatorComment) { this.operatorComment = operatorComment; }

    public String getCorrectiveAction() { return correctiveAction; }
    public void setCorrectiveAction(String correctiveAction) { this.correctiveAction = correctiveAction; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }

    public LocalDate getClosedOn() { return closedOn; }
    public void setClosedOn(LocalDate closedOn) { this.closedOn = closedOn; }
}
