package com.nereonix.nereonet_mvp.core.library;

import com.nereonix.nereonet_mvp.core.ship.Ship;

import jakarta.validation.constraints.Size;

// The "Edit ship particulars" form, used by the Master (library) and the office (officelibrary).
public class ParticularsForm {

    @Size(max = Ship.PARTICULARS_MAX_LENGTH, message = "{library.particulars.error.tooLong}")
    private String text;

    public String getText() { return text; }
    // Browsers send line breaks as \r\n; store \n so each line break counts as one character.
    public void setText(String text) { this.text = text == null ? null : text.replace("\r\n", "\n"); }
}
