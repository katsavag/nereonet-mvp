# 18. Crew home page

Crew home page (P26, any ship user: Master, Chief cook, crew members): `/ship`, where a ship user lands after
signing in and where **Home** and the brand lead. Made for a phone first (390 px, one hand, a slow link, a shared
PC); crew screens are English only. `shiphome/ShipHomeController`, `ShipHomeService`, `templates/shiphome/home.html`
(it replaces the placeholder `login/ship.html`).
- **Header**: the ship's name and IMO number as the eyebrow, the person's name as the heading and their rank under
  it (the role when no rank is set), and before the name their profile photo or initials (P31, see [20](20-my-profile-and-my-crew.md)). P58: Home
  has no sign-out line of its own at any width: **Sign out** is in the sidebar user block from 768 px and under
  **More** in the tab bar on phones ([20](20-my-profile-and-my-crew.md)).
- **To read**: the Required library items of the person's ship that they have not acknowledged in the current
  version and the news they have not opened yet (P27, P39, see [19](19-crew-news.md)), together, oldest first, with their number
  after the heading. Each is a full-width card (at least 56 px) with the type icon (the announcements icon for an
  announcement), the type ("Library" or "News item"), the title and "Published 12 Nov 2026" or "Sent 12 Nov
  2026" (UTC date), and opens the item (where **I have read this** is) or the news item (read by opening it). Retired items and items of
  other ships never show. A new version puts the item back on the list. Nothing left: "You are up to date."
  **All news** and **Open the Library** follow the list.
- **My certificates**: the person's own certificates, soonest expiry first (so expired, then expiring soon, then
  valid): type, "Expires 3 Nov 2026 · expired 8 days ago", the number when set, and the expiry badge of the office
  lists (Expired, 30 / 60 / 90 days, Valid; words and icon, not colour alone). Read only, with the note "Contact the
  office to update a certificate." Without any: "No certificates on record." (`crew/parts :: myCertificates`,
  shared with My profile, 20, which adds the file links.) P42 (see [26](26-required-documents.md)): when required documents are missing or
  expired, one line above them, "2 required documents missing", links to My profile; the Master's home has it too.
- **Crew acknowledgements** (the Master only): the Required items that not everyone on board has acknowledged in
  the current version, oldest first, each with "1 of 3 acknowledged" and its bar, linking to the item's
  acknowledgement overview ([16](16-ship-library.md)). When everyone has: "Everyone on board has acknowledged every required document."
- **Stores** (P52, the Chief cook only, see [30](30-stores.md)): above To read, one card linking to `/ship/stores` with the number
  of provisions Low and Out of stock ("2 low · 1 out of stock"), tinted while either is above zero.
- From 992 px To read (and the Master's card) and the certificates sit side by side.
- **Navigation for the crew on phones** (below 768 px): a tab bar fixed at the bottom of the screen (`layout/parts
  :: tabBar`) with four tabs per role since P65, Home first and **More** last: the Master **Home**, **Approvals**
  and **Vessel order**; the Chief cook **Home**, **Stores** and **Requests**; crew members **Home**, **News** (P27;
  "Announcements" before P39) and **Library**. Each tab's counter is a badge on its icon, in words for screen
  readers; each is a 56 px target, the active one navy and semibold with a bar on its top edge and
  `aria-current="page"`. **More** (`/ship/more`, see [20](20-my-profile-and-my-crew.md)) lists Notifications and My profile, then whatever is not a
  tab under the sidebar's headings **On board** and **Supplies** (News and Library for the Master and the Chief
  cook, My crew for everyone, Vessel order for the Chief cook, Requests for crew members), each with its counter;
  the More tab's badge adds them up with the unread notifications (kept up to date by `js/notifications.js` after
  Mark as read), and More is the active tab on their pages. One place decides the entries per role:
  `navigation/ShipNav` (model attribute `shipNav`, with `navTab` from `NavigationAdvice`). It replaces the
  offcanvas menu and the bell for ship users; office users keep both. From 768 px the sidebar has every entry (P65):
  **On board** Home, News, Library and My crew; **Supplies**, in the order of the work, Requests (Chief cook, crew
  members, see [31](31-requests.md)), Approvals (Master, [21](21-approvals.md)), Vessel order (Master, Chief cook, [23](23-ship-order.md)) and Stores (Chief cook, [30](30-stores.md));
  **Account** My profile and Notifications. The page keeps room for the bar, and the offline banner and the pinned
  **I have read this** sit above it.
- Home has no card for what a tab already counts (P65): no **Requests to approve** for the Master (the Approvals tab) and no **My requests** for the Chief cook (the Requests tab; crew members keep the card).
- The page loads no script of its own (only the layout's shared files) and no images, except the person's own 96 px photo when they have added one (about 2 KB, cached for a year). Measured with the dev data
  as `cook.demo@example.com` (Chrome, 390 px): the HTML is 19.6 KB; with an empty cache the whole page transfers
  569 KB (Bootstrap CSS 232 KB and JS 81 KB, `app.css` 126 KB, the shared scripts 61 KB, the Latin font subset
  49 KB). Those files carry a content hash and are cached for a year, so every later visit transfers only the
  HTML. The app does not compress responses, so these are uncompressed sizes.
- To try it: sign in as `cook.demo@example.com` / `crew123`: "Drug and alcohol policy" is under To read; open it,
  press **I have read this**, then **Home**: "You are up to date.". As `master.demo@example.com` the Crew
  acknowledgements card lists it with its count.
