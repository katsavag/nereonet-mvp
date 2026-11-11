# Nereonet MVP

Crew and ship management for a single shipping company.
Spring Boot + Thymeleaf + PostgreSQL.

## How to run

Requirements: Java 25, Docker.

1. Start PostgreSQL and Mailpit:

   ```
   docker compose up -d
   ```

2. Start the app with the `dev` profile (uses the defaults in `application-dev.yml`):

   ```
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   On Windows (cmd/PowerShell) use `mvnw.cmd` instead of `./mvnw`.

3. Open http://localhost:8080. Signed out, it goes to the sign-in page; signed in, it goes to
   your dashboard (office users `/office`, ship crew `/ship`). Office users can switch language
   (English / Ελληνικά) on the sign-in page and in the sidebar.

   On first start the dev profile creates an office user (`office@example.com` / `office123`)
   and a demo ship (`MV Demo`, IMO 9074729). While there are no item requests it also creates two approved
   requests on the first ship (by name), with three demo items and, when the ship has no active Master / Chief cook,
   the users `master.demo@example.com` and `cook.demo@example.com` (password `crew123`).
   While that ship's library is empty it also gets two text items: "Galley hygiene rules" (Ship rules) and
   "Drug and alcohol policy" (Company policies, required).

   The schema is updated by Hibernate, which cannot add a required column to a table that already has
   rows (you get "column ... does not exist" errors). Then either add the column by hand or empty the
   dev database (with the compose database: `docker compose down -v`, then `docker compose up -d`).
   Hibernate also never updates the check constraints it creates for enum columns. If a database was created
   before invitations could be cancelled, cancelling fails with `invitation_status_check`; fix it with:

   ```
   ALTER TABLE invitation DROP CONSTRAINT invitation_status_check;
   ALTER TABLE invitation ADD CONSTRAINT invitation_status_check CHECK (status IN ('PENDING','ACCEPTED','CANCELLED'));
   ```

   Hibernate never drops a NOT NULL either. If a database was created before users could be deleted, deleting an
   office user who sent invitations fails; fix it with:

   ```
   ALTER TABLE invitation ALTER COLUMN invited_by_id DROP NOT NULL;
   ```

4. Emails sent by the app appear in the Mailpit inbox at http://localhost:8025.

   **Overview** (`/office`, the office home page, P09) is a work list: what needs the office, with the next step on
   each row, so it can be cleared without visiting other pages first. Under the title one summary line ("5 ships ·
   84 crew · 3 open items": active ships, enabled crew on them, open items); the page action is **New announcement**.
   - **Needs attention**: one row per open item of an active ship, the total in the card header, most urgent first:
     1. certificate expired; 2. certificate expiring within 30 days (UTC dates); 3. approved request not exported;
     4. crew invitation expired, or sent (last) more than 48 hours ago and not accepted (worked out from its expiry
     date and `app.invitation.valid-days`); 5. announcement sent, or Required library item published (current
     version), more than 7 days ago that not everyone has acknowledged. Within a kind, the most overdue or oldest first.
     Each row has a status badge with an icon and the kind, a title linking to the record (the certificate, the request,
     the ship's crew list for an invitation, the announcement, the item's acknowledgements), one context line
     (ship · person and rank, or "2 of 5 not acknowledged" · date) and one action: **Open certificate**, **Export**
     (downloads that request's CSV, as on Requests, and marks it exported; it is gone the next time the page loads),
     **Resend invitation** (new link and expiry date, back to the Overview with "Invitation sent again") or **View who
     has not acknowledged**. The card shows the first 50 rows ("Showing the first 50 of 63"); under it **Full lists**
     links each kind to its full list: requests to `/office/requests?notExported=true`, the others to this page
     filtered to that kind (`/office?type=certificates`, `invitations`, `acknowledgements`, every row, with **Show all
     open items** to go back) until the certificate and crew lists can filter by status (P20, P17). With nothing open
     the card says "Nothing needs your attention" and when it was checked.
   - **Fleet**: one row per active ship (the name opens the ship page): crew on board, certificate issues (expired or
     within 30 days), not acknowledged (announcements and Required library items as above), requests not exported.
     Ships with open items come first, then by name. A zero is a muted dash and not a link; any other number links to
     its list filtered to that ship and condition: `/office/crew?shipId=…`, `/office?type=certificates&shipId=…`,
     `/office?type=acknowledgements&shipId=…`, `/office/requests?shipId=…&notExported=true`.
   - `DashboardService.load()` always runs the same 7 queries, whatever the size of the fleet (active ships, crew per
     ship, and one per kind of open item, in `dashboard/DashboardQueries`); `DashboardFlowTest` checks it.
   With the dev data the two seeded approved requests are two rows with **Export**; export them and the card says
   "Nothing needs your attention".

5. Ships (signed in as an office user): **Ships** in the sidebar (`/office/ships`). **Add ship** (a pop-up form) asks for
   the IMO number (7 digits with a valid check digit, e.g. `9074729`; `IMO 9074729` also works), name,
   type and flag (type to filter the countries, e.g. "Greece (GR)"; only the code `GR` is stored). Ships can be
   edited in a pop-up (except the IMO number) and deactivated or activated, never deleted: on the list both are in the
   row's **⋯** menu (**Edit**, then **Activate** or **Deactivate**). The ship's name opens its ship page.
   Inactive ships stay in the list but cannot get new crew invitations. **Deactivate** asks first ("Deactivate MV Demo?
   No new crew can be invited to MV Demo until it is activated again.", see "confirmations (P10)" below); **Activate**
   does not.

   **Ship page** (`/office/ships/{id}`, P08): one place per ship. Every tab starts with the same ship header:
   breadcrumbs (Ships › MV Demo), the ship's name as the heading, then IMO number, type, flag and an Active / Inactive
   badge, with **Edit** (the same pop-up as on the list; without JavaScript it opens `/office/ships/{id}/edit`, the
   same form as a page) and a **⋯** menu (**Activate** or **Deactivate**). Edit, Activate and Deactivate come back to
   the tab they were started on. Under the header a tab bar of ordinary links, one URL each, so every tab can be
   bookmarked; each shows a count:
   - **Overview** (`/office/ships/{id}`): the ship's open items, with the fleet Overview's rule (expired
     certificates, certificates expiring within 30 days, approved requests not exported), each listed with a link;
     the crew on board (enabled users) by the department of their rank (Deck, Engine, Galley, or "Rank not set");
     and the particulars (name, IMO number, type, flag and the first lines of the particulars text, with **Edit ship
     particulars** and a link to the library). The tab's count is the number of open items, in red when above zero.
   - **Crew** (`/crew`), **Certificates** (`/certificates`), **Announcements** (`/announcements`) and **Requests**
     (`/requests`): the fleet-wide lists of 8, 12, 14 and 15 for this ship only, without the ship filter and the Ship
     column (Requests keeps "Not exported yet" and **Export CSV**). The fleet-wide pages keep their ship filter.
   - **Library** (`/library`, see 16) and **Items** (`/items`, see 11): the existing pages, now inside the frame.
   Actions started on a tab keep the ship and return to the tab: **Add crew member** opens
   `/office/ships/{id}/invitations/new`, which never asks which ship; **Add certificate** (`/certificates/new`) picks the
   crew member from this ship's active crew only; **New announcement** opens the form with this ship preselected (the
   target can still be changed) and returns to the tab once sent; a crew member's **⋯** menu returns to the Crew tab.
   The counts: Crew, every user on the ship and its pending invitations; Certificates, expired or expiring within 90
   days; Library, items not retired; Items, all; Announcements, sent to this ship or to all ships; Requests, approved.

   An inactive ship shows a banner on every tab: crew cannot be invited to it or moved to it, it gets no
   announcements and it is not on the Overview; its records stay as they are; **Activate** in the **⋯** menu undoes it.
   Its Crew and Announcements tabs offer no **Add crew member** / **New announcement**.
   On phones the tab bar scrolls sideways and starts with the active tab in view (`js/ship-tabs.js`; without it the bar
   still scrolls). Every ship name in the office (lists, the Overview cards, the audit log, the eyebrow of ship-scoped
   pages) links to the ship page. An unknown id gives 404 on every tab.

6. Invitations (signed in as an office user). The email with the `/register?token=...` link
   appears in Mailpit.
   - Office users: **Office users** in the sidebar (`/office/users`, see 9), then **Invite office user** (opens a pop-up
     form with full name and email; the email is asked once, and the success message names the address the invitation
     went to, e.g. "Invitation sent to jane@example.com."). There is no separate invitations page any more:
     `/office/invitations` redirects to `/office/users`. Pending invitations are listed there with the users, as
     "Invited", or "Invitation expired" when past their expiry date; accepted and cancelled invitations are not listed
     (an accepted one is a user). Each has **Resend** (new link and expiry date, new email; the old link stops
     working) and, in the row's **⋯** menu, **Cancel** (the link then shows the "invalid or expired" page). Cancel asks
     first: "Cancel the invitation to Jane Doe? The link sent to jane@example.com stops working.", with **Keep
     invitation** and **Cancel invitation**.
   - Crew members: **Add crew member** on the **Crew** page (see 8), or on a ship page's **Crew** tab, which opens
     `/office/ships/{shipId}/invitations/new` (the demo ship is usually id `1`) and returns to the tab. The form asks for
     full name, email (once; the success message names the address) and role, and an optional rank (from a fixed list),
     which is copied to the user on registration. A ship can have only one active Master and one
     active Chief cook; inviting a second one shows an error on the role field.

7. Registration: open the `/register?token=...` link from the invitation email (signed out, e.g. in a
   private window) and set a password. The page says what the person is joining: "You are joining MV Demo as Second
   Officer." (the invitation's rank, or its role without a rank: "as Master", "as a crew member") or "You are joining
   Nereonet as an office user.". **Create account** signs the new user in at once and opens their home page
   (`/office` or `/ship`) with "Your account is ready. You are signed in."; there is no trip through the sign-in
   form. The English / Ελληνικά links keep the token, so switching language stays on the same invitation.
   Each link works once; after that, or after it expires (or is cancelled), it shows the "This link is invalid or has
   expired" page, which names the two causes and what to do about each (used already: sign in; expired: ask the office
   for a new invitation, "A link works for 7 days", from `app.invitation.valid-days`). When `app.support-contact` is
   set (`SUPPORT_CONTACT`, free text, see "Configuration outside dev"), the page also shows it as "Office contact".

   Sign-in messages: a wrong email or password, for any account, gives "Wrong email or password.". A deactivated
   account (see 9) gives "This account is deactivated. Contact your office." only when the password was right
   (`/login?deactivated`; `SecurityConfig` checks "enabled" after the password, not before as Spring Security does by
   default), so the message never tells a stranger that an email has an account.

8. Crew (signed in as an office user): **Crew** in the sidebar (`/office/crew`) lists every user with a ship role
   and every pending crew invitation (status "Invited"), filterable by ship. **Add crew member** opens a pop-up form
   (full name, email, optional rank, role, active ship) that sends an invitation email (see Mailpit) and names the address in
   its success message;
   the member appears at once as "Invited" and becomes a user when they register. Clicking a member's name (or **Edit** in the row's **⋯** menu) opens their page; it changes full name, rank, role
   and ship (active ships only; the email cannot be changed). Picking another ship moves the crew member. The one active
   Master / Chief cook per ship rule is checked here too.

   **Import from file** (`/office/crew/import`) invites many crew members at once:
   - **Download template** gives `crew-template.xlsx`: sheet "Crew" with the header row
     `Full name | Email | Rank | Role | Ship IMO`, and sheet "Instructions" (column meanings, role and rank values,
     active ships; in the page's language). Only this template is accepted.
   - Upload the filled-in file as `.xlsx` (first sheet) or `.csv` (UTF-8, `,` or `;`). A file whose first row is not
     the template's header shows "This file does not follow the template".
   - The review screen shows every row, editable, with errors highlighted (missing name or email, invalid,
     existing or repeated email, unknown rank or role, no active ship with that IMO number, more than one Master /
     Chief cook per ship). Exclude rows with the checkbox; **Check again** re-validates.
     **Import and invite** (enabled when no included row has errors) sends one invitation email per row.
     Nothing is saved and no email is sent before that.
   - To try it on the dev database: upload [`samples/crew-sample.xlsx`](samples/crew-sample.xlsx), 10 invented crew
     members (example.com emails) for the demo ship `MV Demo` (IMO 9074729): one Master, one Chief cook and eight crew
     members. Regenerate it with `./mvnw test -Dtest=CrewSampleFileGenerator -DgenerateSample=true`.

9. Office users (signed in as an office user): **Office users** in the sidebar (`/office/users`) lists the office users
   and the pending office invitations together, with **Invite office user** (see 6).
   Any user, crew or office, can be deactivated (they can no longer sign in) and activated again, from the **⋯** menu
   at the end of their row (here and on **Crew**). **Deactivate** asks first ("Deactivate Jane Doe? Jane Doe will not be
   able to sign in until activated again."; a session that is already open is not ended). A deactivated user can also
   be deleted (**Delete** in the same menu, after the confirmation "Delete Jane Doe? Jane Doe (jane@example.com) will be
   deleted for good and cannot be restored."): the user and every invitation for their email are removed,
   so they can be invited again. Their audit log entries stay and show "Deleted user"; invitations they sent stay.
   They are removed from the recipient lists of announcements; announcements they sent stay and show "Deleted user".
   You cannot deactivate or delete yourself.

10. Audit log (signed in as an office user): **Audit log** in the sidebar (`/office/audit`). Lists who did what and
   when, newest first, 50 rows per page, filterable by ship and date range (UTC days). Recorded so far: ship added,
   edited, deactivated, activated; invitation sent, resent, cancelled; crew imported (counts only); user registered, edited, deactivated, activated, deleted;
   item added, edited, deactivated, activated; items imported (counts only); certificate added, edited, deleted;
   certificate reminder sent (by "System"); announcement sent; requests exported (count and ids).
   Entries are read-only.

11. Galley items (signed in as an office user): the **Items** tab of a ship page (`/office/ships/{id}/items`, see 5).
   Each item has a name (unique per ship, ignoring case), a short unit (e.g. `kg`, `lt`, `pcs`) and an optional category.
   **Add item** and the item's name (or **Edit** in the row's **⋯** menu) open a form; items can be deactivated and activated (the row's **⋯** menu), never deleted. Deactivate asks first
   ("The crew of MV Demo cannot request Basmati rice until it is activated again.").
   **Import** (`/office/ships/{id}/items/import`) adds many items at once:
   - **Download template** gives `items-template.xlsx` with the header row `Name | Unit | Category`. Only this header is accepted.
   - Upload it as `.xlsx` (first sheet) or `.csv` (UTF-8, `,` or `;`). The review screen shows every row, editable, with
     errors highlighted (missing name or unit, value too long, name repeated in the file). Rows whose name the ship
     already has are marked and skipped. Exclude rows with the checkbox; **Check again** re-validates.
   - **Import items** saves the new items; the item list then shows how many were added and which names were skipped.
     Nothing is saved before that.
   To try it, save this as `items.csv` and upload it twice (the second time all three are skipped):

   ```
   Name,Unit,Category
   Basmati rice,kg,Dry goods
   Olive oil,lt,Oils
   Eggs,pcs,Fresh
   ```

12. Certificates (signed in as an office user): on a crew member's **Edit** page (`/office/crew/{id}/edit`) the
   **Certificates** card lists their certificates (type, optional number, expiry date) with **Add certificate**,
   **Edit** and, in the row's **⋯** menu, **Delete** (after the confirmation "Delete STCW Basic Safety?").
   **Certificates** in the sidebar (`/office/certificates`) lists every certificate that
   expires within 90 days or has expired, soonest first, filterable by ship, with a badge: Expired, 30, 60 or 90 days.

   A daily job (06:00 UTC; change it with `CERTIFICATE_REMINDER_CRON`, a Spring cron such as `0 0 6 * * *`) reminds
   about certificates of active users that expire within 90, 60 or 30 days: the crew member gets an email and an in-app
   notification, every active office user gets an email. Each certificate gets one reminder per threshold; one entered
   late (e.g. 45 days before expiry) gets only the 60-day reminder now, not the 90-day one as well. Changing the expiry date starts the reminders again.
   To try it without waiting, set `CERTIFICATE_REMINDER_CRON` to e.g. `0 */1 * * * *` (every minute), add a
   certificate expiring in 60 days, and watch Mailpit.

13. Notifications (any signed-in user): the bell in the top bar (phones) and **Notifications** in the sidebar (a pill,
   see "sidebar navigation" below) show the number of unread notifications. `/notifications` lists them, newest first; opening it marks them as read
   (new ones stay highlighted on that visit).

14. Announcements (signed in as an office user): **Announcements** in the sidebar (`/office/announcements`) lists every
   announcement, newest first, with its target (All ships or a ship), date and e.g. "1 of 2 acknowledged".
   Filter by ship to see the announcements sent to that ship or to all ships.
   **New announcement** asks for the target (All ships or one active ship; nothing is preselected), a title and a
   plain-text message. Announcements cannot be edited or deleted, so sending takes two steps (P10):
   - the form's button is **Review** (`POST /office/announcements/review`): an invalid form shows its errors; a valid
     one shows the review page, which saves nothing: the target (as the eyebrow and as "Send to"), the number of people
     per ship (every active ship for All ships, with a Total, and a warning when nobody would receive it) and the
     title and message exactly as the crew will see them (plain text, line breaks kept). The page carries the form in
     hidden fields;
   - **Back to edit** (`POST /office/announcements/new`) shows the form again with the text as it was. An old form
     page that still posts to that URL also just gets the form back: nothing is sent from it;
   - **Send to 12 people** (`POST /office/announcements/send`) checks the form again (a ship deactivated meanwhile is
     an error on the target), counts the recipients again and sends; the message says how many it went to
     ("Announcement sent to 13 people.").

   The recipients are fixed when it is sent: every active user with a ship role on that ship
   (on every active ship for All ships). Someone who joins the ship later is not a recipient; someone who leaves the
   ship or is deactivated later stays in the list. Opening an announcement shows its text and two lists:
   acknowledged (name, role, ship, time) and not yet acknowledged. Announcements cannot be edited or deleted.
   Crew members cannot acknowledge yet (their pages come later), so every recipient shows as "not yet".

15. Approved requests (signed in as an office user): **Requests** in the sidebar (`/office/requests`) lists the item
   requests approved by a Master, newest approval first, with ship, requested by, approved by, approved at, number of
   lines and an "Exported" mark. Filter by ship and "Not exported yet". Click a request to see its items
   (`/office/requests/{id}`). Creating and approving requests on board come later; the dev profile seeds two (see 3).

   Check one or more requests and click **Export CSV** to download `approved-requests-<date>.csv` (UTF-8 with BOM,
   so Excel shows Greek correctly), one row per request line:
   `imo_number,ship_name,request_id,approved_at,approved_by,item_name,unit,quantity` (`approved_at` in UTC, e.g.
   `2026-11-10T09:30:00Z`). The exported requests get the "Exported" mark (reload the page to see it) and the export
   is recorded in the audit log. A request can be exported again.

16. Ship library (signed in as a ship user, e.g. `master.demo@example.com` / `crew123`): **Library** in the sidebar
   (`/ship/library`) shows the ship particulars (name, IMO number, flag and the particulars text) and the library
   items of the user's own ship that are not retired, grouped by category (Ship rules, Company policies, Ship plans and
   maps, Onboarding guides, Other), each with its title and version. Opening an item shows its text, or its file
   name and size with **Download**. Items of another ship, retired items and unknown ids give 404. Particulars
   cannot be edited from the app yet (dev data only). Library files are stored in
   `app.library.storage-dir` (dev: `./data/library`).

   **Add an item**: the Master has the button on **Library** (`/ship/library/new`; Chief cook and crew members get
   403). Office users open the **Library** tab of a ship page (`/office/ships/{id}/library`, same content as the crew
   page, see 5) and add items there
   (`/office/ships/{id}/library/new`). The form asks for title, category, version label (e.g. `1` or `2026-10`),
   Required / Not required, and the content: text **or** one file (PDF, DOCX or XLSX, at most 10 MB), not both.
   A ship can have at most 50 items that are not retired. The item is visible to the crew at once. A Required item
   gives every other enabled user of that ship the notification "Library · New document to read", which opens
   the item. To try it: sign in as `master.demo@example.com`, add a Required text item, then sign in as
   `cook.demo@example.com` and check **Notifications**.

   **Acknowledge a Required item** (Master, Chief cook, crew members): on **Library** each Required item shows
   "Required · to acknowledge" or "Required · acknowledged" (for the user's acknowledgement of its *current*
   version). Opening a Required item not yet acknowledged shows **I have read this**; pressing it
   (`POST /ship/library/{id}/acknowledge`) saves the acknowledgement, records it in the audit log and shows
   "Acknowledged · Version <label> · <date and time>" instead. Pressing it again changes nothing. Acknowledging never
   blocks anything else. To try it: sign in as `cook.demo@example.com`, open **Library**, open
   "Drug and alcohol policy" and press **I have read this**, then go back to **Library**.

   **Publish a new version**: the Master sees **Edit this item** on an item of their ship
   (`/ship/library/{id}/edit`; Chief cook and crew members get 403); office users have **Edit this item** on each
   row of a ship's library (`/office/ships/{shipId}/library/{id}/edit`). The form shows the title, category and
   current version read-only, the current text pre-filled (or the current file name), and asks for a new version
   label (required, different from the current one) and new text **or** a new file (same rules as Add an item).
   **Publish** makes it the version the crew see and records it in the audit log. Earlier versions and
   their acknowledgements stay in the database. Nobody has acknowledged the new version, so a Required item shows
   "Required · to acknowledge" again and the ship's users get "Library · New document to read" again. To try it:
   as `cook.demo@example.com` acknowledge "Drug and alcohol policy"; as `master.demo@example.com` open it, press
   **Edit this item**, enter a new version label and press **Publish**; sign in as the cook again.

   **Retire an item**: the Master sees **Retire this item** on an item of their ship (Chief cook and crew members
   do not; `POST /ship/library/{id}/retire` gives them 403); office users have it at the bottom of the item's
   **Edit this item** page (`POST /office/ships/{shipId}/library/{id}/retire`). The confirmation dialog (P10) asks
   "Retire Galley rules? The crew will no longer see Galley rules, and it cannot be brought back." **Retire** hides the item from every list, its page and file
   give 404, it no longer counts towards the 50 items, and it is recorded in the audit log. There is no way back.
   The item stays in the database with its versions and acknowledgements. To try it: as `master.demo@example.com`
   open an item, press **Retire this item**, then **Retire**; it is gone from **Library**.

   **Acknowledgement overview** (Master of the ship and office users, read only; Chief cook and crew members do not
   see it and get 403): on **Library** each Required item shows e.g. "1 of 3 acknowledged", linking to
   `/ship/library/{id}/acknowledgements` (Master) or `/office/ships/{shipId}/library/{id}/acknowledgements`
   (office). The page lists, for the *current* version, who acknowledged it (full name, role, date and time) and who
   has not yet (full name, role). Only enabled users with a ship role who are on that ship now are counted: someone
   who left the ship is not listed, even if they acknowledged. To try it: as `cook.demo@example.com` acknowledge
   "Drug and alcohol policy"; as `master.demo@example.com` open **Library** and click the count next to it.

Run the tests (H2 in memory, no Docker needed):

```
./mvnw test
```

### Frontend assets

The app makes no request to any other host: everything a page needs is served from
`src/main/resources/static/`.

- `vendor/bootstrap/`: Bootstrap 5.3.8 (`bootstrap.min.css`, `bootstrap.bundle.min.js`), unchanged from the
  npm package, with its `LICENSE`. To upgrade, replace both files with the ones from the new release's `dist/`.
- `fonts/`: the Inter variable font (weights 100–900, upright), Latin and Greek subsets as WOFF2, declared with
  `@font-face` in `css/app.css` (`font-display: swap`, system fonts as fallback). Source: the npm package
  `@fontsource-variable/inter` 5.3.0, files `inter-latin-wght-normal.woff2` and `inter-greek-wght-normal.woff2`.
  Licence: `fonts/LICENSE-Inter.txt` (SIL OFL 1.1).
- `favicon.svg`: the wave mark of `layout/parts :: brand`; change both together.
- `css/app.css`: the only custom stylesheet. It defines the design tokens (`--nn-*` CSS variables) once in `:root`
  and maps Bootstrap's variables and components onto them; templates carry no inline styles. The comment at the
  top of the file lists the scales:
  - surfaces, three levels: page `#f4f1ea`, surface `#fffdf8` (cards, modals, tables, list groups, menus),
    field `#ffffff` (inputs, selects, checkboxes, radios; border at least 3:1 against the surface);
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
    sign-in, registration and error pages and for **I have read this**. Every button can be tapped over at least
    40 px (44 px on phones), also the small ones. Four levels: `btn-primary` (filled navy, at most one per view and
    one per modal), `btn-outline-secondary` (secondary: surface, visible border, navy text), `btn-ghost`
    (tertiary: navy text only; low-priority and row actions) and `btn-outline-danger` (only in a confirmation dialog,
    on a confirmation page or in a row's overflow menu, never directly in a row). Filter forms use secondary **Filter** and tertiary
    **Clear**. Disabled buttons are 50% opaque with a not-allowed cursor; the reason is in the text next to them;
  - page header actions sit in `.page-actions` (the `actions` slot of `layout/parts :: pageHeader`) with a 16 px icon
    before the label (`layout/parts :: icon`: plus, upload, download); on phones they stack full width;
  - lists (P04): one table style, `.data-table`, on every office list (ships, crew, office users, invitations,
    certificates, announcements, requests, galley items, audit log). Cells have 12 px top and bottom padding and
    the first and last cell start and end on the card padding, so the card title, the column headers and the
    first column share one left edge. Every row is at least 52 px high, with or without actions, and every cell
    stays on one line so all rows have the same height. Header cells are 12 px, 600, secondary colour, uppercase,
    letter-spacing 0.04em. Rows get a hover background; counts (`.num`) are right-aligned. The record's name
    (`.cell-primary`) links to its page and is the only bold cell; a ship's name opens its ship page (P08). The record
    count sits after the page title, muted ("Crew · 84", see the page header below); on the tabs of the ship page it is
    on the tab instead; list cards have no header of their own. Email addresses never break: they are cut with an ellipsis and the
    full address is in the title attribute (`layout/parts :: email`). Status badges are a dot plus text in the
    status colours (`layout/parts :: status`, tones ok, warning, danger, info, primary, neutral);
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
    The audit log and the import review tables keep their columns and scroll sideways.

  - forms (P05): one pattern on every form (ship, crew, invitation, certificate, item, announcement, library new, edit
    and particulars, registration, crew and item import), in the "Forms" section of `app.css`:
    - layout: the form card (`.form-box`) is at most 720 px wide; fields sit in `.form-grid`, one per row, and from
      768 px short related fields (`.field-half`) two per row (Type + Flag, Rank + Role, Role + Rank, Number + Expires
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
    - character limits: every field with a `@Size(max)` in its form class shows a counter under it
      (`layout/parts :: counter`; the limit is read from the form class by the `formFields` bean,
      `core/form/FormFields`). Without JavaScript it reads "Up to 5,000 characters"; `js/forms.js` turns it into
      "123 / 5,000" while typing, and "5,012 / 5,000 · 12 too many" in red and bold over the limit. Announcement
      messages and ship particulars count a line break as one character, as the server does;
    - pop-up or page: a pop-up only for short forms (add and edit ship, invite office user, add crew member; the
      last one has five fields until P18 shortens it), everything else is a full page;
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
      ("MV Demo · IMO 9074729": item and library forms, crew invitation, request detail, announcement to one ship;
      `pageHeader(eyebrowShip=${ship})`, a link to the ship page for office users) and on the crew library pages;
      "All ships" for a fleet announcement; the status code on the error page. The tabs of the ship page have the
      ship's name as their heading instead. There is no "Office portal" eyebrow;
    - the title (h1), with the record count after it on list pages ("Galley items · 3"); no page shows its title in
      a card header as well. **Export CSV** on **Requests** moved from the card header to the page header and still
      submits the list's form without JavaScript (`form="exportForm"`);
    - an optional subtitle (one line, e.g. "Newest first.", or the category, version and date of a library item) and
      the actions on the right.
    The parameters are named: `pageHeader(heading=..., crumbs=~{::pageCrumbs}, eyebrow=..., count=..., subtitle=...,
    actions=~{::pageActions})`; the comment above the fragment shows how to define the crumbs
    (`layout/parts :: crumb(label, url)`) and actions. Pages pass their name to the layout as text,
    `layout(#{ship.list.title}, ~{::main})`: the browser tab shows "Page name · Nereonet" and, on phones, the top bar
    shows the page name instead of the app name. The content column is at most 1280 px wide, left aligned next to
    the sidebar, with 24 px between the header, the filters and the content; it has `min-width: 0`, so a wide table
    scrolls inside its card instead of widening the page (no page scrolls sideways at 1440, 1024 or 390 px; the
    import review and audit log tables scroll inside their card).

  - sidebar navigation (P07): the navy sidebar (`layout/layout.html`, entries `layout/parts :: navItem`) has small
    uppercase group labels:
    - office: **Operations** (Overview `/office`, Ships, Crew, Certificates, Announcements, Requests),
      **Administration** (Office users, Audit log), **Account** (Notifications, the language switch); crew: **On board**
      (Library; Home and Announcements come with P26 and P27) and **Account** (Notifications). The user block (name,
      role, **Sign out**) is pinned at the bottom; when the window is shorter than the navigation, only the navigation
      scrolls. The brand (logo and name) links to the user's home page (`/office` or `/ship`);
    - the entry of the current section is active: lighter background, a 3 px pink bar on its left edge, white semibold
      text and `aria-current="page"`. Pages below an entry keep it active (`/office/crew/12/edit` → Crew,
      `/office/ships/3/library` → Ships, `/office/invitations` → Office users), also their error pages. Exactly one entry
      is active on every page except `/ship`, which has no entry until P26. The section comes from one place,
      `navigation/NavigationAdvice` (model attribute `navSection`, from the URL via `NavSection`); controllers do not set it;
    - counters, a quiet pill on the right of the entry, hidden when zero, "99+" above 99, with the number in words for
      screen readers: **Certificates** expired or expiring within 30 days (UTC days), **Requests** approved and not
      exported, **Notifications** unread. One count query each on every page (`navCounts` from `NavigationAdvice`,
      office users only; `unreadNotifications` from `NotificationBellAdvice`);
    - phones (below 768 px): the sidebar is an offcanvas opened from the top bar's menu button and closed with Esc or
      the backdrop. Entries are 40 px tall (44 px on phones); Tab goes brand, entries in visual order, language, Sign
      out, then the page.

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
      "sent 4 Nov 2026 · 09:12 UTC · expires in 3 days"). The hint is secondary text (`.date-hint`) that never breaks;
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
      may be someone else); otherwise it is the home page. What was typed into the form is lost.

  - sign-in and registration (P13): a first-time seafarer on a phone gets in without help (see 7 for the flow and
    the messages):
    - one signed-out frame, `layout/parts :: authFrame` (sign-in, registration, invalid link, signed-out error
      pages): the navy brand panel on the left from 768 px; on phones it collapses to a 64 px header bar, so the
      form starts above the fold (at 390 × 844 the **Continue** button ends at about 430 px). The language switch
      (sign-in and registration) is a 40 px (44 px on phones) touch target;
    - password fields (sign-in, both registration fields) have **Show** / **Hide** inside the field on the right
      (`layout/parts :: passwordToggle`; accessible name "Show password" / "Hide password", and a screen reader
      hears "Your password is visible / hidden"); the field is set back to hidden before the form is sent. Under
      the field "Caps Lock is on." appears while the field has focus and Caps Lock is on
      (`layout/parts :: capsLockHint`). Both come from `js/password.js`; without it the button stays hidden and the
      field is an ordinary password field;
    - the registration password rules are a checklist under the field, "At least 8 characters" (the `@Size(min)` of
      `RegistrationForm`, via `@formFields.minLength`) and "Both passwords are the same", which `js/password.js`
      ticks while the user types: a green circle with a check mark and "done" for screen readers, an empty circle
      and "not yet" otherwise. Without the script it is a plain list. The server checks the same rules again; its
      messages stay under the fields.

