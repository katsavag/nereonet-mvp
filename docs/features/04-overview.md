# 4. Overview

**Overview** (`/office`, the office home page, P09; see [27](27-snoozing.md)) is a work list: what needs the
office, with the next step on each row, so it can be cleared without visiting other pages first. The page action
is **New announcement**. Under the title four stat tiles, each one link with a detail line: **Active vessels**
("2 with open items", to Vessels), **Crew on board** (enabled crew of active vessels; "1 invitation waiting", to
Crew filtered to Active), **Items needing attention** ("3 snoozed", to the list below) and **Pending orders**
(sent and the delivery not scheduled yet; "1 not exported yet", counting only the Not exported items, to Orders
filtered to Pending). From 1200px the tiles are
a row of four and the page has two columns: **Needs attention** on the left (7/12) and, stacked on the right,
**Fleet** and **Recent activity**; from 768px the tiles are 2 × 2 and the columns go under each other; on phones one
column, the tiles two per row.
- **Needs attention**: one row per open item of an active ship, the total in the card header, most urgent first:
  1. certificate expired; then (P40, P41, see [25](25-vetting.md)) a vessel's vetting: SIRE 2.0 operator comments overdue or due
  within 3 days, RightShip inspection expired, unacceptable or expiring within 60 days, SIRE 2.0 report no longer
  current or ending within 60 days, a SIRE 2.0 observation past its target date, and a RightShip Safety Score of 2
  or below, each with **Open RightShip** or **Open SIRE 2.0**; (P42, see [26](26-required-documents.md)) right after the expired certificates,
  one row per seafarer on board with required documents missing or expired ("2 required documents missing", the
  types, **Open crew member**);
  2. certificate expiring within 30 days (UTC dates); 3. order sent by a ship and not
  exported (P36: SENT, CONFIRMED or CLOSED, `exportedAt` empty); then (P44, see [28](28-suppliers-and-quotes.md)) an open request for quotes
  with quotes from two suppliers ("2 quotes ready to compare", **Compare quotes**) or past its reply-by date with
  fewer ("Reply-by date passed", **Open request**);
  4. crew invitation expired, or sent (last) more than 48 hours ago and not accepted (worked out from its expiry
  date and `app.invitation.valid-days`); 5. Required announcement sent (P73), or Required library item published (current
  version), more than 7 days ago that not everyone has read. Within a kind, the most overdue or oldest first.
  Each row has a status badge with an icon and the kind, a title linking to the record (the certificate, the order,
  the ship's crew list for an invitation, the announcement, the item's acknowledgements), one context line
  (ship · person and rank, or "2 of 5 not read" · date) and one action: **Open certificate**, **Open order**
  (the order page, where the delivery is scheduled and the order exported, see [24](24-office-orders.md); or where a shortage is marked
  handled, P51), **Resend invitation** (new link and expiry date, back to the Overview with "Invitation sent again") or **View who
  has not read it**. A certificate or vetting row also has a checkbox and **Snooze** (P43, see [27](27-snoozing.md)); snoozed
  items are left out. The list is paged, 10 rows at a time (20, 50 or 100, the P19 pager; `?page=2`), and sorted
  most urgent first or, with **By date** (`?sort=date`), by the date that drives each row, earliest first. Above
  the rows a line of filter chips, **All** and one per category with its count (Certificates, Required documents,
  Vetting, Orders, Quotes, Invitations, Announcements): links (`/office?type=certificates`, `documents`, `vetting`,
  `orders`, `quotes`, `invitations`, `acknowledgements`), so they work without JavaScript; the **Fleet** card's counts
  filter the list to one vessel (`/office?shipId=…`, "Showing only: MV Demo", with **Show all open items**; a chip
  keeps the vessel). **Open certificate** opens the form, which comes back to this page as it was (page, sort,
  filters) after Save or Cancel. With nothing open the card says "Nothing needs your attention" and when it was
  checked; a chip with nothing says "Nothing needs your attention here".
  With a chip selected, the card header links to that category's full list, **Open the full list**: certificates to
  `/office/certificates?status=expired,30&activeShips=true` (exactly the certificates counted, see [12](12-certificates.md)), vetting to
  `/office/vetting?status=attention`, required documents to `/office/crew?status=active&documents=missing`
  (`AttentionType.Group.listUrl`). Orders, quotes, invitations and announcements have no full list, so no link.
- **Fleet**: one row per active ship, those with open items first, then by name, at most 8 ("The first 8 of 12
  vessels; those with open items first" under the table, with **All vessels** in the card header): the flag and
  the name (the ship page) with the type under it, the RightShip Safety Score (to its Vetting page, see [25](25-vetting.md)) and the
  ship's open items, a link to this page filtered to the vessel; a zero is a muted dash and not a link.
- **Recent activity**: the last 5 audit entries (who, what, which vessel, the time in UTC), with **Audit log** in the
  card header.
- `DashboardService.load()` always runs the same 13 queries, whatever the size of the fleet (the active ships with
  their crew count, one per kind of open item, found by `dashboard/NeedsAttention` with the queries in `dashboard/DashboardQueries`, SIRE 2.0 with two queries in `SireService.attention`, the required documents' rules (P42; two more while
  any are set), the active snoozes and the newest audit entries); `DashboardFlowTest` checks it. The load also
  ends the snoozes that no longer hold ([27](27-snoozing.md)).
With the dev data an order the Master has sent is a row until the office exports it ([24](24-office-orders.md)); then the card says
"Nothing needs your attention".
- **Set up Nereonet** (first run, P25): a checklist of four steps, each with one line of help and a link to the page
  where it is done: 1. **Add a ship** (Ships), 2. **Add or import its crew** (Crew), 3. **Add the ship's first library
  document** (the first ship's Library tab; Ships while there is none), 4. **Send a first announcement**
  (Announcements). A step shows as done (a tick and "Done") when its data exists: any ship; any user with a ship role
  or pending crew invitation; any library item not retired; any announcement. The header says "2 of 4 done". The
  first step not done is `aria-current="step"` and its link is the page's one primary button ("Go to Ships"), so
  the header's **New announcement** waits until the checklist is done. While there is no ship or no crew the
  checklist takes the place of the work list (Needs attention and Fleet: there is nothing to work on yet); after
  that it stays above it, and it disappears once all four steps are done. With the dev data (ship, crew and
  library, no announcement) it shows 3 of 4 above the work list until an announcement is sent.
  `DashboardService.setup()` is one query (`DashboardQueries.setup()`), outside `load()`'s 13.
