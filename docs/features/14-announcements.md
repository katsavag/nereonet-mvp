# 14. Announcements

Announcements (signed in as an office user): **Announcements** in the sidebar (`/office/announcements`) lists every
announcement, newest first, with its target, date and e.g. "1 of 2 read" (P39: the crew read an announcement by
opening it, see [19](19-crew-news.md); before, "acknowledged" by a button). The target (P28) is "All ships",
the ship's name (a link) for one ship, or "3 ships" with the names as its title (and, for screen readers, as hidden
text; on the phone card they show under it). Filter by ship to see the announcements sent to all ships or with that
ship among their targets; search the title; sort by sent (the default, newest first), title and target (All ships,
then one ship by name, then several ships, fewest first); 10 per page, 20, 50 or 100 on request (paged in the database, the
acknowledgement counts read for that page only, and the target ships of the page with one more query).
A Required announcement has the neutral status "Required" after its title in the list and under the heading of its page.
**New announcement** asks for the target, a title, a plain-text message and "Must the crew read it?": **Required**
or **Not required** (P73, the default, as a Library item). Everyone is notified and reading is tracked either way;
only the Required ones are chased: the crew Home's To read, the Master's "News to read" card and the Overview's
Needs attention. `announcement.required` is a non-null boolean with the default true, so announcements sent before
P73 stay required. The review page shows the choice as "Must be read" and carries it in a hidden field. The target (P28) is one of two options,
nothing preselected: **All ships** ("The whole active fleet: 5 ships") or **Selected ships**, a list of the active
ships to tick (name, IMO number, type and flag). With JavaScript (`js/ship-picker.js`) the list shows only while
Selected ships is chosen and has quick filters by type and by flag (each only when the active ships have more than
one), a live "2 of 5 selected" (and how many of those the filters hide), **Select all** (ticks the ships shown) and
**Clear** (unticks every ship); without it every active ship is listed and the ticks are ignored for All ships. One
ship is Selected ships with one box ticked. Announcements cannot be edited or deleted, so sending takes two steps
(P10). Above the **Review** and **Send** buttons both steps say, as a quiet info note, "Nereonix is not an
emergency or safety channel. In an emergency, follow the ship's safety procedures." (FR-GEN-008, P16):
- the form's button is **Review** (`POST /office/announcements/review`): an invalid form shows its errors (Selected
  ships with no box ticked: "Tick at least one ship." on the target); a valid one shows the review page, which saves
  nothing: the target (as the eyebrow and as "Send to": All ships, "MV Demo · IMO 9074729" or "3 ships"), the
  chosen ships that were deactivated since the form was opened ("1 ship left out", named; they get nothing), the
  number of people per ship (every active ship for All ships, the active chosen ones otherwise, with a Total for
  more than one ship and a warning when nobody would receive it) and the title and message exactly as the crew
  will see them (plain text, line breaks kept). The page carries the form in hidden fields, with the active chosen
  ships only. With none of the chosen ships still active it is the form again, with "The ships you ticked are no
  longer active." on the target;
- **Back to edit** (`POST /office/announcements/new`) shows the form again with the text and ticks as they were. An
  old form page that still posts to that URL also just gets the form back: nothing is sent from it;
- **Send to 37 people on 3 ships** (`POST /office/announcements/send`) checks the form again, counts the recipients
  again and sends; the message says how many it went to ("Announcement sent to 38 people on 3 ships."). A chosen
  ship deactivated after the review page was shown sends nothing: the review page comes back naming it as left out,
  to send without it; with no chosen ship still active it is the form with the error on the target.

The recipients are fixed when it is sent: every active user with a ship role on one of the target ships
(on every active ship for All ships). Someone who joins the ship later is not a recipient; someone who leaves the
ship or is deactivated later stays in the list. Each recipient gets an in-app notification "Announcement · <title>"
(the subject is their ship) that opens the announcement on their own page ([19](19-crew-news.md)); there the eyebrow is All ships or
their own ship, never the other target ships. Opening an announcement shows its text, "1 of 3 read" under
the heading (with "3 vessels" as the eyebrow for several ships), **Read by vessel** (every target ship and
every ship of the recipients, by name, "1 of 2 read" each or "Nobody on board when it was sent", and the
Total for more than one ship), and two lists, both grouped by ship: **Read** (name, role, ship, and the read
receipt: two blue ticks, "Read" and the time) and **Not read yet** (one grey tick and "Not read yet"; the words
are always there, never the ticks alone: `layout/parts :: readReceipt`). Announcements cannot be edited or deleted. The audit entry "Announcement sent" lists the target
ship ids and names ("Port state control (ship ids 3, 7: MV Alpha, MV Beta; 12 recipients)"), and has the ship
only when there is one.

Data model (P28): `announcement.fleet_wide` (true for All ships) and the join table `announcement_ship` (the
target ships; none for All ships) replace the nullable `announcement.ship_id`. `AnnouncementRecipient.ship` stays
the ship each recipient was on. On startup, while `announcement.ship_id` still exists,
`core/announcement/AnnouncementTargetMigration` moves the rows in one transaction (ship_id set: one
`announcement_ship` row; null: `fleet_wide = true`) and then drops the column, so existing announcements keep their
target and it runs once.
