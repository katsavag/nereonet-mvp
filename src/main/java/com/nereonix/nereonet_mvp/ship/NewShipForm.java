package com.nereonix.nereonet_mvp.ship;

import com.nereonix.nereonet_mvp.core.ship.ImoNumber;
import com.nereonix.nereonet_mvp.core.ship.ShipType;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class NewShipForm {

    // Accepts "9074729" or "IMO 9074729".
    private String imoNumber;

    @NotBlank(message = "{ship.error.nameRequired}")
    @Size(max = 255)
    private String name;

    private ShipType type;

    // "Greece (GR)" as picked from the list; only the code is stored.
    private String flag;

    // Also false when the field is empty.
    @AssertTrue(message = "{ship.error.imoInvalid}")
    public boolean isImoNumberValid() {
        return ImoNumber.isValid(ImoNumber.normalize(imoNumber));
    }

    @AssertTrue(message = "{ship.error.flagInvalid}")
    public boolean isFlagValid() {
        return Countries.isValidOrBlank(flag);
    }

    public String getImoNumber() { return imoNumber; }
    public void setImoNumber(String imoNumber) { this.imoNumber = imoNumber; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ShipType getType() { return type; }
    public void setType(ShipType type) { this.type = type; }

    public String getFlag() { return flag; }
    public void setFlag(String flag) { this.flag = flag; }
}
