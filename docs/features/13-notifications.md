# 13. Notifications

Notifications (any signed-in user): the bell in the top bar (phones) and **Notifications** in the sidebar (a pill,
see "sidebar navigation" below) show the number of unread notifications. `/notifications` lists them, newest first,
grouped by UTC day (**Today**, **Yesterday**, then the date), 10 to a page (20, 50 or 100, with the usual pager). Each shows a
type icon (certificate, library, announcement; the bell for notifications saved before P23), the ship or person it
concerns ("MV Demo", "Jane Doe · MV Demo"), its text and the time in UTC ("12:40 UTC").
Notifications stay until handled (P23):
- showing the list marks nothing as read; leaving it keeps the unread count;
- a notification is read once it is opened: its text is a POST (`/notifications/{id}/open`) that marks it and goes
  to its link (a library item, for example). A notification without a link (the certificate reminder) has only
  **Mark as read** (`/notifications/{id}/read`). **Mark all as read** (`/notifications/read-all`) is in the page header
  while anything is unread (P43 asked for it in the bell's menu too; the bell is a plain link to this page, there is
  no menu, so the page header is its one place). Each comes back to the same page of the list with "Notification marked as read." or
  "All notifications marked as read.";
- unread ones have a dot and bold text ("Unread:" for screen readers); read ones are regular weight, no dot, and
  their text is a plain link;
- with JavaScript (`js/notifications.js`) Mark as read and Mark all as read do not reload the page: they are sent
  with `Accept: application/json`, the answer is the new count, and the row, the sidebar pill and the top bar's
  bell change in place (offline, or on any other answer, the form is sent the ordinary way). Back to the list after
  opening a notification reloads it, so the one just opened no longer shows as unread;
- only the user's own notifications (someone else's id gives 404). Reading a notification is not recorded in the
  audit log: it is the user's own bookkeeping, not a change to shared records.
`notification.type` and `notification.subject` are new nullable columns (Hibernate adds them on start).
