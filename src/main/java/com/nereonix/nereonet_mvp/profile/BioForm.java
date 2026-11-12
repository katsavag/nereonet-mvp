package com.nereonix.nereonet_mvp.profile;

import com.nereonix.nereonet_mvp.core.profile.CrewProfile;

import jakarta.validation.constraints.Size;

// My profile, Bio (P31): plain text, at most 280 characters, line breaks kept. Line breaks are counted as one
// character each, as the counter under the field does (browsers send them as \r\n). Empty is stored as null.
public class BioForm {

    @Size(max = CrewProfile.BIO_MAX_LENGTH, message = "{profile.bio.error.tooLong}")
    private String bio;

    public static BioForm of(CrewProfile profile) {
        BioForm form = new BioForm();
        form.setBio(profile.getBio());
        return form;
    }

    /** What to store: the text, or null when empty. */
    public String value() {
        return bio == null || bio.isEmpty() ? null : bio;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio == null ? null : bio.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
