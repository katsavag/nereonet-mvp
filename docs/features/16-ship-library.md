# 16. Ship library

Ship library (signed in as a ship user, e.g. `master.demo@example.com` / `crew123`): **Library** in the sidebar
(`/ship/library`) shows the ship particulars (name, IMO number, flag and the particulars text) and the library
items of the user's own ship that are not retired, grouped by category (Ship rules, Company policies, Ship plans and
maps, Onboarding guides, Other). Items of another ship, retired items and unknown ids give 404. Particulars
cannot be edited from the app yet (dev data only). Library files are stored in
`app.library.storage-dir` (dev: `./data/library`).

**Reading (P22)**, the same for the crew and the office:
- Each list row shows a type icon (a page marked PDF, DOCX or XLSX, or lines for a text item; the word says the
  type, the colour only repeats it), the title, "Version 3 · 12 Oct 2026" (the date published, as a UTC date; the
  viewer's own time is the tooltip), and for a Required item a badge: "Required · to acknowledge" / "Required ·
  acknowledged" for the crew, "Required" for the office. The title is the link and the whole row is its click
  area. For the Master and the office a Required row also shows "12 of 20 acknowledged" over a thin bar (green
  once everyone has), a link of its own to the acknowledgement overview.
- Opening an item shows its text, or one file row: type icon, file name and size, then the actions. A PDF has
  **Open PDF**, which opens it in the browser in a new tab (`Content-Disposition: inline`), so the item stays in
  the app's tab and Back still works there, and **Download** second (`?download=true`, `attachment`). DOCX and
  XLSX have **Download** only and are always sent as `attachment`. Crew: `/ship/library/{id}/file`; office:
  `/office/ships/{shipId}/library/{id}/file`.
- Office users open an item from its title on the ship's **Library** tab: the read view
  `/office/ships/{shipId}/library/{id}`, the crew's view without **I have read this**. Its actions are
  **New version** and **Retire**.
- The Master and the office see the item's **Version history** under it, newest first and read only: version label
  ("Current" on the one the crew see), when it was published (UTC) and by whom ("by a deleted user" when that user
  no longer exists). Chief cook and crew members do not see it.

**Add an item**: the Master has the button on **Library** (`/ship/library/new`; Chief cook and crew members get
403). Office users open the **Library** tab of a ship page (`/office/ships/{id}/library`, same content as the crew
page, see [5](05-ships.md)) and add items there
(`/office/ships/{id}/library/new`). The form asks for title, category, version label (e.g. `1` or `2026-10`),
Required / Not required, and the content: text **or** one file (PDF, DOCX or XLSX, at most 10 MB), not both.
Under the file input (here and on **Publish a new version**) an info note says "Do not upload security-sensitive
material such as the Ship Security Plan." (FR-SIN-002, P16).
A ship can have at most 50 items that are not retired. The item is visible to the crew at once. A Required item
gives every other enabled user of that ship the notification "Library · New document to read", which opens
the item. To try it: sign in as `master.demo@example.com`, add a Required text item, then sign in as
`cook.demo@example.com` and check **Notifications**.

**Acknowledge a Required item** (Master, Chief cook, crew members): on **Library** each Required item shows
"Required · to acknowledge" or "Required · acknowledged" (for the user's acknowledgement of its *current*
version). Opening a Required item not yet acknowledged shows **I have read this** after the content (and the
file's actions step down to secondary, so it is the one primary button); on phones (below 576px) it stays pinned
to the bottom of the screen while the content scrolls and comes to rest right after it (P22; offline it unpins, so
the offline banner never covers it). Pressing it
(`POST /ship/library/{id}/acknowledge`) saves the acknowledgement, records it in the audit log and shows
"Acknowledged · Version <label> · <date and time>" instead. Pressing it again changes nothing. Acknowledging never
blocks anything else. To try it: sign in as `cook.demo@example.com`, open **Library**, open
"Drug and alcohol policy" and press **I have read this**, then go back to **Library**.

**Publish a new version**: the Master sees **New version** on an item of their ship
(`/ship/library/{id}/edit`; Chief cook and crew members get 403); office users have **New version** on the
item's read view (`/office/ships/{shipId}/library/{id}/edit`; Cancel and Publish return to the read view). The form shows the title, category and
current version read-only, the current text pre-filled (or the current file name), and asks for a new version
label (required, different from the current one) and new text **or** a new file (same rules as Add an item).
**Publish** makes it the version the crew see and records it in the audit log. Earlier versions and
their acknowledgements stay in the database. Nobody has acknowledged the new version, so a Required item shows
"Required · to acknowledge" again and the ship's users get "Library · New document to read" again. To try it:
as `cook.demo@example.com` acknowledge "Drug and alcohol policy"; as `master.demo@example.com` open it, press
**New version**, enter a new version label and press **Publish**; sign in as the cook again.

**Retire an item**: the Master sees **Retire** on an item of their ship (Chief cook and crew members
do not; `POST /ship/library/{id}/retire` gives them 403); office users have it on the item's read view
(`POST /office/ships/{shipId}/library/{id}/retire`; the plain confirmation page's Cancel returns there). The confirmation dialog (P10) asks
"Retire Galley rules? The crew will no longer see Galley rules, and it cannot be brought back." **Retire** hides the item from every list, its page and file
give 404, it no longer counts towards the 50 items, and it is recorded in the audit log. There is no way back.
The item stays in the database with its versions and acknowledgements. To try it: as `master.demo@example.com`
open an item, press **Retire**, then **Retire** in the dialog; it is gone from **Library**.

**Acknowledgement overview** (Master of the ship and office users, read only; Chief cook and crew members do not
see it and get 403): on **Library** each Required item shows e.g. "1 of 3 acknowledged" with its bar, linking to
`/ship/library/{id}/acknowledgements` (Master) or `/office/ships/{shipId}/library/{id}/acknowledgements`
(office). The page lists, for the *current* version, who acknowledged it (full name, role, date and time) and who
has not yet (full name, role). Only enabled users with a ship role who are on that ship now are counted: someone
who left the ship is not listed, even if they acknowledged. To try it: as `cook.demo@example.com` acknowledge
"Drug and alcohol policy"; as `master.demo@example.com` open **Library** and click the count next to it.
The office's breadcrumbs now go through the item (Ships › MV Demo › Library › the item › Acknowledgements), as the
crew's already did.

**Send reminder** (P22, on the overview, Master and office): next to "Not yet acknowledged (3)". It asks first (a
neutral confirmation, P10: a notification cannot be taken back; without JavaScript the plain confirmation page at
`GET …/acknowledgements/remind`), then `POST /ship/library/{id}/acknowledgements/remind` or
`/office/ships/{shipId}/library/{id}/acknowledgements/remind` gives everyone listed as not yet acknowledged (except
the sender) the in-app notification "Library · Reminder: document to read", which opens the item, and records
`LIBRARY_ACKNOWLEDGEMENT_REMINDER_SENT` in the audit log ("Drug and alcohol policy", version 2, 3 people). It
comes back to the overview with "Reminder sent to 3 people.". At most once per 24 hours per item
(`LibraryItem.lastReminderAt`, a new nullable column): until then the button is replaced by "Reminder sent. The next
one can be sent after 13 Nov 2026 · 09:45 UTC", and a second POST is refused ("A reminder about this item was sent
less than 24 hours ago."). With everyone acknowledged there is no button.
