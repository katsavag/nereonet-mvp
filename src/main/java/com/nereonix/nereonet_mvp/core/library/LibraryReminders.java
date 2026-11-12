package com.nereonix.nereonet_mvp.core.library;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// "Send reminder" on the acknowledgement overview (P22), for the crew (library) and the office (officelibrary)
// controllers: sends it and says what happened as a flash message on the overview.
public final class LibraryReminders {

    private LibraryReminders() {
    }

    public static void send(LibraryService libraryService, String actorEmail, LibraryItem item,
                            RedirectAttributes redirectAttributes) {
        try {
            int sent = libraryService.sendReminder(actorEmail, item);
            if (sent == 0) {
                redirectAttributes.addFlashAttribute("successMessage", "library.reminder.nobody");
            } else {
                redirectAttributes.addFlashAttribute("successMessage", "library.reminder.sent");
                redirectAttributes.addFlashAttribute("successArgs", new Object[] {sent});
            }
        } catch (ReminderTooSoonException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "library.reminder.tooSoon");
        }
    }
}
