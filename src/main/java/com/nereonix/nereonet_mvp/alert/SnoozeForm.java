package com.nereonix.nereonet_mvp.alert;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.alert.AlertSnooze;
import com.nereonix.nereonet_mvp.core.alert.SnoozeReason;
import com.nereonix.nereonet_mvp.core.form.PlausibleDate;

import jakarta.validation.constraints.Size;

/**
 * The snooze page's form (P43): the items (AlertKey params, one or more), the reason (required; Other needs the note),
 * and how long: 7 days, 30 days, until a date (then untilDate, within a year ahead, P39) or until the item expires.
 * returnTo: the office page to go back to.
 */
public class SnoozeForm {

    /** How long a snooze lasts. Labels: snooze.duration.<NAME>. */
    public enum Duration {
        DAYS_7, DAYS_30, DATE, UNTIL_EXPIRY;

        public String messageKey() {
            return "snooze.duration." + name();
        }
    }

    private List<String> items = new ArrayList<>();

    private SnoozeReason reason;

    @Size(max = AlertSnooze.NOTE_MAX_LENGTH, message = "{snooze.error.noteTooLong}")
    private String note;

    private Duration duration;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @PlausibleDate(yearsBefore = 0, yearsAfter = 1)
    private LocalDate untilDate;

    private String returnTo;

    public List<String> getItems() { return items; }
    public void setItems(List<String> items) { this.items = items == null ? new ArrayList<>() : items; }

    public SnoozeReason getReason() { return reason; }
    public void setReason(SnoozeReason reason) { this.reason = reason; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null || note.isBlank() ? null : note.strip(); }

    public Duration getDuration() { return duration; }
    public void setDuration(Duration duration) { this.duration = duration; }

    public LocalDate getUntilDate() { return untilDate; }
    public void setUntilDate(LocalDate untilDate) { this.untilDate = untilDate; }

    public String getReturnTo() { return returnTo; }
    public void setReturnTo(String returnTo) { this.returnTo = returnTo; }
}
