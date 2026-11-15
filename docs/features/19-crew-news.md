# 19. Crew news

Crew news (P27, any ship user): the crew read the announcements the office sends ([14](14-announcements.md)).
`announcement/ShipAnnouncementController`, `templates/announcement/ship-list.html` and `ship-detail.html`; English
only, as every crew screen. **News** sits between Home and Library in the sidebar and the phone tab bar,
with the number the person has not read yet (P37; "2 unread" for screen readers, none at zero).
- `/ship/news`: the news the signed-in person is a recipient of, the ones not read yet first (P37), each group
  newest first. Each row is one link (the whole row) with the title, the date sent (UTC) and its state: not read
  yet, a bold title, a dot and "New"; read, two ticks ("Read" for screen readers). The word carries the state, the
  weight and colour only repeat it. With none: "No news yet".
- `/ship/news/{id}`: All vessels or the ship (with its IMO number) as the eyebrow, the title, "Office ·
  <sender name>" (just "Office" when the sender was deleted) and the date and time sent in UTC, the message as plain text with its line breaks, in the reading column (48 rem
  wide at most), and under it the read receipt ("Read 13 Nov 2026 · 10:07 UTC" with two ticks).
- **Read by opening** (P39, asked by the crew: the button was one tap too many over a slow link): opening the item
  is reading it. The first time, the server sets the person's `acknowledgedAt` and records "Announcement read" in
  the audit log ([10](10-audit-log.md)) in the same request as the page (`AnnouncementService.open`); opening it again, in a second
  tab or after a resend over a bad link, changes nothing: same time, no new audit entry. There is no **I have read
  this** button and no `POST …/acknowledge` any more. Opening the item does not mark its notification as read
  (Notifications keep their own state, [13](13-notifications.md)).
- The old URLs `/ship/announcements` and `/ship/announcements/{id}`, answer 301 to `/ship/news` and
  `/ship/news/{id}` (bookmarks, links in older notifications and emails).
- Someone who is not a recipient (another ship, joined after it was sent) gets 404 on both URLs, records nothing,
  and never sees it in their list. The crew never see who else has or has not read it: only their own state.
- Unread news is on the Home page's To read ([18](18-crew-home.md)) until opened.
- To try it: as `office@example.com` send an announcement to MV Demo ([14](14-announcements.md)). As `cook.demo@example.com` /
  `crew123`: **News** has the counter; open the item. As the office, the announcement's page shows "1 of 2 read"
  with Demo Cook, the two ticks and the time under Read.
