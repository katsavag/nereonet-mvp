package com.nereonix.nereonet_mvp.announcement;

import com.nereonix.nereonet_mvp.core.announcement.Announcement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// The new announcement form. target is "fleet" (all ships) or a ship id. Nothing is preselected,
// so a fleet-wide announcement is never sent by accident.
public class AnnouncementForm {

    public static final String FLEET = "fleet";

    @NotBlank(message = "{announcement.error.titleRequired}")
    @Size(max = Announcement.TITLE_MAX_LENGTH, message = "{announcement.error.tooLong}")
    private String title;

    @NotBlank(message = "{announcement.error.bodyRequired}")
    @Size(max = Announcement.BODY_MAX_LENGTH, message = "{announcement.error.tooLong}")
    private String body;

    @NotBlank(message = "{announcement.error.targetRequired}")
    @Pattern(regexp = FLEET + "|\\d{1,18}", message = "{announcement.error.targetRequired}")
    private String target;

    // Null for the whole fleet. Call only on a valid form.
    public Long shipId() {
        return FLEET.equals(target) ? null : Long.valueOf(target);
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title == null ? null : title.strip(); }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body == null ? null : body.strip(); }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
}
