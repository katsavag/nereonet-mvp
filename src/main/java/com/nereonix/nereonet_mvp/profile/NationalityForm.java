package com.nereonix.nereonet_mvp.profile;

import com.nereonix.nereonet_mvp.core.profile.CrewProfile;
import com.nereonix.nereonet_mvp.ship.Countries;

import jakarta.validation.constraints.AssertTrue;

// My profile, Nationality (P31): an ISO 3166 code from the list, or empty for "Not given".
public class NationalityForm {

    private String nationality;

    public static NationalityForm of(CrewProfile profile) {
        NationalityForm form = new NationalityForm();
        form.setNationality(profile.getNationality());
        return form;
    }

    @AssertTrue(message = "{profile.nationality.error.unknown}")
    public boolean isNationalityValid() {
        return value() == null || Countries.isCode(value());
    }

    /** What to store: the code, or null for "Not given". */
    public String value() {
        return nationality == null || nationality.isBlank() ? null : nationality.trim();
    }

    public String getNationality() {
        return nationality;
    }

    public void setNationality(String nationality) {
        this.nationality = nationality;
    }
}
