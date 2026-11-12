package com.nereonix.nereonet_mvp.announcement;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipient;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// The crew's announcements (P27, SecurityConfig: /ship/** is for ship roles). Each person sees only the announcements
// they are a recipient of, and only their own acknowledgement: never who else has or has not acknowledged. An
// announcement they did not receive is a 404. English only, as every crew screen.
@Controller
public class ShipAnnouncementController {

    private final AnnouncementService announcementService;
    private final UserRepository userRepository;

    public ShipAnnouncementController(AnnouncementService announcementService, UserRepository userRepository) {
        this.announcementService = announcementService;
        this.userRepository = userRepository;
    }

    @GetMapping("/ship/announcements")
    public String list(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        User user = userRepository.findByEmail(currentUser.getUsername()).orElseThrow();
        model.addAttribute("ship", user.getShip());
        model.addAttribute("rows", announcementService.forUser(currentUser.getUsername()));
        return "announcement/ship-list";
    }

    @GetMapping("/ship/announcements/{id}")
    public String detail(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        AnnouncementRecipient recipient = announcementService.recipient(currentUser.getUsername(), id);
        model.addAttribute("recipient", recipient);
        model.addAttribute("announcement", recipient.getAnnouncement());
        return "announcement/ship-detail";
    }

    // "I have read this". Pressing it again (a second tab, a resend over a bad link) changes nothing.
    @PostMapping("/ship/announcements/{id}/acknowledge")
    public String acknowledge(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                              RedirectAttributes redirectAttributes) {
        if (announcementService.acknowledge(currentUser.getUsername(), id)) {
            redirectAttributes.addFlashAttribute("successMessage", "announcement.ship.acknowledgedMessage");
        }
        return "redirect:/ship/announcements/" + id;
    }
}
