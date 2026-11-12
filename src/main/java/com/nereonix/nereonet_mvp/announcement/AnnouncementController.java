package com.nereonix.nereonet_mvp.announcement;

import java.text.Collator;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.i18n.LocaleContextHolder;
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
import com.nereonix.nereonet_mvp.ship.Countries;
import com.nereonix.nereonet_mvp.ship.ShipFrame;
import com.nereonix.nereonet_mvp.ship.ShipTab;

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig. No edit or delete on purpose, so sending takes two steps
// (P10): the form posts to /review, which shows what will be sent and to how many people; only the review page's
// "Send to N people on N ships" posts to /send. "Back to edit" posts the text back to /new, which shows the form again.
// The target is All ships or Selected ships, a ticked list of active ships (P28).
// The ship page's Announcements tab lists one ship's; New announcement there opens the form with that ship ticked
// (?shipId=) and, once sent, returns to the tab (the form's returnShipId field).
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

    // The same list for one ship (those to all ships and those with this ship among their targets), without the ship
    // filter.
    @GetMapping("/office/ships/{id}/announcements")
    public String shipList(@PathVariable Long id, @ModelAttribute ListRequest listRequest, Model model) {
        shipFrame.show(model, shipFrame.ship(id), ShipTab.ANNOUNCEMENTS);
        ListQuery list = ListQuery.of(ShipTab.ANNOUNCEMENTS.url(id), listRequest, SORTS);
        model.addAttribute("list", list);
        model.addAttribute("rows", announcementService.findAll(id, list));
        return "announcement/list";
    }

    // shipId: opened from that ship's Announcements tab. An active ship is then ticked under Selected ships; without
    // it nothing is preselected, so a fleet-wide announcement is never sent by accident.
    @GetMapping("/office/announcements/new")
    public String newForm(@RequestParam(required = false) Long shipId, Model model) {
        AnnouncementForm form = new AnnouncementForm();
        Ship fromShip = shipId == null ? null : shipFrame.ship(shipId);
        if (fromShip != null && fromShip.isActive()) {
            form.setTarget(AnnouncementForm.SHIPS);
            form.setShipIds(List.of(fromShip.getId()));
        }
        model.addAttribute("form", form);
        return showForm(fromShip, model);
    }

    // Step 1 of 2 (P10): the form's Review button. A valid form shows the review page: the target ships, the
    // recipients per ship, the chosen ships that are no longer active (left out) and the text as the crew will see it.
    // Nothing is saved; the review page carries the form in hidden fields, with the active ships only.
    @PostMapping("/office/announcements/review")
    public String review(@Valid @ModelAttribute("form") AnnouncementForm form, BindingResult result,
                         @RequestParam(required = false) Long returnShipId, Model model) {
        Ship fromShip = returnShipId == null ? null : shipFrame.ship(returnShipId);
        AnnouncementService.Targets targets = checkTarget(form, result);
        if (targets == null) {
            return showForm(fromShip, model);
        }
        return showReview(form, targets, fromShip, model);
    }

    // Back to edit on the review page: the form again, with the text as it was. Also where an old form page that still
    // posts here ends up, so nothing is ever sent from this URL.
    @PostMapping("/office/announcements/new")
    public String edit(@ModelAttribute("form") AnnouncementForm form,
                       @RequestParam(required = false) Long returnShipId, Model model) {
        return showForm(returnShipId == null ? null : shipFrame.ship(returnShipId), model);
    }

    // Step 2 of 2: "Send to N people on N ships" on the review page. The ships and recipients are worked out again now
    // (the success message gives the number it went to). A chosen ship deactivated since the review page was shown
    // sends nothing: the review page comes back, saying that ship is left out, for another look. With no active ship
    // left (or a form no longer valid) it goes back to the form with its errors.
    @PostMapping("/office/announcements/send")
    public String send(@Valid @ModelAttribute("form") AnnouncementForm form, BindingResult result,
                       @RequestParam(required = false) Long returnShipId, Model model,
                       @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        Ship fromShip = returnShipId == null ? null : shipFrame.ship(returnShipId);
        AnnouncementService.Targets targets = checkTarget(form, result);
        if (targets == null) {
            return showForm(fromShip, model);
        }
        if (!targets.leftOut().isEmpty()) {
            return showReview(form, targets, fromShip, model);
        }
        AnnouncementService.Sent sent = announcementService.create(form.getTitle(), form.getBody(), form.isFleet(),
                targets.shipIds(), currentUser.getUsername());
        redirectAttributes.addFlashAttribute("successMessage", "announcement.created");
        redirectAttributes.addFlashAttribute("successArgs", new Object[] {sent.recipients(), sent.ships()});
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

    // The text, then the acknowledgements per ship (with the total) and who has and has not acknowledged (P28).
    @GetMapping("/office/announcements/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Announcement announcement = announcementService.get(id);
        AnnouncementService.Recipients recipients = announcementService.findRecipients(announcement);
        model.addAttribute("announcement", announcement);
        model.addAttribute("recipients", recipients);
        model.addAttribute("perShip", announcementService.acknowledgementsPerShip(announcement, recipients));
        return "announcement/detail";
    }

    // The targets of a valid form, or null with an error on the target field: Selected ships with no box ticked, or
    // with none of the ticked ships still active (deactivated since the form was opened).
    private AnnouncementService.Targets checkTarget(AnnouncementForm form, BindingResult result) {
        if (result.hasFieldErrors("target")) {
            return null;
        }
        if (!form.isFleet() && form.getShipIds().isEmpty()) {
            result.rejectValue("target", "announcement.error.shipsRequired");
            return null;
        }
        AnnouncementService.Targets targets = announcementService.targets(form.isFleet(), form.getShipIds());
        if (!form.isFleet() && targets.isEmpty()) {
            result.rejectValue("target", "announcement.error.shipsInactive");
        }
        return result.hasErrors() ? null : targets;
    }

    // The review page. The form keeps only the active target ships, so Send and Back to edit post those.
    private String showReview(AnnouncementForm form, AnnouncementService.Targets targets, Ship fromShip, Model model) {
        form.setShipIds(form.isFleet() ? List.of() : targets.shipIds());
        List<AnnouncementService.ShipRecipients> perShip = announcementService.recipientsPerShip(targets);
        model.addAttribute("fromShip", fromShip);
        model.addAttribute("targets", targets);
        model.addAttribute("onlyShip", !targets.fleet() && targets.ships().size() == 1 ? targets.ships().getFirst() : null);
        model.addAttribute("perShip", perShip);
        model.addAttribute("people", perShip.stream().mapToInt(AnnouncementService.ShipRecipients::people).sum());
        return "announcement/review";
    }

    // fromShip: the ship whose tab the form was opened from (breadcrumbs, Cancel, where to return), or null.
    // The Selected ships list: the active ships, with the types and flags among them for its quick filters.
    private String showForm(Ship fromShip, Model model) {
        List<Ship> ships = announcementService.findActiveShips();
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> countries = Countries.labels(locale);
        Collator collator = Collator.getInstance(locale);
        Map<String, String> flags = new LinkedHashMap<>();
        ships.stream().map(Ship::getFlag).filter(Objects::nonNull).distinct()
                .sorted(Comparator.comparing(code -> countries.getOrDefault(code, code), collator))
                .forEach(code -> flags.put(code, countries.getOrDefault(code, code)));
        model.addAttribute("fromShip", fromShip);
        model.addAttribute("ships", ships);
        model.addAttribute("shipTypes", ships.stream().map(Ship::getType).filter(Objects::nonNull).distinct()
                .sorted().toList());
        model.addAttribute("shipFlags", flags);
        model.addAttribute("fleet", AnnouncementForm.FLEET);
        model.addAttribute("selected", AnnouncementForm.SHIPS);
        return "announcement/form";
    }
}
