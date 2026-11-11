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

import jakarta.validation.Valid;

// /office/** is limited to OFFICE_USER in SecurityConfig. No edit or delete on purpose.
@Controller
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping("/office/announcements")
    public String list(@RequestParam(required = false) Long shipId, Model model) {
        model.addAttribute("rows", announcementService.findAll(shipId));
        model.addAttribute("ships", announcementService.findAllShips());
        model.addAttribute("shipId", shipId);
        return "announcement/list";
    }

    @GetMapping("/office/announcements/new")
    public String newForm(Model model) {
        model.addAttribute("form", new AnnouncementForm());
        return showForm(model);
    }

    @PostMapping("/office/announcements/new")
    public String create(@Valid @ModelAttribute("form") AnnouncementForm form, BindingResult result, Model model,
                         @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return showForm(model);
        }
        Announcement announcement = announcementService.create(form.getTitle(), form.getBody(), form.shipId(),
                currentUser.getUsername());
        redirectAttributes.addFlashAttribute("successMessage", "announcement.created");
        return "redirect:/office/announcements/" + announcement.getId();
    }

    @GetMapping("/office/announcements/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Announcement announcement = announcementService.get(id);
        model.addAttribute("announcement", announcement);
        model.addAttribute("recipients", announcementService.findRecipients(announcement));
        return "announcement/detail";
    }

    private String showForm(Model model) {
        model.addAttribute("ships", announcementService.findActiveShips());
        model.addAttribute("fleet", AnnouncementForm.FLEET);
        return "announcement/form";
    }
}
