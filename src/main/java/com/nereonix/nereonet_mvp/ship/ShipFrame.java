package com.nereonix.nereonet_mvp.ship;

import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;

/**
 * The frame of every tab of the ship page (ship/parts :: header): the ship header, the inactive banner and the tab
 * bar. The controller of each tab calls show() and renders its own template inside the frame. Model attributes:
 *   ship           the ship
 *   shipTab        the key of the active tab (ShipTab.key())
 *   shipTabs       every ShipTab, in order
 *   shipTabCounts  ShipTabCounts, the number shown on each tab
 *   vetting        the vessel's RightShip standing (RightShipView) for the header's badge row (P40)
 *   sire           a tanker's latest SIRE 2.0 inspection (SireView) for the same row (P41); null for other types
 *   editForm, types, countries   for the Edit ship pop-up of the header (ship/parts :: editModal)
 */
@Component
public class ShipFrame {

    private final ShipService shipService;
    private final VettingService vettingService;
    private final SireService sireService;

    public ShipFrame(ShipService shipService, VettingService vettingService, SireService sireService) {
        this.shipService = shipService;
        this.vettingService = vettingService;
        this.sireService = sireService;
    }

    /** The ship, or 404 if there is none. For tabs whose controller has no ship lookup of its own. */
    public Ship ship(Long id) {
        return shipService.get(id);
    }

    public void show(Model model, Ship ship, ShipTab tab) {
        model.addAttribute("ship", ship);
        model.addAttribute("shipTab", tab.key());
        model.addAttribute("shipTabs", ShipTab.values());
        model.addAttribute("shipTabCounts", shipService.tabCounts(ship));
        model.addAttribute("vetting", vettingService.view(ship));
        model.addAttribute("sire", VettingService.sireApplies(ship) ? sireService.latest(ship) : null);
        addEditForm(model, ship);
    }

    /** What the Edit ship form needs: the form filled from the ship (unless a posted one is there), types, countries. */
    static void addEditForm(Model model, Ship ship) {
        Map<String, String> countries = Countries.labels(LocaleContextHolder.getLocale());
        if (!model.containsAttribute("editForm")) {
            model.addAttribute("editForm", EditShipForm.of(ship, countries));
        }
        model.addAttribute("types", ShipType.values());
        model.addAttribute("countries", countries);
    }
}
