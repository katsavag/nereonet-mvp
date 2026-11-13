package com.nereonix.nereonet_mvp.announcement;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.view.RedirectView;

import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipient;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// The crew's announcements (P27, SecurityConfig: /ship/** is for ship roles), called News on the crew side since P39
// (/ship/news; the office keeps "Announcements"). Each person sees only the news items they are a recipient of, and
// only their own read state: never who else has or has not read them. One they did not receive is a 404. Opening one
// marks it read (P39: no button). English only, as every crew screen.
@Controller
public class ShipAnnouncementController {

    static final String NEWS = "/ship/news";

    private final AnnouncementService announcementService;
    private final UserRepository userRepository;

    public ShipAnnouncementController(AnnouncementService announcementService, UserRepository userRepository) {
        this.announcementService = announcementService;
        this.userRepository = userRepository;
    }

    @GetMapping(NEWS)
    public String list(@AuthenticationPrincipal CurrentUser currentUser, Model model) {
        User user = userRepository.findByEmail(currentUser.getUsername()).orElseThrow();
        model.addAttribute("ship", user.getShip());
        model.addAttribute("rows", announcementService.forUser(currentUser.getUsername()));
        return "announcement/ship-list";
    }

    // Opening a news item reads it: the first time, the server records when (AnnouncementService.open).
    @GetMapping(NEWS + "/{id}")
    public String detail(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        AnnouncementRecipient recipient = announcementService.open(currentUser.getUsername(), id);
        model.addAttribute("recipient", recipient);
        model.addAttribute("announcement", recipient.getAnnouncement());
        return "announcement/ship-detail";
    }

    // The URLs before P39 (bookmarks, links in older notifications and emails): moved for good.
    @GetMapping("/ship/announcements")
    public RedirectView oldList() {
        return moved(NEWS);
    }

    @GetMapping("/ship/announcements/{id}")
    public RedirectView oldDetail(@PathVariable Long id) {
        return moved(NEWS + "/" + id);
    }

    private static RedirectView moved(String url) {
        RedirectView view = new RedirectView(url, true);
        view.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        return view;
    }
}
