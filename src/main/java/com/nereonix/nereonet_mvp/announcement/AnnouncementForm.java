package com.nereonix.nereonet_mvp.announcement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.nereonix.nereonet_mvp.core.announcement.Announcement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// The new announcement form (P28). target is "fleet" (all active ships) or "ships" (the ticked shipIds). Nothing is
// preselected, so a fleet-wide announcement is never sent by accident. With "ships" and no box ticked the controller
// rejects the target field; the ticked boxes are ignored for "fleet".
public class AnnouncementForm {

    public static final String FLEET = "fleet";
    public static final String SHIPS = "ships";

    @NotBlank(message = "{announcement.error.targetRequired}")
    @Pattern(regexp = FLEET + "|" + SHIPS, message = "{announcement.error.targetRequired}")
    private String target;

    private List<Long> shipIds = new ArrayList<>();

    @NotBlank(message = "{announcement.error.titleRequired}")
    @Size(max = Announcement.TITLE_MAX_LENGTH, message = "{announcement.error.tooLong}")
    private String title;

    @NotBlank(message = "{announcement.error.bodyRequired}")
    @Size(max = Announcement.BODY_MAX_LENGTH, message = "{announcement.error.tooLong}")
    private String body;

    public boolean isFleet() {
        return FLEET.equals(target);
    }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    // Without duplicates or empty values, in the order sent.
    public List<Long> getShipIds() { return shipIds; }
    public void setShipIds(List<Long> shipIds) {
        this.shipIds = shipIds == null ? new ArrayList<>()
                : new ArrayList<>(shipIds.stream().filter(Objects::nonNull).distinct().toList());
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title == null ? null : title.strip(); }

    public String getBody() { return body; }
    // Browsers send line breaks as \r\n; store \n so each line break counts as one character, as in the counter.
    public void setBody(String body) { this.body = body == null ? null : body.replace("\r\n", "\n").strip(); }
}
