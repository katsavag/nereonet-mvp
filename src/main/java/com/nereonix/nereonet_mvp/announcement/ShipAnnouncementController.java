package com.nereonix.nereonet_mvp.announcement;

import java.util.List;

import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
    /** News items per page (P47): the list shows pages 1 to ?page=n, "Show older" adds the next one. */
    static final int PAGE_SIZE = 10;
    /** The most pages one request shows, so a hand-made URL cannot ask for everything at once. */
    static final int MAX_PAGES = 50;
    /** The longest preview sent to the browser; CSS cuts it to one line. */
    static final int PREVIEW_LENGTH = 160;

    private final AnnouncementService announcementService;
    private final UserRepository userRepository;

    public ShipAnnouncementController(AnnouncementService announcementService, UserRepository userRepository) {
        this.announcementService = announcementService;
        this.userRepository = userRepository;
    }

    /** One row of the News list (P47): the person's own recipient row and a one-line plain-text preview of the text. */
    public record NewsRow(AnnouncementRecipient recipient, String preview) {

        public boolean isUnread() {
            return recipient.getAcknowledgedAt() == null;
        }
    }

    // P47: the first `page` pages (10 each) in one list, limited in the database: unread first ("New"), then read
    // ("Earlier"), each newest first. "Show older" links to page + 1 while there are more.
    @GetMapping(NEWS)
    public String list(@AuthenticationPrincipal CurrentUser currentUser,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        User user = userRepository.findByEmail(currentUser.getUsername()).orElseThrow();
        int pages = Math.clamp(page, 1, MAX_PAGES);
        Slice<AnnouncementRecipient> slice = announcementService.forUser(currentUser.getUsername(), pages * PAGE_SIZE);
        List<NewsRow> rows = slice.getContent().stream()
                .map(r -> new NewsRow(r, preview(r.getAnnouncement().getBody()))).toList();
        model.addAttribute("ship", user.getShip());
        model.addAttribute("unreadRows", rows.stream().filter(NewsRow::isUnread).toList());
        model.addAttribute("readRows", rows.stream().filter(r -> !r.isUnread()).toList());
        model.addAttribute("unread", announcementService.unreadFor(currentUser.getUsername()));
        model.addAttribute("nextPage", slice.hasNext() && pages < MAX_PAGES ? pages + 1 : null);
        return "announcement/ship-list";
    }

    /** The text on one line (every run of white space a single space), at most PREVIEW_LENGTH characters with "…". */
    static String preview(String body) {
        if (body == null) {
            return "";
        }
        String line = body.replaceAll("\\s+", " ").strip();
        return line.length() <= PREVIEW_LENGTH ? line : line.substring(0, PREVIEW_LENGTH - 1).stripTrailing() + "…";
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
