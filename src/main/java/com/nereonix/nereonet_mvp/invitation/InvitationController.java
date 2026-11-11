package com.nereonix.nereonet_mvp.invitation;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.nereonix.nereonet_mvp.ship.ShipTab;

// The former "Invite crew member" page of a ship (P17 removed it). Crew members are added with the Add crew member
// modal, on the Crew page or on a ship page's Crew tab (crew/CrewController); office users on the Office users page
// (crew/OfficeUserController). /office/** is limited to OFFICE_USER in SecurityConfig.
@Controller
public class InvitationController {

    // Old bookmarks and links: the ship's Crew tab with the Add crew member modal open and the ship preselected. The tab
    // gives 404 for an unknown ship and offers no modal for an inactive one.
    @GetMapping("/office/ships/{shipId}/invitations/new")
    public String crewForm(@PathVariable Long shipId) {
        return "redirect:" + ShipTab.CREW.url(shipId) + "?add=true";
    }
}
