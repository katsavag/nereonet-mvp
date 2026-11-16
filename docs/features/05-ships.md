# 5. Ships

Ships (signed in as an office user): **Vessels** in the sidebar (`/office/ships`).
Wording (P39): every English text on screen and in emails says "vessel", not "ship" ("Add vessel", "All
vessels"; `MessageKeysTest` checks it), except proper names: the certificate type "Ship Security Officer" and the
crew template's column "Ship IMO" (a file format). The code, URLs, database tables, export column names and these docs keep
"ship"; the Greek texts are unchanged. Below, the buttons are named with "ship" as in the code; the screens say "vessel". **Add ship** (a pop-up form) asks for
the IMO number (7 digits with a valid check digit, e.g. `9074729`; `IMO 9074729` also works), name,
type and flag (type to filter the countries, e.g. "Greece (GR)"; only the code `GR` is stored). Ships can be
edited in a pop-up (except the IMO number) and deactivated or activated, never deleted: on the list both are in the
row's **⋯** menu (**Edit**, then **Activate** or **Deactivate**). The ship's name opens its ship page.
P40: a **Safety Score** column (the RightShip score, "3/5", "N/A" when not rated; for an active vessel a link to its
Vetting page, see [25](25-vetting.md)). The list sorts by name (default) or by Safety Score (`?sort=score`, lowest first, then the
other way; not rated last either way, then by name) and shows 10 vessels a page with the shared pager (P89); it has no
search or filters.
P43: the **Flag** column shows the country's flag as a small icon (20 × 15, `layout/parts :: flag`, the SVGs under
`static/vendor/flags/`, see "Frontend assets") before the country name; the same icon with the name appears on
the ship page header and particulars, the Vetting list, the library's particulars ([16](16-ship-library.md)) and, for the crew's
nationality, My profile, My crew and the office's crew member page.
Inactive ships stay in the list but cannot get new crew invitations. **Deactivate** asks first ("Deactivate MV Demo?
No new crew can be invited to MV Demo until it is activated again.", see "confirmations (P10)" below); **Activate**
does not.

**Ship page** (`/office/ships/{id}`, P08): one place per ship. Every tab starts with the same ship header:
breadcrumbs (Ships › MV Demo), the ship's name as the heading, then IMO number, type, flag and an Active / Inactive
badge (P40: and, for an active vessel, a row of three compact badges, Safety Score, GHG and inspection; there is
no Vetting tab; P41: a tanker also gets a SIRE 2.0 badge, "Report current until …"; P43: each badge is its own
link, the first three to the vessel's RightShip page and the SIRE badge to its SIRE 2.0 page, with "opens
RightShip" / "opens SIRE 2.0" for screen readers, and the flag is shown as a small icon before the country name,
`layout/parts :: country`), with **Edit** (the same pop-up as on the list; without JavaScript it opens `/office/ships/{id}/edit`, the
same form as a page) and a **⋯** menu (**Activate** or **Deactivate**). Edit, Activate and Deactivate come back to
the tab they were started on. Under the header a tab bar of ordinary links, one URL each, so every tab can be
bookmarked; each shows a count:
- **Overview** (`/office/ships/{id}`): the ship's open items, with the fleet Overview's rule (expired
  certificates, certificates expiring within 30 days, orders sent and not exported), each listed with a link;
  the crew on board (enabled users) by the department of their rank (Deck, Engine, Galley, or "Rank not set");
  and the particulars (name, IMO number, type, flag and the first lines of the particulars text, with **Edit ship
  particulars** and a link to the library). The tab's count is the number of open items, in red when above zero.
- **Crew** (`/crew`) and **Announcements** (`/announcements`): the fleet-wide lists of 8 and 14 for this ship
  only, without the ship filter and the Ship column. The fleet-wide pages keep their ship filter. (The Requests
  tab went with the request export in P36; a ship's orders are on **Orders** filtered to it, 24.)
- **Certificates** (`/certificates`): the certificate matrix of the crew on board, or (**List**,
  `/certificates/list`) the Certificates list of 12 for this ship only; see 12.
- **Library** (`/library`, see [16](16-ship-library.md)) and **Items** (`/items`, see [11](11-galley-items.md)): the existing pages, now inside the frame.
Actions started on a tab keep the ship and return to the tab: **Add crew member** opens the same pop-up form as
on the Crew page with this ship preselected (it can still be changed; once sent, the user is on the Crew tab of the
ship the invitation is for; the former page `/office/ships/{id}/invitations/new` now redirects to the tab with the
form open, `?add=true`); an invitation's **Resend** and **Cancel invitation** return to the tab; **Add certificate** (`/certificates/new`) picks the
crew member from this ship's active crew only; **New announcement** opens the form with **Selected ships** chosen
and this ship ticked (the target can still be changed) and returns to the tab once sent; a crew member's **⋯** menu returns to the Crew tab.
The counts: Crew, every user on the ship and its pending invitations; Certificates, expired or expiring within 90
days (what the tab's List view shows); Library, items not retired; Items, all; Announcements, sent to all ships or
with this ship among their targets (P28); Requests, approved.

An inactive ship shows a banner on every tab: crew cannot be invited to it or moved to it, it gets no
announcements and it is not on the Overview; its records stay as they are; **Activate** in the **⋯** menu undoes it.
Its Crew and Announcements tabs offer no **Add crew member** / **New announcement**.
On phones the tab bar scrolls sideways and starts with the active tab in view (`js/ship-tabs.js`; without it the bar
still scrolls). Every ship name in the office (lists, the Overview cards, the audit log, the eyebrow of ship-scoped
pages) links to the ship page. An unknown id gives 404 on every tab.
