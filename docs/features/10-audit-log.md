# 10. Audit log

Audit log (signed in as an office user): **Audit log** in the sidebar (`/office/audit`). Lists who did what and
when, newest first, 10 rows per page by default, 20, 50 or 100 on request (paged in the database), searchable (who
did it, by name or email, and the details), filterable by ship and date range (UTC days), sortable by when, who,
action and ship. Under the table "1 to 10 of 27" and **Previous** / **Next**; the pager, the page size and the sort
keep the search and the filters. **From** and **To** accept dates from 30 years ago to 1 year ahead (see the date
limits in 12); a date outside them is shown on the field with the limits, an impossible one (`from=2026-02-31`
in the URL) with "Enter a date.", and the list is not filtered by it. Recorded so far: ship added,
edited, deactivated, activated; invitation sent, resent, cancelled; crew imported (counts only); user registered, edited, deactivated, activated, deleted; password reset (the user, by their email; see [17](17-forgot-password.md));
item added, edited, deactivated, activated; items imported (counts only); certificate added, edited, deleted;
certificate file added, replaced, removed (P39, with the file name); certificate reminder sent (by "System");
announcement sent, announcement read (by the crew member, with the title, the first time they open it: P39, before
"announcement acknowledged"); order sent, delivery scheduled and delivery changed (port and delivery date; P51, before "order confirmed"),
receipt confirmed on board (P51: when, lines short, files), shortage handled (P51, with the note), orders exported
(counts and ids; before P36 "requests exported").
Entries are read-only.
