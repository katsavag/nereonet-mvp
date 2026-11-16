package com.nereonix.nereonet_mvp.announcement;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.view.RedirectView;

import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipient;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// The crew's announcements (SecurityConfig: /ship/** is for ship roles), called News on the crew side
// (/ship/news; the office keeps "Announcements"). Each person sees only the news items they are a recipient of, and
// only their own read state: never who else has or has not read them. One they did not receive is a 404. Opening one
// marks it read (no button). English only, as every crew screen.
@Controller
public class ShipAnnouncementController {

    static final String NEWS = "/ship/news";
    // Newest first: there is nothing to sort, but ListQuery needs its default.
    private static final ListSort[] SORTS = {ListSort.desc("sent")};
    /** The longest preview sent to the browser; CSS cuts it to one line. */
    static final int PREVIEW_LENGTH = 160;

    private final AnnouncementService announcementService;
    private final UserRepository userRepository;

    public ShipAnnouncementController(AnnouncementService announcementService, UserRepository userRepository) {
        this.announcementService = announcementService;
        this.userRepository = userRepository;
    }

    /** One row of the News list: the person's own recipient row and a one-line plain-text preview of the text. */
    public record NewsRow(AnnouncementRecipient recipient, String preview) {

        public boolean isUnread() {
            return recipient.getAcknowledgedAt() == null;
        }
    }

    // "New": everything not opened yet, never paged (it is what must be read). "Earlier": what was read, 10 a page
    // with the shared pager (listRequest: its page and page size), whose links come back to that heading. Each
    // newest first.
    @GetMapping(NEWS)
    public String list(@AuthenticationPrincipal CurrentUser currentUser, @ModelAttribute ListRequest listRequest,
                       Model model) {
        User user = userRepository.findByEmail(currentUser.getUsername()).orElseThrow();
        ListQuery list = ListQuery.of(NEWS, listRequest, SORTS).anchor("newsGroupEarlier");
        List<NewsRow> unreadRows = announcementService.newFor(currentUser.getUsername()).stream()
                .map(ShipAnnouncementController::row).toList();
        model.addAttribute("ship", user.getShip());
        model.addAttribute("list", list);
        model.addAttribute("unreadRows", unreadRows);
        model.addAttribute("readRows", announcementService.earlierFor(currentUser.getUsername(), list)
                .map(ShipAnnouncementController::row));
        model.addAttribute("unread", unreadRows.size());
        return "announcement/ship-list";
    }

    private static NewsRow row(AnnouncementRecipient recipient) {
        return new NewsRow(recipient, preview(recipient.getAnnouncement().getBody()));
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

    // The old /ship/announcements URLs (bookmarks, links in notifications and emails already sent) redirect for good.
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
