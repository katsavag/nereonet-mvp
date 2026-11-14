package com.nereonix.nereonet_mvp.ship;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselDataService;

/**
 * The ship page's Vessel data tab (P45, ShipTab.VESSEL): what VesselAPI last returned for the vessel, from the stored
 * rows only; opening it never calls VesselAPI. "Refresh from VesselAPI" on it posts to ShipController's refresh, which
 * shows Edit vessel with the Saved / VesselAPI table.
 */
@Controller
public class VesselDataController {

    private final ShipFrame shipFrame;
    private final VesselDataService vesselDataService;

    public VesselDataController(ShipFrame shipFrame, VesselDataService vesselDataService) {
        this.shipFrame = shipFrame;
        this.vesselDataService = vesselDataService;
    }

    @GetMapping("/office/ships/{id}/vessel")
    public String vesselData(@PathVariable Long id, Model model) {
        Ship ship = shipFrame.ship(id);
        shipFrame.show(model, ship, ShipTab.VESSEL);
        model.addAttribute("vesselData", vesselDataService.view(ship));
        return "ship/vessel";
    }
}
