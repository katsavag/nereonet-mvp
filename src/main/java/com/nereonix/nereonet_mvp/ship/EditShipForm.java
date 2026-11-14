package com.nereonix.nereonet_mvp.ship;

import java.util.ArrayList;
import java.util.List;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipType;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The IMO number cannot be changed, so it is not part of this form.
public class EditShipForm {

    @NotBlank(message = "{ship.error.nameRequired}")
    @Size(max = 255)
    private String name;

    private ShipType type;

    // P46: the 2-letter code ("GR"), typed or picked from the list; stored in upper case. "Greece (GR)" is still
    // accepted (Countries.toCode).
    private String flag;

    // P45: the token of a "Refresh from VesselAPI" kept by the server (VesselLookupStore) until Save.
    private String lookupToken;

    // P45: the ticked rows of the Saved / VesselAPI table (name, type, flag): only these change, with the server's
    // values of the lookup.
    private List<String> accept = new ArrayList<>();

    // P45: the flag chosen when VesselAPI's country and country code disagree (an ISO code); empty keeps the saved one.
    private String flagChoice;

    /** Filled from the ship; the flag as its code ("GR"). */
    static EditShipForm of(Ship ship) {
        EditShipForm form = new EditShipForm();
        form.setName(ship.getName());
        form.setType(ship.getType());
        form.setFlag(ship.getFlag());
        return form;
    }

    @AssertTrue(message = "{ship.error.flagInvalid}")
    public boolean isFlagValid() {
        return Countries.isValidOrBlank(flag);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ShipType getType() { return type; }
    public void setType(ShipType type) { this.type = type; }

    public String getFlag() { return flag; }
    public void setFlag(String flag) { this.flag = flag; }

    public String getLookupToken() { return lookupToken; }
    public void setLookupToken(String lookupToken) { this.lookupToken = lookupToken; }

    public List<String> getAccept() { return accept; }
    public void setAccept(List<String> accept) { this.accept = accept == null ? new ArrayList<>() : accept; }

    public String getFlagChoice() { return flagChoice; }
    public void setFlagChoice(String flagChoice) { this.flagChoice = flagChoice; }
}