Links from templates (`@{/css/app.css}`) and `url(...)` in CSS get a content hash in the URL
(`/css/app-<md5>.css`), and these files are sent with `Cache-Control: max-age=31536000, public`
(`spring.web.resources` in `application.yml`). A changed file gets a new URL, so browsers on board load each
version once. Always link static files with `@{...}` so they get the hash; a plain `/css/...` link would be cached
for a year without one. All of these paths are public in `SecurityConfig`, so the sign-in page is styled too.

### Configuration outside dev

Set these environment variables:

| Variable | Example |
|---|---|
| `DB_URL` | `jdbc:postgresql://host:5432/nereonet` |
| `DB_USERNAME` | |
| `DB_PASSWORD` | |
| `MAIL_HOST` | e.g. `smtp.gmail.com` |
| `MAIL_PORT` | e.g. `587` (STARTTLS) |
| `MAIL_USERNAME` | SMTP login, e.g. the Gmail address |
| `MAIL_PASSWORD` | SMTP password; for Gmail an App Password (needs 2-step verification), not the account password |
| `MAIL_FROM` | system sender address; for Gmail the account address or one of its aliases |
| `APP_BASE_URL` | public URL used in email links, no trailing slash, e.g. `https://nereonet.example.com` |
| `CERTIFICATE_REMINDER_CRON` | optional; when the certificate reminder job runs (UTC), default `0 0 6 * * *` |
| `LIBRARY_STORAGE_DIR` | folder for library files, created if missing, e.g. `/var/lib/nereonet/library`; back it up with the database |
| `INVITATION_JWT_SECRET` | random secret, at least 32 characters, for signing invitation links |
| `BOOTSTRAP_OFFICE_EMAIL` | first office user; required only while no office user exists |
| `BOOTSTRAP_OFFICE_PASSWORD` | first office user; required only while no office user exists |
| `SUPPORT_CONTACT` | optional free text shown as "Office contact" on the "link is invalid or has expired" page, e.g. `Crewing department · crewing@example.com · +30 210 000 0000`; not shown when empty |

Outside dev, SMTP login and STARTTLS are required (no plain-text fallback). Keep secrets out of
checked-in files (`application*.yml`, `docker-compose.yml`) and do not enable `mail.debug` or DEBUG
logging for `jakarta.mail` / `org.eclipse.angus.mail`: that output contains the SMTP login.
