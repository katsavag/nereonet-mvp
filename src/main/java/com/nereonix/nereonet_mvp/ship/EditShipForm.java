package com.nereonix.nereonet_mvp.ship;

import java.util.Map;

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

    // "Greece (GR)" as picked from the list; only the code is stored.
    private String flag;

    /** Filled from the ship. countries: code -> "Greece (GR)", as Countries.labels gives them. */
    static EditShipForm of(Ship ship, Map<String, String> countries) {
        EditShipForm form = new EditShipForm();
        form.setName(ship.getName());
        form.setType(ship.getType());
        form.setFlag(ship.getFlag() == null ? null : countries.getOrDefault(ship.getFlag(), ship.getFlag()));
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
}
