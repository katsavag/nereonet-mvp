package com.nereonix.nereonet_mvp.notification;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;

/**
 * /notifications (P23), for every logged-in user (SecurityConfig: any other URL requires login). Showing the list
 * marks nothing as read; a notification is read once the user opens it (POST .../open, which then goes to its
 * link) or presses its Mark as read, or Mark all as read. Mark as read and Mark all as read come back to the same
 * page of the list with a message; js/notifications.js sends them with Accept: application/json instead and gets the
 * new unread count, to update the page and the counters in place.
 */
@Controller
public class NotificationController {

    static final String PATH = "/notifications";
    private static final ListSort[] SORTS = { ListSort.desc("createdAt") };

    /** The unread count after an action, and the counters' words for screen readers in the page language. */
    public record Unread(long unread, String countLabel, String bellLabel) {
    }

    private final NotificationInboxService inboxService;
    private final MessageSource messageSource;

    public NotificationController(NotificationInboxService inboxService, MessageSource messageSource) {
        this.inboxService = inboxService;
        this.messageSource = messageSource;
    }

    // listRequest: the page and page size (25 or 50; layout/parts :: pager). The list has no search, filter or sort.
    @GetMapping(PATH)
    public String list(@AuthenticationPrincipal CurrentUser currentUser, @ModelAttribute ListRequest listRequest,
                       Model model) {
        ListQuery list = list(listRequest);
        Page<Notification> notifications = inboxService.page(currentUser.getUsername(), list);
        model.addAttribute("list", list);
        model.addAttribute("notifications", notifications);
        model.addAttribute("days", NotificationDay.of(notifications.getContent()));
        return "notification/list";
    }

    // Marks it as read and follows its link; without one (not offered on the page), back to the list.
    @PostMapping(PATH + "/{id}/open")
    public String open(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
        String link = inboxService.open(currentUser.getUsername(), id);
        return "redirect:" + (link == null ? PATH : link);
    }

    @PostMapping(PATH + "/{id}/read")
    public String markRead(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                           @ModelAttribute ListRequest listRequest, RedirectAttributes redirectAttributes) {
        inboxService.markRead(currentUser.getUsername(), id);
        redirectAttributes.addFlashAttribute("successMessage", "notification.markedRead");
        return "redirect:" + list(listRequest).getUrl();
    }

    @PostMapping(value = PATH + "/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Unread markReadInPlace(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
        inboxService.markRead(currentUser.getUsername(), id);
        return unread(currentUser);
    }

    @PostMapping(PATH + "/read-all")
    public String markAllRead(@AuthenticationPrincipal CurrentUser currentUser,
                              @ModelAttribute ListRequest listRequest, RedirectAttributes redirectAttributes) {
        inboxService.markAllRead(currentUser.getUsername());
        redirectAttributes.addFlashAttribute("successMessage", "notification.allMarkedRead");
        return "redirect:" + list(listRequest).getUrl();
    }

    @PostMapping(value = PATH + "/read-all", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Unread markAllReadInPlace(@AuthenticationPrincipal CurrentUser currentUser) {
        inboxService.markAllRead(currentUser.getUsername());
        return unread(currentUser);
    }

    private static ListQuery list(ListRequest listRequest) {
        return ListQuery.of(PATH, listRequest, SORTS);
    }

    // The same words as layout/layout.html gives the sidebar entry and the top bar's bell.
    private Unread unread(CurrentUser currentUser) {
        long unread = inboxService.countUnread(currentUser.getUsername());
        return new Unread(unread,
                messageSource.getMessage("nav.count.notifications", new Object[] { unread }, LocaleContextHolder.getLocale()),
                messageSource.getMessage("notification.bell", new Object[] { unread }, LocaleContextHolder.getLocale()));
    }
}
