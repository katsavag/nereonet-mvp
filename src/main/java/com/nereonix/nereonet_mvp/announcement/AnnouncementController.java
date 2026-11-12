package com.nereonix.nereonet_mvp.announcement;

import java.util.List;

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
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig. No edit or delete on purpose, so sending takes two steps
// (P10): the form posts to /review, which shows what will be sent and to how many people; only the review page's
// "Send to N people" posts to /send. "Back to edit" posts the text back to /new, which shows the form again.
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

    // The columns that sort (P19), the default first.
    private static final ListSort[] SORTS = {ListSort.desc("sentAt"), ListSort.asc("title"), ListSort.asc("target")};

    // listRequest: search, sort and page (P19).
    @GetMapping("/office/announcements")
    public String list(@RequestParam(required = false) Long shipId, @ModelAttribute ListRequest listRequest,
                       Model model) {
        List<Ship> ships = announcementService.findAllShips();
        ListQuery list = ListQuery.of("/office/announcements", listRequest, SORTS).filterShip(shipId, ships);
        model.addAttribute("list", list);
        model.addAttribute("rows", announcementService.findAll(shipId, list));
        model.addAttribute("ships", ships);
        model.addAttribute("shipId", shipId);
        return "announcement/list";
    }

    // The same list for one ship (its announcements and those to all ships), without the ship filter.
    @GetMapping("/office/ships/{id}/announcements")
    public String shipList(@PathVariable Long id, @ModelAttribute ListRequest listRequest, Model model) {
        shipFrame.show(model, shipFrame.ship(id), ShipTab.ANNOUNCEMENTS);
        ListQuery list = ListQuery.of(ShipTab.ANNOUNCEMENTS.url(id), listRequest, SORTS);
        model.addAttribute("list", list);
        model.addAttribute("rows", announcementService.findAll(id, list));
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

    // Step 1 of 2 (P10): the form's Review button. A valid form shows the review page: the target, the recipients per
    // ship and the text as the crew will see it. Nothing is saved; the review page carries the form in hidden fields.
    @PostMapping("/office/announcements/review")
    public String review(@Valid @ModelAttribute("form") AnnouncementForm form, BindingResult result,
                         @RequestParam(required = false) Long returnShipId, Model model) {
        Ship fromShip = returnShipId == null ? null : shipFrame.ship(returnShipId);
        if (!checkTarget(form, result)) {
            return showForm(fromShip, model);
        }
        List<AnnouncementService.ShipRecipients> perShip = announcementService.recipientsPerShip(form.shipId());
        model.addAttribute("fromShip", fromShip);
        model.addAttribute("targetShip", form.shipId() == null ? null : perShip.getFirst().ship());
        model.addAttribute("perShip", perShip);
        model.addAttribute("people", perShip.stream().mapToInt(AnnouncementService.ShipRecipients::people).sum());
        return "announcement/review";
    }

    // Back to edit on the review page: the form again, with the text as it was. Also where an old form page that still
    // posts here ends up, so nothing is ever sent from this URL.
    @PostMapping("/office/announcements/new")
    public String edit(@ModelAttribute("form") AnnouncementForm form,
                       @RequestParam(required = false) Long returnShipId, Model model) {
        return showForm(returnShipId == null ? null : shipFrame.ship(returnShipId), model);
    }

    // Step 2 of 2: "Send to N people" on the review page. The recipients are counted again now (the success message
    // gives the number it went to); a form that is no longer valid (e.g. the ship was deactivated meanwhile) goes
    // back to the form with its errors.
    @PostMapping("/office/announcements/send")
    public String send(@Valid @ModelAttribute("form") AnnouncementForm form, BindingResult result,
                       @RequestParam(required = false) Long returnShipId, Model model,
                       @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        Ship fromShip = returnShipId == null ? null : shipFrame.ship(returnShipId);
        if (!checkTarget(form, result)) {
            return showForm(fromShip, model);
        }
        AnnouncementService.Sent sent = announcementService.create(form.getTitle(), form.getBody(), form.shipId(),
                currentUser.getUsername());
        redirectAttributes.addFlashAttribute("successMessage", "announcement.created");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {sent.recipients()});
        if (fromShip != null) {
            return "redirect:" + ShipTab.ANNOUNCEMENTS.url(fromShip.getId());
        }
        return "redirect:/office/announcements/" + sent.announcement().getId();
    }

    // Review and send take only POST. A form sent from a page with one of these URLs (the form shown again with its
    // errors, or the review page) after the session expired comes back here after signing in (P12, ReturnTo): show the
    // form, where js/save.js restores the text from the browser's draft (P15).
    @GetMapping({"/office/announcements/review", "/office/announcements/send"})
    public String backToForm() {
        return "redirect:/office/announcements/new";
    }

    @GetMapping("/office/announcements/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Announcement announcement = announcementService.get(id);
        model.addAttribute("announcement", announcement);
        model.addAttribute("recipients", announcementService.findRecipients(announcement));
        return "announcement/detail";
    }

    // True when the form is valid and its target is the fleet or an active ship; a ship deactivated since the form
    // was opened is an error on the target field.
    private boolean checkTarget(AnnouncementForm form, BindingResult result) {
        if (!result.hasErrors() && form.shipId() != null
                && announcementService.findActiveShip(form.shipId()).isEmpty()) {
            result.rejectValue("target", "announcement.error.targetRequired");
        }
        return !result.hasErrors();
    }

    // fromShip: the ship whose tab the form was opened from (breadcrumbs, Cancel, where to return), or null.
    private String showForm(Ship fromShip, Model model) {
        model.addAttribute("fromShip", fromShip);
        model.addAttribute("ships", announcementService.findActiveShips());
        model.addAttribute("fleet", AnnouncementForm.FLEET);
        return "announcement/form";
    }
}
