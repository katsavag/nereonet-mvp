package com.nereonix.nereonet_mvp.crew;

import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The email cannot be changed, so it is not part of this form. There is no role: saving sets the one of the rank
// (P18), also for a user whose rank and role disagreed (User.rankNeedsReview).
public class EditCrewForm {

    @NotBlank(message = "{user.error.fullNameRequired}")
    @Size(max = 255)
    private String fullName;

    @NotNull(message = "{user.error.rankRequired}")
    private Rank rank;

    @NotNull(message = "{user.error.shipRequired}")
    private Long shipId;

    static EditCrewForm of(User user) {
        EditCrewForm form = new EditCrewForm();
        form.setFullName(user.getFullName());
        form.setRank(user.getRank());
        // Inactive ships are not in the list, so the office has to pick another one.
        form.setShipId(user.getShip().isActive() ? user.getShip().getId() : null);
        return form;
    }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public Rank getRank() { return rank; }
    public void setRank(Rank rank) { this.rank = rank; }

    public Long getShipId() { return shipId; }
    public void setShipId(Long shipId) { this.shipId = shipId; }
}
