package com.nereonix.nereonet_mvp.announcement;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.announcement.Announcement;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig. No edit or delete on purpose.
// The ship page's Announcements tab lists one ship's; New announcement there opens the form with that ship
// preselected (?shipId=) and, once sent, returns to the tab (the form's returnShipId field).
@Controller
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final ShipFrame shipFrame;

    public AnnouncementController(AnnouncementService announcementService, ShipFrame shipFrame) {
        this.announcementService = announcementService;
        this.shipFrame = shipFrame;
    }

    @GetMapping("/office/announcements")
    public String list(@RequestParam(required = false) Long shipId, Model model) {
        model.addAttribute("rows", announcementService.findAll(shipId));
        model.addAttribute("ships", announcementService.findAllShips());
        model.addAttribute("shipId", shipId);
        return "announcement/list";
    }

    // The same list for one ship (its announcements and those to all ships), without the ship filter.
    @GetMapping("/office/ships/{id}/announcements")
    public String shipList(@PathVariable Long id, Model model) {
        shipFrame.show(model, shipFrame.ship(id), ShipTab.ANNOUNCEMENTS);
        model.addAttribute("rows", announcementService.findAll(id));
        return "announcement/list";
    }

    // shipId: opened from that ship's Announcements tab. An active ship is then the preselected target;
    // without it nothing is preselected, so a fleet-wide announcement is never sent by accident.
    @GetMapping("/office/announcements/new")
    public String newForm(@RequestParam(required = false) Long shipId, Model model) {
        AnnouncementForm form = new AnnouncementForm();
        Ship fromShip = shipId == null ? null : shipFrame.ship(shipId);
        if (fromShip != null && fromShip.isActive()) {
            form.setTarget(fromShip.getId().toString());
        }
        model.addAttribute("form", form);
        return showForm(fromShip, model);
    }

    @PostMapping("/office/announcements/new")
    public String create(@Valid @ModelAttribute("form") AnnouncementForm form, BindingResult result,
                         @RequestParam(required = false) Long returnShipId, Model model,
                         @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        Ship fromShip = returnShipId == null ? null : shipFrame.ship(returnShipId);
        if (result.hasErrors()) {
            return showForm(fromShip, model);
        }
        Announcement announcement = announcementService.create(form.getTitle(), form.getBody(), form.shipId(),
                currentUser.getUsername());
        redirectAttributes.addFlashAttribute("successMessage", "announcement.created");
        if (fromShip != null) {
            return "redirect:" + ShipTab.ANNOUNCEMENTS.url(fromShip.getId());
        }
        return "redirect:/office/announcements/" + announcement.getId();
    }

    @GetMapping("/office/announcements/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Announcement announcement = announcementService.get(id);
        model.addAttribute("announcement", announcement);
        model.addAttribute("recipients", announcementService.findRecipients(announcement));
        return "announcement/detail";
    }

    // fromShip: the ship whose tab the form was opened from (breadcrumbs, Cancel, where to return), or null.
    private String showForm(Ship fromShip, Model model) {
        model.addAttribute("fromShip", fromShip);
        model.addAttribute("ships", announcementService.findActiveShips());
        model.addAttribute("fleet", AnnouncementForm.FLEET);
        return "announcement/form";
    }
}
