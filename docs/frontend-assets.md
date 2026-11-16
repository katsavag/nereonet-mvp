# Frontend assets

The app makes no request to any other host: everything a page needs is served from
`src/main/resources/static/`.

The server itself calls a few external hosts, all optional and off until configured, and never from the browser:
RightShip (P40, `RIGHTSHIP_BASE_URL`, see [25](features/25-vetting.md)) and VesselAPI (P45, `https://api.vesselapi.com/v1`, only
while `VESSELAPI_API_KEY` is set, and only when a user presses **Look up by IMO** or **Refresh from VesselAPI**, see
29), and for counting the store by voice AssemblyAI (P57, `https://api.assemblyai.com`, the recording) and Claude
(P69, `https://api.anthropic.com`, the text and the vessel's provisions), only while both `ASSEMBLYAI_API_KEY` and
`ANTHROPIC_API_KEY` are set, and only when the Chief cook sends a recording (see [34](features/34-voice-count.md)). The photographed delivery invoice is read by Claude too (P70, `https://api.anthropic.com`, only the page's image or PDF), only while `ANTHROPIC_API_KEY` is set, and only when the ship presses **Done: start counting** or **Read again** (see [37](features/37-invoice-photo.md)). (The VesselFinder map of the ship
page is a separate experiment, `SHIP_MAP_ENABLED`.)

- `vendor/bootstrap/`: Bootstrap 5.3.8 (`bootstrap.min.css`, `bootstrap.bundle.min.js`), unchanged from the
  npm package, with its `LICENSE`. To upgrade, replace both files with the ones from the new release's `dist/`.
- `vendor/flags/` (P43): one SVG per country (`gr.svg`, 4 × 3, 249 files) from the npm package `flag-icons` 7.6.0,
  unchanged, with its `LICENSE` (MIT). `ship/Flags` (`@flags`) lists them once at start; `layout/parts :: flag(code)`
  renders `<img src="/vendor/flags/gr.svg" width="20" height="15" alt="">` (nothing for a code without a file) and
  `country(code, label)` the icon with the name. To upgrade, replace the folder with the new release's `flags/4x3/`.
- `fonts/`: the Inter variable font (weights 100–900, upright), Latin and Greek subsets as WOFF2, declared with
  `@font-face` in `css/app.css` (`font-display: swap`, system fonts as fallback). Source: the npm package
  `@fontsource-variable/inter` 5.3.0, files `inter-latin-wght-normal.woff2` and `inter-greek-wght-normal.woff2`.
  Licence: `fonts/LICENSE-Inter.txt` (SIL OFL 1.1).
- `img/brand/` (P29), the Nereonix brand: `favicon-32.png` (the browser tab) and `apple-touch-icon.png` (180 px, home
  screen), both linked from `layout/layout.html`; `nereonix-mark-192.png`, the mark shown at 32 px by
  `layout/parts :: brand` (mark, "Nereonix" as text, optional tagline; in the sidebar, the sign-in panel and its
  phone bar, and the crew home) with `alt=""` since the name is text next to it. The PNG is square with empty space
  around the drawing; on the sign-in panel (80 px box, P30) negative margins take that space out, so the drawing sits
  next to the name. `nereonix-logo-on-dark-600.png` (600×422), the inline logo of the emails (no page uses it); `nereonix-mark-512.png`, the large mark, kept as source and not linked. Every `<img>` has its
  `width` and `height`. Replace a file under the same name; the content hash changes its URL.
- `css/app.css`: the only custom stylesheet. It defines the design tokens (`--nn-*` CSS variables) once in `:root`
  and maps Bootstrap's variables and components onto them; templates carry no inline styles. The comment at the
  top of the file lists the scales:
  - surfaces, three levels: page `#f4f1ea`, surface `#fffdf8` (cards, modals, tables, list groups, menus),
    field `#ffffff` (inputs, selects, checkboxes, radios; border at least 3:1 against the surface);
  - header surface (P58), `--nn-head-bg` `#ece7dc`: card headers (Bootstrap `--bs-card-cap-bg`, with a 1 px
    `--nn-divider` line under them and the title in `--nn-text`) and table headers (`.table > thead`, also when
    sticky), a visible step from the surface (1.21:1) with text at 13.3:1, secondary text 6.0:1 and links 7.5:1.
    Card headers do not use `bg-transparent` (`DesignTokensTest`);
  - one blue, the brand navy `--nn-blue`, for primary buttons, outline buttons, links, checked boxes and the focus
    ring (a 2 px ring with a 2 px gap on every focusable element; white on navy backgrounds);
  - status pairs (subtle background + text, contrast at least 4.5:1) for ok, warning, danger and info, used by
    `bg-*-subtle`, `text-*-emphasis`, `text-bg-*`, `alert-*` and `list-group-item-*`;
  - radius 6 px badges (never pills), 8 px buttons and fields, 12 px cards, 16 px modals;
  - type h1 28 px, card title 18 px, body 16 px, tables and form labels 14 px, help text 13 px, eyebrow 12 px; `.num` gives
    tabular figures for IMO numbers, dates, counts and quantities;
  - spacing 4 / 8 / 12 / 16 / 24 / 32 px: 24 px between page sections, 16 px inside cards, card padding 20 px;
  - buttons, three sizes: 40 px by default (page header actions, form actions, filters, modal footers), `btn-sm`
    32 px only in table rows, list rows and card toolbars, `btn-lg` 48 px only for the full-width buttons on the
    sign-in, registration and error pages. Every button can be tapped over at least
    40 px (44 px on phones), also the small ones. Four levels: `btn-primary` (filled navy, at most one per view and
    one per modal), `btn-outline-secondary` (secondary: surface, visible border, navy text), `btn-ghost`
    (tertiary: navy text only; low-priority and row actions) and `btn-outline-danger` (only in a confirmation dialog,
    on a confirmation page or in a row's overflow menu, never directly in a row). Filter bars apply on change (see
    "lists: search, sort and pages (P19)"); without JavaScript they show a secondary **Apply**, and **Clear all** is a
    tertiary link. Disabled buttons are 50% opaque with a not-allowed cursor; the reason is in the text next to them;
  - page header actions sit in `.page-actions` (the `actions` slot of `layout/parts :: pageHeader`) with a 16 px icon
    before the label (`layout/parts :: icon`: plus, upload, download); on phones they stack full width;
  - lists (P04): one table style, `.data-table`, on every office list (ships, crew, office users, invitations,
    certificates, announcements, requests, galley items, audit log). Cells have 12 px top and bottom padding and
    the first and last cell start and end on the card padding, so the card title, the column headers and the
    first column share one left edge. Every row is at least 52 px high, with or without actions, and every cell
    stays on one line so all rows have the same height. Header cells are 12 px, 600, secondary colour, uppercase,
    letter-spacing 0.04em, in `--nn-text` on the header surface (P58). Rows get a hover background; counts (`.num`)
    are right-aligned; short codes such as a currency (`.code-cell`, P58) are centred, tabular and on one line,
    header included (`layout/parts :: sortHeaderCell(list, key, label, cellClass)`). The record's name
    (`.cell-primary`) links to its page and is the only bold cell; a ship's name opens its ship page (P08). The record
    count sits after the page title, muted ("Crew · 84", see the page header below); on the tabs of the ship page it is
    on the tab instead; list cards have no header of their own. Email addresses never break: they are cut with an ellipsis and the
    full address is in the title attribute (`layout/parts :: email`). Status badges are a dot plus text in the
    status colours (`layout/parts :: status`, tones ok, warning, danger, info, primary, neutral);
  - lists: search, sort and pages (P19): every office list (Crew, Office users, Certificates, Items, Requests,
    Announcements, Audit log, and the same lists in the ship tabs) has one filter bar (`layout/parts :: filterBar`:
    search field, the list's filters, `shipFilter` for the ship). It is a GET form, so the whole state is in the URL
    (`?q=…&shipId=…&status=…&sort=…&dir=…&size=…&page=…`, in that order, only what is set) and every list URL reloads
    to the same result and can be bookmarked or linked to. With `static/js/list-filter.js` a filter applies as soon as
    it changes and the search on Enter or after a 400 ms pause; focus comes back to the field after the reload.
    Without JavaScript an **Apply** button is shown. Active filters are chips (`list.search.chip` first), each with a
    remove link, and **Clear all** (it keeps the sort and page size). Sortable column headers are links
    (`layout/parts :: sortHeader`) with `aria-sort` and a direction arrow: the first click sorts the column
    (ascending, dates newest first), the next one reverses it. The server sorts; an unknown sort key falls back to the
    list's default. Pages: 10 rows (P43; 25 before, 20 on the audit log), or 20, 50 or 100 (`size=50`), with "1 to 10 of 412",
    **Previous** and **Next** (`layout/parts :: pager`, shown when there is more than one page of 10; a disabled link has
    no `href`); changing
    the search, a filter, the sort or the size goes back to page 1, a page past the end shows the last page. Every
    list is counted and paged in the database (`core/list/ListQueries`: a JPA Criteria count plus a `LIMIT`/`OFFSET`
    query with the id as tie-breaker; Crew and Office users: a native `UNION ALL` of users and pending invitations in
    `crew/PeopleQueries`). The list state is read by `core/list/ListQuery` (from the `ListRequest` record), which
    also builds every link. From 1200 px the table header stays visible while the page scrolls (`.table-sticky`; below
    that wide tables scroll sideways and phone lists are stacked cards). A filtered list with no rows says "No results
    for these filters" with **Clear all** (`layout/parts :: listEmpty`), not the list's empty state (P25, below);
  - empty states (P25): a list with nothing in it yet (no search or filter set, `ListQuery.emptyUnfiltered(rows)`)
    shows `layout/parts :: emptyState`, centred in its card: the list's icon (the sidebar icon, larger, in a circle in
    the primary pair), a one-line title, one sentence saying what the list is for and what to do first, and the list's
    first action as a button. The action is the one the page header (or the ship tab's heading) had, which the header
    then leaves out, so the view keeps one primary button. Where the first step is done elsewhere the action leads
    there (`layout/parts :: emptyLink`). The filtered-empty line stays as it was: left aligned, no icon, **Clear all**.
    Per list: Ships "Add your first ship to invite its crew." (**Add ship**); Crew, Add crew member and Import from
    file, or without an active ship "Crew members belong to a ship..." (**Go to Ships**); a ship's Crew tab (Add crew
    member; nothing on an inactive ship); Office users (Invite office user; only shown before the first office user);
    Certificates, "No certificates yet" (**Go to Crew**), or when certificates exist but none expires within 90 days
    "Every certificate is valid for more than 90 days." (**Show all certificates**, secondary, `?status=all`); a
    ship's Certificates tab, matrix and list: Add certificate, or without crew on board **Go to Crew** (its tab);
    Galley items (Add item, Import); Orders, "No orders yet" (**Go to Ships**);
    Announcements (New announcement; on the tab with the ship preselected); Audit log (**Go to Overview**, where the
    setup checklist is); Notifications (**Go to start page**); Library, office tab and the Master (Add an item; the
    other crew read who adds documents, English only); a crew member's Certificates card (Add certificate, secondary:
    Save is that page's primary); the ship Overview tab's Crew on board (Add crew member, opening the Crew tab's form)
    and the Overview's Fleet card (Go to Ships). The texts are `<list>.empty.title` / `.text` message keys;
  - row actions: at most one visible tertiary button (e.g. **Edit** on a ship, **Resend** on an invitation) and a
    **⋯** "more" menu (`layout/parts :: rowMenu`) named after the record for screen readers ("More actions: MV
    Demo"), holding the rest; destructive entries (deactivate, delete, cancel) come last, after a divider, in red,
    and ask first (see "confirmations (P10)" below).
    The menu is a Bootstrap dropdown on a native `<details>` element: with JavaScript Bootstrap opens it (arrow
    keys, Escape, positioned over the page so a scrolling table never cuts it off; `js/row-menu.js` keeps the
    details element open meanwhile); without JavaScript the details element opens and every entry is still an
    ordinary link or form;
  - phones (below 768 px): `.data-table-stack` turns each row into a stacked card: the name as its title (with
    the checkbox before it and the actions after it), two or three key fields as label and value (`data-label`),
    the status badge; less important columns (`.stack-hide`) are left out, so there is no sideways scrolling.
    The audit log and the import review tables keep their columns and scroll sideways (the import review keeps
    its row number and name columns in place, P24).

  - forms (P05): one pattern on every form (ship, crew, invitation, certificate, item, announcement, library new, edit
    and particulars, registration, crew and item import), in the "Forms" section of `app.css`:
    - layout: the form card (`.form-box`) is at most 720 px wide; fields sit in `.form-grid`, one per row, and from
      768 px short related fields (`.field-half`) two per row (Type + Flag, Number + Expires
      on, Unit + Category, Category + Version label). Long fields, textareas and the ship pickers span the full width
      (a ship's name and IMO number are never cut off). A form with more than five fields groups them under short
      legends (`.form-section`: "Item details" and "Content" on Add an item);
    - labels 14 px, weight 600, 6 px above the field; optional fields say "(optional)" after the label
      (`layout/parts :: optional`), required ones have no asterisk; help text 13 px under the field; the error
      message stays under the field;
    - values that cannot change (email on the crew edit page and on registration, IMO number on Edit ship, the
      title, category and current version on Publish new version, IMO number and flag on the particulars page) are
      plain text in label and value rows (`<dl class="static-fields">`), never read-only or disabled inputs;
    - actions: on full pages bottom left, primary first, then **Cancel** as a tertiary button (`.form-actions`); in
      pop-ups in the footer on the right, **Cancel** then the primary. Wording: **Save**, **Cancel** (`form.save`,
      `form.cancel`) or a specific verb (**Send invitation**, **Send**, **Add item**, **Publish**). Registration keeps
      its full-width 48 px **Create account**, as on the sign-in page;
    - errors: a form posted with more than one error starts with a summary ("This form has 3 errors",
      `layout/parts :: errorSummary`) linking to each field in page order; `js/forms.js` moves focus to it (in a
      pop-up once it has opened) and puts each field's label before its message;
    - character limits: every field with a `@Size(max)` in its form class has a counter under it
      (`layout/parts :: counter`; the limit is read from the form class by the `formFields` bean,
      `core/form/FormFields`). It is hidden until the text reaches 75 % of the limit; then `js/forms.js` shows
      "3,800 / 5,000" while typing, and "5,012 / 5,000 · 12 too many" in red and bold over the limit. Without
      JavaScript it stays hidden: `maxlength` and the server's message cover the limit. Announcement
      messages and ship particulars count a line break as one character, as the server does;
    - pop-up or page: a pop-up only for short forms (add and edit ship, invite office user, add crew member, also on a ship's Crew tab), everything else is a full page;
    - file inputs show the accepted types and the size limit before a file is chosen ("PDF, DOCX or XLSX, at most
      10 MB", "XLSX or CSV, at most 10 MB") and, with `js/forms.js`, the chosen file's name and size after it, with a
      warning when a library file is over 10 MB.

  - page header and layout frame (P06): every page, signed out ones included, starts with
    `layout/parts :: pageHeader`, in this order:
    - breadcrumbs on every page below the top level (the sidebar entries are the top level): section › record ›
      page, each a link except the last, which is the page itself (`aria-current="page"`). The section is the sidebar
      entry (Ships, Crew, Announcements, Requests; Library for the crew). Below a ship the trail goes through the ship
      page and its tab (`ship/parts :: crumbs`): Ships › MV Demo › Items › Add item; on the ship page's tabs it is
      Ships › MV Demo. Links are underlined and each is a 40 px (44 px on phones) touch target. They replace the
      "← Library" style links and the "Back to ships" link;
    - the eyebrow, only for real context: the ship's name and IMO number on every ship-scoped page below the ship page
      ("MV Demo · IMO 9074729": item and library forms, request detail, announcement to one ship;
      `pageHeader(eyebrowShip=${ship})`, a link to the ship page for office users) and on the crew library pages;
      "All ships" for a fleet announcement and "3 ships" for one to several ships (P28); the status code on the error page. The tabs of the ship page have the
      ship's name as their heading instead. There is no "Office portal" eyebrow;
    - the title (h1), with the record count after it on list pages ("Galley items · 3"); no page shows its title in
      a card header as well. **Export** on **Orders** (P36) is in the page header and still submits the list's
      form without JavaScript (`form="exportForm"`);
    - an optional subtitle (one line, e.g. "Newest first.", or the category, version and date of a library item) and
      the actions on the right.
    The parameters are named: `pageHeader(heading=..., crumbs=~{::pageCrumbs}, eyebrow=..., count=..., subtitle=...,
    actions=~{::pageActions})`; the comment above the fragment shows how to define the crumbs
    (`layout/parts :: crumb(label, url)`) and actions. Pages pass their name to the layout as text,
    `layout(#{ship.list.title}, ~{::main})`: the browser tab shows "Page name · Nereonet" and, on phones, the top bar
    shows the page name instead of the app name. The content uses the full width next to the sidebar (P29: no
    1280 px cap any more), with 32 px side gutters from 768 px (16 px on phones), and 24 px between the header, the
    filters and the content; it has `min-width: 0`, so a wide table scrolls inside its card instead of widening the
    page (no page scrolls sideways at 1920, 1440, 1024 or 390 px; the import review and audit log tables scroll
    inside their card). Lists, Overview, the ship page and the certificate matrix are fluid. Form pages
    (`<main class="form-page">`) keep a 720 px column, centred; where a form has related context
    (`form-page form-page-aside`: the ship's particulars beside an item or library form, `ship/parts :: formAside`;
    the template beside the import upload) it goes beside the form from 1200 px and under it below. Card grids
    (`.card-grid`: ship Overview, an announcement's acknowledged / pending lists) have 1 column, 2 from 992 px and
    3 from 1600 px (`.card-grid-3`).

  - sidebar navigation (P07): the navy sidebar (`layout/layout.html`, entries `layout/parts :: navItem`) has small
    uppercase group labels:
    - office: **Operations** (Overview `/office`, Ships, Crew, Certificates, Vetting (P40, see [25](features/25-vetting.md)), Announcements,
      Orders (P36, see [24](features/24-office-orders.md)), Suppliers (P44, see [28](features/28-suppliers-and-quotes.md))),
      **Administration** (Office users, Audit log), **Account** (Notifications; the language switch moved to the top
      bar, P29); crew: **On board**
      (Home `/ship`, News `/ship/news`, Library, My crew), **Supplies** (P65: Requests, Approvals, Vessel order,
      Stores, per role, see [18](features/18-crew-home.md)) and **Account** (My profile, Notifications). The user block (name,
      role, **Sign out**) is pinned at the bottom; when the window is shorter than the navigation, only the navigation
      scrolls. The brand (logo and name) links to the user's home page (`/office` or `/ship`);
    - the entry of the current section is active: lighter background, a 3 px pink bar on its left edge, white semibold
      text and `aria-current="page"`. Pages below an entry keep it active (`/office/crew/12/edit` → Crew,
      `/office/ships/3/library` → Ships, `/office/invitations` → Office users), also their error pages. Exactly one entry
      is active on every page (on `/ship` it is Home, P26). The section comes from one place,
      `navigation/NavigationAdvice` (model attribute `navSection`, from the URL via `NavSection`); controllers do not set it;
    - counters, a quiet pill on the right of the entry, hidden when zero, "99+" above 99, with the number in words for
      screen readers: **Certificates** expired or expiring within 30 days (UTC days; while it shows, the entry opens
      exactly those, `/office/certificates?status=expired,30`, P20), **Vetting** active vessels whose RightShip
      inspection has expired or expires within 60 days (P40) or whose SIRE 2.0 needs attention (P41), each vessel
      once ("2 need attention"), **Orders** pending or with the delivery
      pending (P51), **Notifications** unread. One count query each on every page (`navCounts` from `NavigationAdvice`,
      office users only; `unreadNotifications` from `NotificationBellAdvice`). The Notifications pill and the bell
      follow Mark as read without a reload ([13](features/13-notifications.md), P23; the entry has `data-nav="notifications"`);
    - rail and preference (P29), from 768 px: the sidebar is a 64 px rail by default (the mark, the entries' icons, the
      user's initials and a sign-out icon); each entry's name shows as a tooltip beside it on hover and on keyboard
      focus, and stays in the link for screen readers; the active entry keeps its pink bar; a counter becomes a
      10 px dot with the number and its meaning still in the link's text. **Expand menu** / **Collapse menu**
      (`aria-expanded`, `aria-controls="sidebar"`; `layout/parts :: sidebarToggle`) switches to the full 256 px
      sidebar and back. It is at the top, next to the brand mark (under it, centred, in the rail), where it
      is found without scrolling: an icon only (a panel with a chevron pointing the way the sidebar will move) on a
      44 px target, its words the accessible name and a tooltip on hover and keyboard focus. The choice is the cookie `sidebar=expanded|collapsed` (one year,
      path `/`, SameSite=Lax), read by the server (`navigation/SidebarPreference`, the body class
      `sidebar-expanded` / `sidebar-collapsed`), so the next page is drawn in the right state without a flash.
      With JavaScript (`js/sidebar.js`) the toggle changes the page in place and writes the cookie; without it the
      button posts its form to `POST /preferences/sidebar` (`state`, `returnTo`; CSRF as everywhere), which sets the
      cookie and goes back to the same page and query (`returnTo` must be a path on this site, anything else goes to
      `/`). It is a display preference: no audit entry and no message;
    - phones (below 768 px), office users: the sidebar is an offcanvas (at most 320 px, so part of the dimmed page
      stays visible) opened from the top bar's menu button. It closes with the X next to the brand (44 px, "Close
      menu"), a tap outside, Esc, or by choosing an entry (`js/sidebar.js`); focus then goes back to the menu button,
      and the page behind does not scroll while it is open. Ship crew have no menu button and no bell: the bottom tab bar ([18](features/18-crew-home.md), P26) has the same
      entries. Entries are 40 px tall (44 px on phones); Tab goes brand, entries in visual order, Sign out, the
      sidebar toggle, then the page.

  - confirmations (P10): no action that cannot be undone or that takes access away happens on one click, and every
    confirmation looks and reads the same. Deactivate (ship, user, galley item), Delete (user, certificate), Cancel
    (office invitation) and Retire (library item) are each a `layout/parts :: confirmForm`; Activate needs none:
    - the form opts in with data attributes: `data-confirm-title` names the record ("Deactivate MV Demo?"),
      `data-confirm-body` gives the consequence in one sentence, `data-confirm-label` is the confirm button,
      `data-confirm-tone` is `danger` (cannot be undone: delete, cancel, retire) or `neutral` (Activate undoes it:
      deactivate), and the optional `data-confirm-dismiss` replaces "Cancel" where it would read like the action
      ("Keep invitation" next to "Cancel invitation"). The texts are message keys (`*.confirm.title`, `*.confirm.body`);
    - `js/confirm.js` opens the one dialog, `layout/parts :: confirmDialog` (a native `<dialog>` in the layout, styled
      like the other modals): focus starts on Cancel; Esc, Cancel and the backdrop close it and focus goes back to
      the menu button or the trigger; the confirm button is `btn-outline-danger` (danger) or `btn-primary` (neutral)
      and works once; in a danger dialog Enter does not confirm (Space or a click does);
    - without the script (or in a browser without `<dialog>`) nothing is submitted silently: the form has no submit
      button and its trigger is a link to the action's own URL, where a GET shows the same question as a plain page
      (`confirm/confirm.html`, built by `core/confirm/ConfirmPages` from the same message keys) with the confirm
      button and the way back to where it started (e.g. `/office/ships/3/deactivate?tab=crew`).

  - times and dates (P11): office and ship read the same time for the same event, so every time is shown in UTC
    and says so. One format in both languages, from one place, the `@dates` bean (`core/time/Dates`); month names
    follow the page language:
    - a date and time is `layout/parts :: localTime`: "21 Oct 2026 · 12:40 UTC" (Greek "21 Οκτ 2026 · 12:40 UTC"),
      with `datetime="2026-10-21T12:40:00Z"`. The text is the same whatever the viewer's time zone (Athens,
      Singapore). `js/local-time.js` does not change it: it adds the viewer's own time as a tooltip and as screen
      reader text, "Your time: 21 Oct 2026, 14:40" (wording from `time.local`, on `<body data-local-time>`);
      without the script there is just no tooltip;
    - a date without a time (a certificate's expiry) is `layout/parts :: localDate`, "21 Oct 2026", with no zone;
    - dates that drive action get a relative hint after them, in whole UTC days from today (UTC), computed on the
      server: certificate expiry "in 23 days", "expires today", "expired 5 days ago"
      (`layout/parts :: certificateHint`, or `expiryDate` for the date and its hint: certificates list, crew edit
      page, ship Overview, office Overview) and invitation expiry "expires in 2 days", "expired today"
      (`layout/parts :: invitationHint`: Office users, Crew, office Overview, where a crew invitation now reads
      "sent 4 Nov 2026 · 09:12 UTC · expires in 3 days"); after "Invitation expired" on Office users and Crew it
      says when, "2 days ago", "today" (`invitationExpiredHint`, P17; both in `crew/parts :: invitationStatus`). The hint is secondary text (`.date-hint`) that never breaks;
    - the audit log's date filter is labelled **From (UTC)** and **To (UTC)**: it filters on UTC days;
    - certificate reminder emails and notifications (English) use the same date format (`Dates.DATE_PATTERN`).

  - error pages (P12): an error says what happened and what to do next. Spring Boot picks
    `templates/error/<status>.html`, then `error/<series>xx.html`, then `error.html`; all of them are one
    `layout/parts :: errorPage(heading, text)`, with the status code as the eyebrow and **Go to start page** (the
    user's home, `/office` or `/ship`) as the next step:
    - 403 `error/403.html` "No access": "You don't have access to this page." (e.g. a crew member opening an
      `/office` URL);
    - 404 `error/404.html` "Page not found": "This page doesn't exist or was removed." (an unknown URL or record id,
      e.g. a library item that does not exist);
    - 500 and up `error/5xx.html` "Something went wrong": "Something went wrong on our side. Try again." and a
      reference to quote to the office: the time in UTC and an eight-character code without 0/O and 1/I
      ("11 Nov 2026 · 14:03 UTC · 7KQ3MX2P"). `error/ErrorReferenceAttributes` makes the code and logs it with the
      failed URL (`Error reference 7KQ3MX2P: status 500 on /office/ships: ...`), next to the server's stack trace;
    - any other status (400, 405) keeps the general `error.html`, "Something went wrong";
    - signed in, the error page sits in the normal frame with the sidebar (crew get the ship sidebar, office the
      office one, the section of the failed URL stays active); signed out, in the navy frame of the sign-in page;
    - a form sent after the session expired, or with an invalid CSRF token (e.g. from a tab of an older session),
      no longer ends on a 403: `core/security/SignedOutHandler` signs the user out if still signed in and sends
      them to `/login?expired`, "You were signed out. Sign in to continue." After signing in they are back on the
      page the form was on (from the `Referer` header). A page opened while signed out also opens after signing in.
      `core/security/ReturnTo` only returns to paths of this app, never to `/login`, `/logout`, `/register` or
      `/error`, nor to an `/office` page for crew or a `/ship` page for the office (on a shared PC the next person
      may be someone else); otherwise it is the home page. The text of a long-text form is not lost: the browser
      keeps a draft and the form opens with it (see "saving on a bad connection (P15)" below).

  - sign-in and registration (P13): a first-time seafarer on a phone gets in without help (see [7](features/07-registration.md) for the flow and
    the messages):
    - one signed-out frame, `layout/parts :: authFrame` (sign-in, registration, invalid link, signed-out error
      pages): the narrow navy brand panel on the left from 768 px (P30: 320 px, growing with the window to at most
      416 px, `--nn-auth-panel-width`) with the brand at its top left (`layout/parts :: brand`: the mark and
      "Nereonix" as text) and the organisation at its bottom left: "Organisation" / "Εταιρεία", the company's name
      and its code, from `ORGANISATION_NAME` and `ORGANISATION_CODE` (`core/config/Organisation`; nothing is shown
      while the name is empty). The form card is at most 420 px, centred (P29). The sign-in, registration and reset
      pages use `layout/auth` instead of `layout/layout` (P30), so they never show the signed-in frame around them,
      even to someone who is still signed in. On phones the panel collapses to a 64 px bar with the mark and
      "Nereonix" (no organisation), so the form starts above the
      fold (at 390 × 844 the **Continue** button ends at about 480 px). **EN | ΕΛ** (sign-in, registration, reset)
      is at the top right of the form side, a 40 px (44 px on phones) touch target;
    - password fields (sign-in, both registration fields) have **Show** / **Hide** inside the field on the right
      (`layout/parts :: passwordToggle`; accessible name "Show password" / "Hide password", and a screen reader
      hears "Your password is visible / hidden"); the field is set back to hidden before the form is sent. Under
      the field "Caps Lock is on." appears while the field has focus and Caps Lock is on
      (`layout/parts :: capsLockHint`). Both come from `js/password.js`; without it the button stays hidden and the
      field is an ordinary password field;
    - the new password rules are a checklist under the field, "At least 8 characters" (the `@Size(min)` of
      `core/form/NewPasswordForm`, via `@formFields.minLength`) and "Both passwords are the same", which
      `js/password.js` ticks while the user types: a green circle with a check mark and "done" for screen readers, an
      empty circle and "not yet" otherwise. Without the script it is a plain list. The server checks the same rules
      again; its messages stay under the fields. Registration and password reset (P14) share the two fields, the rules
      and the checklist: `layout/parts :: newPasswordFields`, for a form class that extends `NewPasswordForm`;
    - text links between the signed-out pages ("Forgot your password?", "Back to sign in", P14) are
      `layout/parts :: authLink`: underlined, 14 px semibold, a 40 px (44 px on phones) touch target.

  - saving on a bad connection (P15): typing a long text and pressing Save is safe on an unstable satellite link.
    All of it is `js/save.js`, on every page; without the script every form still submits. No offline
    queue and no service worker:
    - sent once: when a POST form is sent, all its submit buttons are disabled and the one pressed shows a spinner
      and "Saving…" (sign-in "Signing in…", sign-out "Signing out…": `data-busy-label`).
      A second click, a double click or Enter sends nothing more. Back to the page (browser Back, `pageshow`) gives
      the buttons back. A form answered with a download (`data-file`, the order export, P36) leaves the page as it
      is, so it is never made busy. The
      confirmation dialog (P10) sends its form the same way
      (`requestSubmit`);
    - drafts: the long-text forms (new announcement: title and message; library: add an item, publish a new
      version, ship particulars; office and crew) carry `data-draft="<the URL of their page>"`. While the user types,
      their fields are kept in the browser's `localStorage` under that URL and the signed-in user's id
      (`<body data-user>`, `CurrentUser.getId()`), so on a shared PC one person's draft is never shown to the next.
      File inputs, passwords, hidden fields and fields marked `data-draft-skip` are never kept: the announcement's
      **Send to** is not, so a draft never picks a ship. Opening the form with a draft that differs from what the
      server sent puts the draft back and says "Draft restored from 12:40 UTC · Discard" (another day: "21 Oct 2026 ·
      12:40 UTC"; `layout/parts :: draftNotice`); **Discard** puts back what the server sent and removes the draft;
    - the draft is removed only when the server confirms the save: sending the form (or the review page's form,
      `data-draft-for`) notes it in `sessionStorage`, and the next page removes it if it shows the success message
      (`data-flash="success"` on `layout/parts :: flash`). The form shown again with errors, an error message or the
      sign-in page keep it. So after a session expired (P12) the user signs in, lands on the form and the text is
      back. The review and send URLs of announcements, which only take POST, now redirect a GET to the form
      (`GET /office/announcements/review` → `/office/announcements/new`), so returning there after signing in works;
    - leaving a page never asks anything (no `beforeunload`): text typed in a form without `data-draft` and not sent
      is lost, as on any web form;
    - offline: while the browser reports no connection a banner is fixed at the bottom of the window (right of the
      sidebar from 768 px; `layout/parts :: offlineBanner`, a live region): "No connection. Your text is kept on this
      device." on a page with a draft form, "No connection. Wait for it to come back before you save anything."
      elsewhere. Sending a POST form is stopped and "Not sent: there is no connection. Send it again when the
      connection is back." appears under its buttons. The banner and the message go when the connection returns.
      Storage may be full, blocked or missing (private windows): every access is wrapped, and the page then works
      without drafts.

  - language switch and required notices (P16):
    - **EN | ΕΛ** (`layout/parts :: langSwitch`, P29): at the top right of the office top bar on every office page and
      width (white on the navy phone bar, navy on the page from 768 px), and at the top right of the form side of
      the signed-out pages; the crew pages have none. The current language is bold with `aria-current="true"`, a thin
      line between the two; each link has `lang`/`hreflang` and the accessible name "English" or "Ελληνικά", and is
      40 px (44 px on phones). The links change only the
      language. Each is the current page's query string with `lang` set, as a relative link (`?lang=el&shipId=1`),
      from the `@languageLinks` bean (`core/config/LanguageLinks`): the browser keeps the path, and the ship and
      status filters, page number and invitation or reset token stay, in their order and encoding. `lang` is never
      repeated. Only the URL's query string is used, never what a form posted (a page shown in answer to a POST, such
      as the announcement review, gets `?lang=el` only); an error page keeps the query of the request that failed.
      The registration and reset forms shown again after a POST still pass their token, which is added when the URL
      lacks it;
    - notices the user must see where they apply are `layout/parts :: infoNote(id, text)`: a quiet note in the info
      pair (13 px, 7.5:1) with an "i" icon; the words carry the meaning. Today: "not an emergency or safety channel"
      on the new announcement form and its review page, and "do not upload security-sensitive material" under the
      library file input (add an item, publish a new version; crew and office), which the input names in its
      `aria-describedby`;
    - every office key of `messages.properties` is also in `messages_el.properties`: `core/config/MessageKeysTest`
      fails and lists the missing keys otherwise. Crew-only and email keys, English only today, are in its explicit
      `ENGLISH_ONLY` list; the test also fails when a listed key is removed or gets a Greek text.

Links from templates (`@{/css/app.css}`) and `url(...)` in CSS get a content hash in the URL
(`/css/app-<md5>.css`), and these files are sent with `Cache-Control: max-age=31536000, public`
(`spring.web.resources` in `application.yml`). A changed file gets a new URL, so browsers on board load each
version once. Always link static files with `@{...}` so they get the hash; a plain `/css/...` link would be cached
for a year without one. All of these paths are public in `SecurityConfig`, so the sign-in page is styled too.
