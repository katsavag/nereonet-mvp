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
   with **EN | ΕΛ** at the top right of the sign-in page and of every office page (P29). Switching changes only the language: the page, its
   filters, page number and token stay (see "language switch and required notices (P16)" below).

   On first start the dev profile creates an office user (`office@example.com` / `office123`)
   and a demo ship (`MV Demo`, IMO 9074729). While there are no item requests and the first ship (by name) has no
   active Master / Chief cook, it creates the users `master.demo@example.com` and `cook.demo@example.com` (password
   `crew123`). Since P31 the first ship also gets three more crew members with sample profiles for My crew (see 20),
   and since P33, while it has none, two requests waiting for its Master (see 21), with four demo items. Since P36 no
   approved requests are seeded: the Master approves these and sends the ship order (23), which the office then sees
   on **Orders** (24).
   While that ship's library is empty it also gets two text items: "Galley hygiene rules" (Ship rules) and
   "Drug and alcohol policy" (Company policies, required).
   Since P40 (once, while there is no ship with IMO 9300001) three bulk carriers with RightShip data entered by hand,
   calculated 21 Oct 2026, each with its sample report from `samples/rightship/` (read from the working directory, so
   start the app from the project folder): **ALDER** (9300001, Safety Score 3/5, GHG C unverified, inspection valid
   for 38 more days), **LAUREL** (9400007, 5/5, A+ verified, 9 months) and **ROWAN** (9500003, 4/5, B verified,
   6 months). MV Demo has no RightShip data. The sample reports are synthetic images made for the demo, not
   RightShip documents. The demo ship is found by its IMO number 9074729 (the vetting vessels sort before it by name).
   Since P41 (once, while there is no ship with IMO 9600009) the tanker **BIRCH** (9600009) with the SIRE 2.0
   inspection of the synthetic sample report `samples/sire/birch-sire-sample.pdf` (fictional vessel and company, not
   an OCIMF document): 12 Jun 2026 at Piraeus, published 26 Jun 2026, five observations, three closed, one open
   (target in 30 days) and one overdue (target 12 days ago), with the PDF as its report. So the sidebar shows
   **Vetting 2** and the Overview lists ALDER's inspection and BIRCH's overdue observation (see 25).

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

   **Overview** (`/office`, the office home page, P09; redesigned in P43, see 27) is a work list: what needs the
   office, with the next step on each row, so it can be cleared without visiting other pages first. The page action
   is **New announcement**. Under the title four stat tiles, each one link with a detail line: **Active vessels**
   ("2 with open items", to Vessels), **Crew on board** (enabled crew of active vessels; "1 invitation waiting", to
   Crew filtered to Active), **Items needing attention** ("3 snoozed", to the list below) and **Orders waiting for
   the office** (sent and not confirmed; "1 not exported yet", to Orders filtered to Sent). From 1200px the tiles are
   a row of four and the page has two columns: **Needs attention** on the left (7/12) and, stacked on the right,
   **By category**, **Next 30 days**, **Fleet** and **Recent activity**; from 768px the tiles are 2 × 2 and the
   columns go under each other; on phones one column, the tiles two per row.
   - **Needs attention**: one row per open item of an active ship, the total in the card header, most urgent first:
     1. certificate expired; then (P40, P41, see 25) a vessel's vetting: SIRE 2.0 operator comments overdue or due
     within 3 days, RightShip inspection expired, unacceptable or expiring within 60 days, SIRE 2.0 report no longer
     current or ending within 60 days, a SIRE 2.0 observation past its target date, and a RightShip Safety Score of 2
     or below, each with **Open RightShip** or **Open SIRE 2.0**; (P42, see 26) right after the expired certificates,
     one row per seafarer on board with required documents missing or expired ("2 required documents missing", the
     types, **Open crew member**);
     2. certificate expiring within 30 days (UTC dates); 3. order sent by a ship and not
     exported (P36: SENT, CONFIRMED or CLOSED, `exportedAt` empty); then (P44, see 28) an open request for quotes
     with quotes from two suppliers ("2 quotes ready to compare", **Compare quotes**) or past its reply-by date with
     fewer ("Reply-by date passed", **Open request**);
     4. crew invitation expired, or sent (last) more than 48 hours ago and not accepted (worked out from its expiry
     date and `app.invitation.valid-days`); 5. announcement sent, or Required library item published (current
     version), more than 7 days ago that not everyone has acknowledged. Within a kind, the most overdue or oldest first.
     Each row has a status badge with an icon and the kind, a title linking to the record (the certificate, the order,
     the ship's crew list for an invitation, the announcement, the item's acknowledgements), one context line
     (ship · person and rank, or "2 of 5 not acknowledged" · date) and one action: **Open certificate**, **Open order**
     (the order page, where it is confirmed and exported, see 24), **Resend invitation** (new link and expiry date, back to the Overview with "Invitation sent again") or **View who
     has not acknowledged**. A certificate or vetting row also has a checkbox and **Snooze** (P43, see 27); snoozed
     items are left out. The list is paged, 10 rows at a time (20, 50 or 100, the P19 pager; `?page=2`), and sorted
     most urgent first or, with **By date** (`?sort=date`), by the date that drives each row, earliest first. Above
     the rows a line of filter chips, **All** and one per category with its count (Certificates, Required documents,
     Vetting, Orders, Quotes, Invitations, Announcements): links (`/office?type=certificates`, `documents`, `vetting`,
     `orders`, `quotes`, `invitations`, `acknowledgements`), so they work without JavaScript; the **Fleet** card's counts
     filter the list to one vessel (`/office?shipId=…`, "Showing only: MV Demo", with **Show all open items**; a chip
     keeps the vessel). **Open certificate** opens the form, which comes back to this page as it was (page, sort,
     filters) after Save or Cancel. With nothing open the card says "Nothing needs your attention" and when it was
     checked; a chip with nothing says "Nothing needs your attention here".
   - **By category**: one tile per category with its open count in the tone of the category ("3 open" in red for
     certificates, amber for orders) or a green "All clear", each a link to the full list: orders to
     `/office/orders?exported=no` (not exported yet), certificates to `/office/certificates?status=expired,30&activeShips=true`
     (exactly the certificates counted, see 12), vetting to `/office/vetting?status=attention`, required documents to
     `/office/crew?status=active&documents=missing`, the others to this page filtered to that kind.
   - **Next 30 days**: the coming dates in order, at most 8 (certificates expiring, RightShip inspections ending,
     SIRE 2.0 reports ending and operator comments due, order deliveries), each with the vessel and the record link;
     **See all** opens the list by date. Nothing that has passed, nothing snoozed.
   - **Fleet**: one row per active ship, those with open items first, then by name, at most 8 ("The first 8 of 12
     vessels; those with open items first" under the table, with **All vessels** in the card header): the flag and
     the name (the ship page) with the type under it, the RightShip Safety Score (to its Vetting page, see 25) and the
     ship's open items, a link to this page filtered to the vessel; a zero is a muted dash and not a link.
   - **Recent activity**: the last 5 audit entries (who, what, which vessel, the time in UTC), with **Audit log** in the
     card header.
   - `DashboardService.load()` always runs the same 13 queries, whatever the size of the fleet (the active ships with
     their crew count, one per kind of open item, RightShip included since P40, requests for quotes since P44, in `dashboard/DashboardQueries`,
     SIRE 2.0 since P41, two queries in `SireService.attention`, the required documents' rules (P42; two more while
     any are set), the active snoozes and the newest audit entries); `DashboardFlowTest` checks it. The load also
     ends the snoozes that no longer hold (27).
   With the dev data an order the Master has sent is a row until the office exports it (24); then the card says
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
     `DashboardService.setup()` is one query (`DashboardQueries.setup()`), outside `load()`'s twelve.

5. Ships (signed in as an office user): **Vessels** in the sidebar (`/office/ships`).
   Wording (P39): every English text on screen and in emails says "vessel", not "ship" ("Add vessel", "All
   vessels"; `MessageKeysTest` checks it), except proper names: the certificate type "Ship Security Officer" and the
   crew template's column "Ship IMO" (a file format). The code, URLs, database tables, export column names and this README keep
   "ship"; the Greek texts are unchanged. Below, the buttons are named as before P39. **Add ship** (a pop-up form) asks for
   the IMO number (7 digits with a valid check digit, e.g. `9074729`; `IMO 9074729` also works), name,
   type and flag (type to filter the countries, e.g. "Greece (GR)"; only the code `GR` is stored). Ships can be
   edited in a pop-up (except the IMO number) and deactivated or activated, never deleted: on the list both are in the
   row's **⋯** menu (**Edit**, then **Activate** or **Deactivate**). The ship's name opens its ship page.
   P40: a **Safety Score** column (the RightShip score, "3/5", "N/A" when not rated; for an active vessel a link to its
   Vetting page, see 25). The list sorts by name (default) or by Safety Score (`?sort=score`, lowest first, then the
   other way; not rated last either way, then by name); it has no search, filters or pages.
   P43: the **Flag** column shows the country's flag as a small icon (20 × 15, `layout/parts :: flag`, the SVGs under
   `static/vendor/flags/`, see "Frontend assets") before the country name; the same icon with the name appears on
   the ship page header and particulars, the Vetting list, the library's particulars (16) and, for the crew's
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
   - **Library** (`/library`, see 16) and **Items** (`/items`, see 11): the existing pages, now inside the frame.
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

6. Invitations (signed in as an office user). The email with the `/register?token=...` link
   appears in Mailpit.
   - Office users: **Office users** in the sidebar (`/office/users`, see 9), then **Invite office user** (opens a pop-up
     form with full name and email; the email is asked once, and the success message names the address the invitation
     went to, e.g. "Invitation sent to jane@example.com."). There is no separate invitations page any more:
     `/office/invitations` redirects to `/office/users`. Pending invitations are listed there with the users, as
     "Invited", or "Invitation expired" when past their expiry date; accepted and cancelled invitations are not listed
     (an accepted one is a user). Each has **Resend** (new link and expiry date, new email; the old link stops
     working) and, in the row's **⋯** menu, **Cancel invitation** (the link then shows the "invalid or expired" page). Cancel asks
     first: "Cancel the invitation to Jane Doe? The link sent to jane@example.com stops working.", with **Keep
     invitation** and **Cancel invitation**.
   - Crew members: **Add crew member** on the **Crew** page (see 8), or on a ship page's **Crew** tab (see 5), the same
     pop-up form with the ship preselected; `/office/ships/{shipId}/invitations/new` (the demo ship is usually id `1`)
     redirects to that tab with the form open. Crew invitations have the same **Resend** and **Cancel invitation** on
     the Crew list and the Crew tab (P17), with the same endpoints, `POST /office/invitations/{id}/resend` and
     `/cancel`; each returns to the list it was started from (the forms send `tab=crew&shipId=…`, or `returnTo=crew` with
     the Crew list's `shipId` and `status` filters, or `returnTo=overview`; anything else goes to Office users, never
     to a URL from the request). Both are in the audit log with the ship. The form asks for
     full name, email (once; the success message names the address), rank and ship. There is no role to pick
     (P18): the rank sets it, Master → Master, Chief Cook → Chief cook, every other rank (Chief Engineer included) →
     crew member. The rank is required and is copied to the user on registration. Under the rank picker (grouped by
     department: Deck, Engine, Catering) a read-only line says what the rank gives, e.g. "Access: Master · can publish
     library items and see acknowledgements", and follows the picker as it changes (`js/forms.js`; without the
     script it shows the saved rank's access). A ship can have only one active Master and one active Chief cook;
     inviting a second one shows an error on the rank field.
   - The email (P29) is plain text and HTML in one message (`core/mail/MailService.send(to, subject, text, html)`,
     `multipart/related` holding `multipart/alternative` text + HTML, then the logo). The HTML is
     `templates/email/action.html`, filled from a `core/mail/ActionEmail` by `core/mail/HtmlEmails`: a 600 px table
     layout with inline styles only (`DesignTokensTest` exempts `templates/email/`), Inter first in the font stack
     with system fallbacks; a navy `#10223a` header with the logo (`img/brand/nereonix-logo-on-dark-600.png` attached
     inline as `cid:nereonix-logo`, never a remote image) and "Nereonix" as text; a white card on the cream `#f4f1ea`
     page with "Hello Jane Doe,", who invites to what ("Maria Papadopoulou invited you to join MV Aegean Star as
     Second Officer.", or "… to join the office."), one navy **Accept invitation** button (44 px), the link as text
     under it, the expiry in UTC; then a footer: who sent it, why the person got it and, when `SUPPORT_CONTACT` is
     set, "Questions? Contact: …". Every text has its own colour on its own background and
     `color-scheme: light`, so dark-mode clients and blocked images leave it readable. The texts are `email.*`
     message keys (`core/invitation/InvitationEmails`). Crew emails are in English; office emails are in the language
     of the office user who sends (or resends) them. The password reset email (17) uses the same template.
     In dev, `/dev/email/invitation` (office: `?office`, Greek: `&lang=el`) and `/dev/email/password-reset` show
     the HTML with sample data (`dev/DevEmailPreviewController`, dev profile only, signed in).
   - Landing in the inbox, not in spam (P30). The app does its part: the sender has a name, "Nereonix
     <noreply@…>" (`MAIL_FROM_NAME`, default "Nereonix"; a name written in `MAIL_FROM` itself wins); the Message-ID
     ends in the sender's domain, not the computer's name (`spring.mail.properties.mail.from`); the inbox preview line
     is hidden with `display:none` only, never as text in the background colour or 1 px high; there is a plain-text
     part and no remote image. Most of the decision is not in the email, though:
     - a new Gmail address that suddenly sends invitation emails with a link to `http://localhost:8080` looks like
       phishing to Gmail. In development, mark the first one **Not spam** and add the sender to the recipient's
       contacts; Gmail learns from that;
     - for real use send from the company's own domain (for example `noreply@crew.example.com`) through Google
       Workspace or a transactional mail service, with SPF, DKIM and DMARC set up in its DNS, and use an `https`
       `APP_BASE_URL` on the same domain, so the links match the sender.

7. Registration: open the `/register?token=...` link from the invitation email (signed out, e.g. in a
   private window) and set a password. Opened while someone is still signed in (for example the office user who
   has just sent the invitation, P30), the page shows on its own, never inside the sidebar and top bar, with "You are
   signed in as John Doe. Creating this account signs you out and signs you in to the new account." The page says what the person is joining: "You are joining MV Demo as Second
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
   and every pending crew invitation in one list, searchable (name or email) and filterable by ship and by **Status**: Active, Invited, Invitation expired,
   Inactive (query parameters, so other pages can link to a filtered list: `/office/crew?status=expired`,
   `?shipId=1&status=invited`; an unknown value shows every status), sortable by name (the default), email, rank, ship
   and status, 10 per page, 20, 50 or 100 on request (P43; see "lists: search, sort and pages (P19)" below; users and invitations are paged together
   in the database, one native SQL `UNION ALL` in `crew/PeopleQueries`). The ship's **Crew** tab is the same list for
   that ship, without the ship filter. An invitation shows its status with its date,
   "Invited · expires in 3 days" or, in the warning tone, "Invitation expired · 2 days ago", and has **Resend** (one
   click, also when expired: new link, new expiry date, new email) and, in the row's **⋯** menu, **Cancel invitation**
   (asks first, see 6); both come back to the list as it was: search, filters, sort and page (P17, P19). **Add crew member** opens a pop-up form
   (full name, email, rank, active ship; no role, see 6) that sends an invitation email (see Mailpit) and names the address in
   its success message;
   the member appears at once as "Invited" and becomes a user when they register. Clicking a member's name (or **Edit** in the row's **⋯** menu) opens their page; it changes full name, rank
   and ship (active ships only; the email cannot be changed), and saving sets the rank's role. Picking another ship
   moves the crew member. The one active Master / Chief cook per ship rule is checked here too, on the rank field.
   The list shows the rank; there is no Role column, only a small access badge after the rank for a Master and a
   Chief cook ("Master access"). Users from before P18 whose rank does not set the role they have, or who have no
   rank, keep their role: they are listed in a **Rank needs review** notice above the list (for the list as
   filtered), each linking to their page, which names the access they have now ("Access now: Master. Saving applies
   the access of the rank above."). Saving there applies the mapping. Under the form a **Profile** card (P31) shows,
   read only, what the crew member has filled in about themselves (photo, languages, nationality, bio; "Not given"
   where empty): only they can change it (see 20). Deleting a crew member also deletes their photo files.
   P42 (see 26): a **Documents** column ("5 of 7" required documents held, sortable), the **Missing documents**
   filter (`?documents=missing`), **Export required documents** and a **Required documents** card on the member's
   page; the Rank needs review notice also lists crew without a vessel.

   **Import from file** (`/office/crew/import`) invites many crew members at once, from the file the crewing system
   produces or from the template. Four steps, shown at the top of every page (**Upload**, **Map columns**,
   **Review**, **Done**; the current one marked, `aria-current="step"`), with **Cancel** on each (it forgets the
   upload and goes back to the crew list). Nothing is saved and no email is sent before **Import and invite** on
   the Review step. The steps are shared with the item import (see 11 and "Import from a file" under Frontend assets):
   - **Upload**: any `.xlsx`, `.xls` or `.csv` (UTF-8, or Windows-1252 when it is not valid UTF-8; `,` or `;`, whichever
     the first lines use), with any column names in any order; the column names need not be in the first row. A
     workbook with more than one visible sheet asks which sheet holds the rows (the one whose columns look most like
     the fields is chosen already). The easy path stays: **Download template** gives `crew-template.xlsx` (sheet
     "Crew" with `Full name | Email | Rank | Ship IMO`, sheet "Instructions" with the rank values, the access each
     gives and the active ships, in the page's language). A file with exactly the template's columns skips the
     mapping and goes straight to Review.
   - **Map columns**: for each field (Full name, Email, Rank, Ship) a list of the file's columns ("C · Seafarer ·
     e.g. Stavros Pelagidis"), filled in with the best guess from the column names (crewing-system names such as
     Seafarer, Position, Vessel IMO No., E-mail address; also Greek ones), and a preview of the first five rows with
     the field each column goes to. Full name can come from two columns (first name and last name: "Given name" +
     "Surname" are paired by themselves). Columns no field uses are ignored and listed under "Not imported". If a
     title or notes come before the column names, **Column names are in** picks another row (among the first 20).
     **Continue** is blocked, with the reason next to the field and in the error summary, while a required field
     has no column or one column is used twice.
   - **Match values**: each distinct rank not written as in Nereonet ("C/E", "Ch. Eng.", "2/O", "Bosun" is exact)
     is asked once, with a suggestion selected (short forms and abbreviations); the answer applies to every row with
     that value. "Decide per row on the review" leaves it for the Review step. The ship is matched by IMO number
     ("9074729" or "IMO 9074729") or by the exact name (ignoring case) of one active ship.
   - The confirmed column and value mapping is saved when the import is done (table `import_mapping`, per kind,
     keyed by the file's column names in any order) and filled in again next time a file with the same column names
     is uploaded ("Filled in from the mapping saved on 12 Nov 2026."). A template file saves none.
   - **Review**: every row, editable, with errors highlighted and the message next to the field (missing name or
     email, invalid, existing or repeated email, missing or unknown rank, no active ship with that IMO number, more
     than one Master / Chief cook per ship, shown on the rank). Under each rank its access in short ("Access:
     Master"). Each row says what it will do (New), and above the table "Will import: 3 new, …". Filter chips **All**,
     **With errors**, **Excluded** (with counts; they keep edits made before they are pressed); **Exclude all rows
     with errors** in one action; exclude single rows with the checkbox; **Check again** re-validates (P39: with
     `js/import-check.js` it is rarely needed: each cell checks itself as it is corrected, 400 ms after the last key,
     against the same rules as the server (required, too long, email format, a rank and a ship chosen); a cell that
     is right again loses its error at once, a wrong one shows it when the person leaves the cell. The "With errors"
     count, **Exclude all N rows with errors**, the error total and **Import and invite** with its hint follow. What
     only the server knows (an email that already has an account, a repeated one) stays shown while the value is the
     one it checked. The server checks every row again on submit; without the script the page works as before); **Change
     column mapping** goes back. The row number and name columns stay in place when the table scrolls sideways. A
     file with a `Role` column (the template from before P18) is accepted: that column is ignored and the review
     page says so. **Import and invite** (enabled when no included row has errors) sends one invitation email per
     row.
   - **Done**: how many were invited and skipped (excluded), with a link to the crew list.
   - To try it on the dev database: upload [`samples/crew-sample.xlsx`](samples/crew-sample.xlsx), 10 invented crew
     members (example.com emails) for the demo ship `MV Demo` (IMO 9074729): one Master, one Chief cook and eight crew
     members. Regenerate it with `./mvnw test -Dtest=CrewSampleFileGenerator -DgenerateSample=true`.

9. Office users (signed in as an office user): **Office users** in the sidebar (`/office/users`) lists the office users
   and the pending office invitations together, with **Invite office user** (see 6). Search (name or email), a
   **Status** filter, sorting by name, email and status and pages as on Crew (paged in the database the same way).
   Any user, crew or office, can be deactivated (they can no longer sign in) and activated again, from the **⋯** menu
   at the end of their row (here and on **Crew**). **Deactivate** asks first ("Deactivate Jane Doe? Jane Doe will not be
   able to sign in until activated again."; a session that is already open is not ended). A deactivated user can also
   be deleted (**Delete** in the same menu, after the confirmation "Delete Jane Doe? Jane Doe (jane@example.com) will be
   deleted for good and cannot be restored."): the user and every invitation for their email are removed,
   so they can be invited again. Their audit log entries stay and show "Deleted user"; invitations they sent stay.
   They are removed from the recipient lists of announcements; announcements they sent stay and show "Deleted user".
   You cannot deactivate or delete yourself.

10. Audit log (signed in as an office user): **Audit log** in the sidebar (`/office/audit`). Lists who did what and
   when, newest first, 10 rows per page by default (P43; 20 since P39, 25 before), 20, 50 or 100 on request (paged in the database), searchable (who
   did it, by name or email, and the details), filterable by ship and date range (UTC days), sortable by when, who,
   action and ship. Under the table "1 to 10 of 27" and **Previous** / **Next**; the pager, the page size and the sort
   keep the search and the filters. **From** and **To** accept dates from 30 years ago to 1 year ahead (see the date
   limits in 12); a date outside them is shown on the field with the limits, an impossible one (`from=2026-02-31`
   in the URL) with "Enter a date.", and the list is not filtered by it. Recorded so far: ship added,
   edited, deactivated, activated; invitation sent, resent, cancelled; crew imported (counts only); user registered, edited, deactivated, activated, deleted; password reset (the user, by their email; see 17);
   item added, edited, deactivated, activated; items imported (counts only); certificate added, edited, deleted;
   certificate file added, replaced, removed (P39, with the file name); certificate reminder sent (by "System");
   announcement sent, announcement read (by the crew member, with the title, the first time they open it: P39, before
   "announcement acknowledged"); order sent, order confirmed (port and delivery date, "corrected" when changed again), orders exported
   (counts and ids; before P36 "requests exported").
   Entries are read-only.

11. Galley items (signed in as an office user): the **Items** tab of a ship page (`/office/ships/{id}/items`, see 5).
   Each item has a name (unique per ship, ignoring case), a short unit (e.g. `kg`, `lt`, `pcs`), a required category
   (P32, `core/item/ItemCategory`): **Provisions** (food and drink for the ship's meals) or **Personal items** (things
   crew buy for themselves, such as cigarettes, soft drinks, toiletries), and, for provisions only, an optional
   **Place** where it is stored (e.g. "Dry store", "Cold room"). On the add and edit forms Category is a select and
   Place shows only while Category is Provisions (`js/forms.js`, `data-shown-for`; without the script it stays
   visible, and the place of a personal item is never saved).
   **Add item** and the item's name (or **Edit** in the row's **⋯** menu) open a form; items can be deactivated and activated (the row's **⋯** menu), never deleted. Deactivate asks first
   ("The crew of MV Demo cannot request Basmati rice until it is activated again."). The list shows name, unit,
   category, place and status; it is searchable (name and place), filterable by status (Active, Inactive) and
   category (Provisions, Personal items), sortable by name (the default), unit, category, place and status, 10 (20, 50 or 100)
   per page (paged in the database).
   **Import** (`/office/ships/{id}/items/import`) adds and updates many items at once, from the file the purchasing
   system produces or from the template, in the same four steps as the crew import (see 8): **Upload** (`.xlsx`,
   `.xls` or `.csv`, any columns; **Download template** gives `items-template.xlsx` with
   `Name | Unit | Category | Place` and an **Instructions** sheet in the user's language, which skips the mapping),
   **Map columns** (Name, Unit, Category, and the optional Place; guesses such as Description, UoM, Product group,
   Storage), **Review**, **Done**. There are no values to match: Category must be `Provisions` or `Personal items`
   (ignoring case), and Place is kept for provisions only. The mapping is saved and suggested again
   as for crew, for every ship (it describes the purchasing system's file, not the ship).
   - **Review**: every row, editable. A name the ship does not have (ignoring case) is **New**; a name it has
     **Updates** that item's unit, category and place, or is **No change** (skipped) when they are the same. The rows
     that add or update are checked (missing name, unit or category, a category that is not Provisions or Personal
     items, value too long, name repeated in the file). Filters, **Exclude
     all rows with errors**, the live check of each cell (P39) and the sticky first columns as for crew.
   - **Import items** saves the new items and the updates; **Done** shows how many were added, updated and skipped,
     with a link to the ship's items. Nothing is saved before that. An import started for one ship is never continued
     or saved for another: its steps lead back to that ship's upload.
   To try it, save this as `items.csv` and upload it twice (the second time all three are "No change"; change a unit
   to see an update):

   ```
   Name,Unit,Category,Place
   Basmati rice,kg,Provisions,Dry store
   Eggs,pcs,Provisions,Cold room
   Cigarettes,box,Personal items,
   ```

   Data model (P32): `item.category` was free text and is now `ItemCategory` (stored as its name), and `item.place`
   is a new nullable column. Hibernate keeps the old category column, so on startup `core/item/ItemCategoryMigration` sets
   `PROVISIONS` on every item whose category is missing or not `PROVISIONS` / `PERSONAL_ITEMS` (every item before
   P32 was a provision) and makes the column not null. It changes nothing on a new database. For development you can
   simply recreate the database instead (drop it, or `docker compose down -v`, and start the app again: Hibernate
   creates the schema and the dev profile seeds the demo data).

12. Certificates (signed in as an office user): on a crew member's **Edit** page (`/office/crew/{id}/edit`) the
   **Certificates** card lists their certificates (type, optional number, expiry date) with **Add certificate**,
   **Edit** and, in the row's **⋯** menu, **Delete** (after the confirmation "Delete STCW Basic Safety?").
   **Certificates** in the sidebar (`/office/certificates`, P20) lists every certificate, soonest expiry first. The
   **Expires on** column shows the date and how far it is ("in 23 days", "expired 5 days ago"); the status badge has
   an icon as well as a colour: a warning triangle for Expired, a clock for 30, 60 or 90 days, a tick for Valid.
   Searchable (type, number, crew member), sortable by expiry, type, crew member, ship and number, 10 per page (20, 50 or 100)
   (paged in the database), filterable by ship, **Active ships only** (`activeShips=true`) and **Status**
   (`?status=`): `expired`; `30`, `60`, `90` (not expired, expiring within that many days, today included, so
   "Within 60 days" includes "Within 30 days"); `valid` (more than 90 days left); `all`. Without a status, or with one
   it does not know: expired and within 90 days, as before. States combine with a comma, which is how the counts link
   to exactly what they count (each tested in `CertificatePlanningFlowTest`):
   - the sidebar counter (expired or within 30 days, every ship): the **Certificates** entry opens
     `?status=expired,30` while the counter shows; without a counter, the list as it is;
   - the Overview's **By category** tile for certificates (the same, crew of active ships; P43, "Full lists" before):
     `?status=expired,30&activeShips=true`;
   - the ship page's **Certificates** tab count (expired or within 90 days): the tab's **List** view.
   P43: the crew member's name in the list links to their page (`/office/crew/{id}/edit`), as in the Crew list; each
   row has a checkbox and **Snooze**, a snoozed certificate's row is muted with "Snoozed by … until …" and **Undo**,
   and the Status filter has **Snoozed** (`?status=snoozed`; the other states leave snoozed certificates out, see 27).
   **Export** (page header) downloads the list as filtered, searched and sorted, every page, as an Excel workbook
   (P39; CSV before; `.xls` since P40): `certificates-<date>.xls`, sheet "Certificates", with the columns
   `imo_number, ship_name, crew_member, rank, type, number, expires_on, days_left, status` (rank as its code,
   `SECOND_OFFICER`; `expires_on` a date cell, `days_left` a number, negative once expired; status `expired`,
   `30_days`, `60_days`, `90_days` or `valid`, the badge). It is a plain link (GET, nothing changes, not in the audit
   log).

   **The export format** (P39, every office export: certificates, the certificate matrix and ship orders, see 24;
   `core/export/XlsExport`, Apache POI, already used by the imports): since P40 one `.xls` workbook (Excel 97-2003,
   POI's `HSSFWorkbook`, so it also opens in older spreadsheet programs) with one sheet named after
   the list; the first row holds the column names (never translated: they are the format), bold, frozen (it stays on
   screen while scrolling) and with an autofilter on every column; then one row per record. Dates are real date
   cells shown as `13 Nov 2026` (`dd mmm yyyy`), a date and time as `10 Nov 2026 09:30` (UTC), numbers are numbers,
   anything else is text (an IMO number stays text: it is an identifier); an empty value is an empty cell. Column
   widths fit the longest value (8 to 60 characters). The file name is the list's name and the UTC date,
   `<name>-2026-11-13.xls`; the response is `application/vnd.ms-excel`, an attachment that is never cached. The
   format's limits: at most 65,535 rows of data and 256 columns; a bigger export is refused and the page says "Too
   many rows for one file: narrow the filters" (nothing is downloaded); a text over 32,767 characters is shortened
   and ends with "…". Tests read the workbook back (`XlsSheet` in the tests; `XlsExportTest` for the limits). The
   imports still read `.xlsx`, `.xls` and `.csv`, and the import templates (8, 11) stay `.xlsx`: they are not
   exports.

   **Certificate file** (P39): on **Add certificate** and **Edit** an optional scan of the certificate, PDF, JPEG or
   PNG, at most 10 MB (said before choosing; with `js/forms.js` the chosen file's name and size show, with a warning before a file
   over 10 MB is sent). The server checks the extension and the file's first bytes; a file of another kind: "Only
   PDF, JPEG or PNG files can be attached."; too large: "The file is larger than 10 MB. Choose a smaller file.". Editing a certificate with a
   file shows its name, **Open** and **Remove the file**; a new file replaces the old one. Files are stored under
   `<app.certificate.storage-dir>` (default `<app.library.storage-dir>/certificates`, back it up with the library) as
   `<uuid>.<ext>`; a replaced or removed file, or that of a deleted certificate, is removed after the change is
   committed. The audit log records "Certificate file added", "Certificate file replaced" and "Certificate file
   removed" with the file name. A certificate with a file shows a paperclip **Open** link on the crew member's page,
   on the Certificates list and on the crew member's **My profile** (20). Only the office
   (`/office/crew/{userId}/certificates/{id}/file`) and the certificate's owner (`/ship/profile/certificates/{id}/file`)
   can open it, inline in a new tab and never cached; anyone else on board gets 404 for the `/ship` URL (and 403
   for the office URL, as every office page). A crew member who is deleted takes their certificates with them in the
   database, but their files stay on disk (see the report of P39).

   **Date limits** (P39): a date field refuses a date outside a plausible range, both in the browser (`min` and `max`
   on the date input) and on the server (`core/form/PlausibleDate`, measured from today in UTC), with "Enter a date
   between 13 Nov 1996 and 13 Nov 2056." on the field; a date that is not a date at all: "Enter a date.".

   | Field | Earliest | Latest |
   | --- | --- | --- |
   | Certificate **Expires on** (12) | 30 years ago | 30 years ahead |
   | Order **Needed by** (Master, 23) | today | 2 years ahead |
   | Order **Delivery date** (office, 24) | 1 year ago | 2 years ahead |
   | Audit log **From** / **To** (10) | 30 years ago | 1 year ahead |

   The imports have no date columns. Dates saved before P39 are not changed. To find the ones outside these limits
   (PostgreSQL; nothing is changed automatically, correct them by hand in the app):

   ```sql
   SELECT id, user_id, type, expires_on FROM certificate
    WHERE expires_on < CURRENT_DATE - INTERVAL '30 years' OR expires_on > CURRENT_DATE + INTERVAL '30 years';
   SELECT id, ship_id, status, needed_by, delivery_date FROM ship_order
    WHERE needed_by > CURRENT_DATE + INTERVAL '2 years'
       OR delivery_date < CURRENT_DATE - INTERVAL '1 year' OR delivery_date > CURRENT_DATE + INTERVAL '2 years';
   ```

   (A Needed by date in the past is normal for an order sent earlier, so only the upper limit is checked there.)

   The ship page's **Certificates** tab (`/office/ships/{id}/certificates`) is a matrix: the active crew on board as
   rows, by department and rank (Rank's order, "Rank not set" last), and the certificate types they hold as columns,
   the most held first. Each cell shows the expiry date and the badge of that person's certificate of that type that
   expires last (a renewed certificate replaces the old one) and opens it; a dash when there is none, which opens
   **Add certificate** with the person and the type filled in. The crew column stays in place while the table scrolls
   sideways (also on phones; the table is a keyboard-focusable region). **Matrix** / **List** switch between the
   matrix and the list above for this ship (`/certificates/list`, without the ship filter, the Ship column and Active
   ships only). **Export** downloads the view on screen (P39: Excel, see the export format above): the matrix as
   `certificate-matrix-<IMO>-<date>.xls`, sheet "Certificate matrix" (`crew_member, rank`, then one column per type
   with the expiry date as a date cell, empty when there is none), the list as `certificates-<IMO>-<date>.xls`.
   P42 (see 26): the types required of someone on board are columns too, and a required cell with no certificate
   shows **Missing** (danger, icon and word; `missing` in the export) instead of the dash.

   Certificate type is free text with suggestions (a datalist): the types already used, most used first, then the
   starter list (STCW Basic Safety Training, Certificate of Competency, Flag State Endorsement, GMDSS Operator,
   Advanced Fire Fighting, Proficiency in Survival Craft, Medical First Aid, Security Awareness, Ship Security Officer,
   Tanker Endorsement, Medical Fitness Certificate, Seaman's Book, Passport, Yellow Fever Vaccination, US Visa C1/D).
   On save a type that differs from a known one only in case or surrounding spaces is stored as that one
   (`certificate/CertificateTypes`): "stcw basic safety training " becomes "STCW Basic Safety Training", the most used
   spelling, else the starter list's. The certificate being edited does not count, so the only certificate of a type
   can still correct its spelling. Different spellings already in the database are shown as one type in the
   suggestions and in the matrix.

   **Add certificate** and **Edit** go back to where they were opened after Save or Cancel: the Certificates list with
   its filters, page and sort, the ship's matrix or list, the Overview, the ship's Overview tab, else the crew
   member's page (or, from the ship, the tab). The page travels as `returnTo` on the link and as a hidden field;
   only those office pages are accepted (`certificate/CertificateReturn`), anything else goes to the usual page.
   **Delete** still returns to the crew member's page.

   A daily job (06:00 UTC; change it with `CERTIFICATE_REMINDER_CRON`, a Spring cron such as `0 0 6 * * *`) reminds
   about certificates of active users that expire within 90, 60 or 30 days: the crew member gets an email and an in-app
   notification, every active office user gets an email. Each certificate gets one reminder per threshold; one entered
   late (e.g. 45 days before expiry) gets only the 60-day reminder now, not the 90-day one as well. Changing the expiry date starts the reminders again.
   To try it without waiting, set `CERTIFICATE_REMINDER_CRON` to e.g. `0 */1 * * * *` (every minute), add a
   certificate expiring in 60 days, and watch Mailpit.

13. Notifications (any signed-in user): the bell in the top bar (phones) and **Notifications** in the sidebar (a pill,
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

14. Announcements (signed in as an office user): **Announcements** in the sidebar (`/office/announcements`) lists every
   announcement, newest first, with its target, date and e.g. "1 of 2 read" (P39: the crew read an announcement by
   opening it, see 19; before, "acknowledged" by a button). The target (P28) is "All ships",
   the ship's name (a link) for one ship, or "3 ships" with the names as its title (and, for screen readers, as hidden
   text; on the phone card they show under it). Filter by ship to see the announcements sent to all ships or with that
   ship among their targets; search the title; sort by sent (the default, newest first), title and target (All ships,
   then one ship by name, then several ships, fewest first); 10 per page, 20, 50 or 100 on request (paged in the database, the
   acknowledgement counts read for that page only, and the target ships of the page with one more query).
   **New announcement** asks for the target, a title and a plain-text message. The target (P28) is one of two options,
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
   (the subject is their ship) that opens the announcement on their own page (19); there the eyebrow is All ships or
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

15. Approved requests: removed in P36. The office no longer sees single approved requests (`/office/requests`, its
   CSV export, the ship page's Requests tab, `ItemRequest.exportedAt` / `exportedBy` and `RequestExport` are gone): the
   Master collects them into one ship order and sends that (23), and the office works on the orders (24). The text
   below is how it worked until then. Hibernate does not drop columns or tables, so an existing database keeps
   `item_request.exported_at`, `item_request.exported_by_id`, `request_export` and `request_export_request`, unused;
   drop them by hand if you like.

   Until P36: **Requests** in the sidebar (`/office/requests`) listed the item
   requests approved by a Master, newest approval first, with ship, requested by, approved by, approved at and number
   of lines. Filter by ship and **Export status** (`?exported=`): **Not exported yet** by default (also `no`, and
   any value it does not know, such as an old `?notExported=true` link), **Exported** (`yes`) or **All** (`all`);
   these two add an **Exported** column: when, in UTC, and by whom ("12 Nov 2026 · 10:32 UTC", "by John Doe", or
   "Not yet"). Search the number (`#12`), the people, the ship name or IMO number; sort by approved at, number, ship,
   requested by and approved by; 25 or 50 per page (paged in the database). Click a request (anywhere on its row but
   the checkbox; its number is the link for the keyboard) to see its items (`/office/requests/{id}`). The Master sees
   the requests waiting for them on board (21); creating and approving them there come later. The dev profile seeds
   two approved (see 3) and two waiting (see 21).

   Exporting (P21), three clicks at most:
   - **Select** rows with their checkboxes, or all rows of the page with the checkbox in the table header (on phones,
     where the header is hidden, **Select all on this page** above the list; half-checked when some are). The line
     above the table says how many are selected ("3 selected"); **Export selected** is disabled until at least one
     is. Or, without selecting, **Export all not exported (N)** exports every approved request not exported yet under
     the ship filter (every ship without one; on the ship's tab, that ship). It is not shown when there is none.
   - Both are a POST that records the export (`RequestExport`: who, when, which requests; audit log "Requests
     exported" with the count and ids), marks the requests exported now by the office user, and redirects to the list
     showing exactly them, exported (`?exported=yes&exportId=…`, chip "Export: 12 Nov 2026 · 10:32 UTC"; the ship
     filter kept) with "3 requests exported · Download again". No reload is needed: the rows show as exported and the
     sidebar counter has dropped. The file is a GET of that export, `/office/requests/exports/{id}`, which the page
     starts by itself (`js/download.js`); **Download again** repeats it (downloading changes nothing). Without
     JavaScript the link is there to click.
   - Selecting requests exported before asks first (P10, neutral): "Export requests that were exported before?
     Some of the selected requests were exported before. They will be in the file again and marked as exported now,
     by you." **Export again** / **Cancel** (without JavaScript the same question as a page).
   - Nothing selected: "Select at least one request to export."; nothing left to export (another office user was
     quicker): "Nothing to export: every approved request has been exported."

   The file `approved-requests-<date of the export>.csv` (UTF-8 with BOM, so Excel shows Greek correctly) has one row
   per request line: `imo_number,ship_name,request_id,approved_at,approved_by,item_name,unit,quantity`
   (`approved_at` in UTC, e.g. `2026-11-10T09:30:00Z`).

16. Ship library (signed in as a ship user, e.g. `master.demo@example.com` / `crew123`): **Library** in the sidebar
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
     `/office/ships/{shipId}/library/{id}/file` (new: the office could not open files before).
   - Office users open an item from its title on the ship's **Library** tab: the read view
     `/office/ships/{shipId}/library/{id}`, the crew's view without **I have read this**. Its actions are
     **New version** (until P22 "Edit this item", on each row) and **Retire** (until P22 at the bottom of the edit page).
   - The Master and the office see the item's **Version history** under it, newest first and read only: version label
     ("Current" on the one the crew see), when it was published (UTC) and by whom ("by a deleted user" when that user
     no longer exists). Chief cook and crew members do not see it.

   **Add an item**: the Master has the button on **Library** (`/ship/library/new`; Chief cook and crew members get
   403). Office users open the **Library** tab of a ship page (`/office/ships/{id}/library`, same content as the crew
   page, see 5) and add items there
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

17. Forgot password (any user, signed out): **Forgot your password?** under the password on the sign-in page opens
   `/password/forgot`, one email field. **Send link** always goes to `/password/forgot/sent`, "If an account exists
   for this address, we sent a link to set a new password.": the same page, byte for byte, for a known and an unknown
   address (it names no address and has no form). The email (Mailpit in dev; in the language of the page it was asked
   from) holds a `/password/reset?token=...` link that works once and for 60 minutes; no password is ever emailed.
   It is plain text and HTML, in the invitation email's template (6, P29), with **Set a new password** as its button.
   - No email goes to an unknown or a deactivated address, nor to an address that already got 5 links within the
     last hour (the 6th request shows the same page and sends nothing). The email is sent on another thread, so the
     answer takes as long for a known address as for an unknown one.
   - The link opens **Set a new password**: the account's email as text, then **New password** and **Repeat new
     password** with the rules, show / hide and checklist of registration (see 7 and "sign-in and registration
     (P13)"). **Save new password** signs the user in at once and opens their home page with "Your password is
     changed. You are signed in.". It records "Password reset" in the audit log (see 10) with the user as actor and
     their email as details; never the password or the link.
   - Using one link stops every other open link of that user. A link that was used, is older than 60 minutes, or
     belongs to a user who was since deactivated or deleted shows "This reset link is invalid or has expired",
     with **Request a new link** (`/password/forgot`).
   - The tokens are signed like invitation tokens (`core/passwordreset/PasswordResetTokenService`, HS256 with
     `INVITATION_JWT_SECRET`), with the audience `password-reset`, so an invitation link is never accepted as a reset
     link. Each link sent is a `password_reset` row (token id, user, email, sent at, expires at, used at), kept for the
     hourly limit.

18. Crew home page (P26, any ship user: Master, Chief cook, crew members): `/ship`, where a ship user lands after
   signing in and where **Home** and the brand lead. Made for a phone first (390 px, one hand, a slow link, a shared
   PC); crew screens are English only. `shiphome/ShipHomeController`, `ShipHomeService`, `templates/shiphome/home.html`
   (it replaces the placeholder `login/ship.html`).
   - **Header**: the ship's name and IMO number as the eyebrow, the person's name as the heading and their rank under
     it (the role when no rank is set), and before the name their profile photo or initials (P31, see 20). Next to
     the name, from 768 px: "Not Demo Cook?" and **Sign out**, one tap from Home, so
     the next person on a shared PC sees whose session it is. Below 768 px they are not repeated there (P39): Sign
     out is under **More** in the tab bar (20).
   - **To read**: the Required library items of the person's ship that they have not acknowledged in the current
     version and the news they have not opened yet (P27, P39, see 19), together, oldest first, with their number
     after the heading. Each is a full-width card (at least 56 px) with the type icon (the announcements icon for an
     announcement), the type ("Library" or "News item"), the title and "Published 12 Nov 2026" or "Sent 12 Nov
     2026" (UTC date), and opens the item (where **I have read this** is) or the news item (read by opening it). Retired items and items of
     other ships never show. A new version puts the item back on the list. Nothing left: "You are up to date."
     **All news** and **Open the Library** follow the list.
   - **My certificates**: the person's own certificates, soonest expiry first (so expired, then expiring soon, then
     valid): type, "Expires 3 Nov 2026 · expired 8 days ago", the number when set, and the expiry badge of the office
     lists (Expired, 30 / 60 / 90 days, Valid; words and icon, not colour alone). Read only, with the note "Contact the
     office to update a certificate." Without any: "No certificates on record." (`crew/parts :: myCertificates`,
     shared with My profile, 20, which adds the file links.) P42 (see 26): when required documents are missing or
     expired, one line above them, "2 required documents missing", links to My profile; the Master's home has it too.
   - **Crew acknowledgements** (the Master only): the Required items that not everyone on board has acknowledged in
     the current version, oldest first, each with "1 of 3 acknowledged" and its bar, linking to the item's
     acknowledgement overview (16). When everyone has: "Everyone on board has acknowledged every required document."
   - From 992 px To read (and the Master's card) and the certificates sit side by side.
   - **Navigation for the crew on phones** (below 768 px): a tab bar fixed at the bottom of the screen (`layout/parts
     :: tabBar`) with **Home**, **News** (P27; "Announcements" before P39), **Library**, **Notifications** and **More** (P31: My profile,
     My crew and Sign out, see 20) (the Notifications unread count as a badge on the icon, in words
     for screen readers, kept up to date by `js/notifications.js` after Mark as read), each a 56 px target, the active
     one navy and semibold with a bar on its top edge and `aria-current="page"`. It replaces the offcanvas menu and
     the bell for ship users; office users keep both. From 768 px the sidebar has the same entries (On board: Home,
     News, Library and, since P31, My crew; Account: Notifications and My profile). The page keeps room for the bar, and the offline banner and the
     pinned **I have read this** sit above it.
   - The page loads no script of its own (only the layout's shared files) and no images, except since P31 the
     person's own 96 px photo when they have added one (about 2 KB, cached for a year). Measured with the dev data
     as `cook.demo@example.com` (Chrome, 390 px): the HTML is 19.6 KB; with an empty cache the whole page transfers
     569 KB (Bootstrap CSS 232 KB and JS 81 KB, `app.css` 126 KB, the shared scripts 61 KB, the Latin font subset
     49 KB). Those files carry a content hash and are cached for a year, so every later visit transfers only the
     HTML. The app does not compress responses, so these are uncompressed sizes.
   - To try it: sign in as `cook.demo@example.com` / `crew123`: "Drug and alcohol policy" is under To read; open it,
     press **I have read this**, then **Home**: "You are up to date.". As `master.demo@example.com` the Crew
     acknowledgements card lists it with its count.

19. Crew news (P27, any ship user; "Announcements" until P39): the crew read the announcements the office sends (14).
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
     the audit log (10) in the same request as the page (`AnnouncementService.open`); opening it again, in a second
     tab or after a resend over a bad link, changes nothing: same time, no new audit entry. There is no **I have read
     this** button and no `POST …/acknowledge` any more. Opening the item does not mark its notification as read
     (Notifications keep their own state, 13).
   - The URLs before P39, `/ship/announcements` and `/ship/announcements/{id}`, answer 301 to `/ship/news` and
     `/ship/news/{id}` (bookmarks, links in older notifications and emails).
   - Someone who is not a recipient (another ship, joined after it was sent) gets 404 on both URLs, records nothing,
     and never sees it in their list. The crew never see who else has or has not read it: only their own state.
   - Unread news is on the Home page's To read (18) until opened.
   - To try it: as `office@example.com` send an announcement to MV Demo (14). As `cook.demo@example.com` /
     `crew123`: **News** has the counter; open the item. As the office, the announcement's page shows "1 of 2 read"
     with Demo Cook, the two ticks and the time under Read.

20. My profile and My crew (P31, any ship user): a seafarer adds a little about themselves, and sees who is on board.
   `profile/ProfileController`, `mycrew/MyCrewController`, `core/profile/` (the `crew_profile` table, `ProfileService`,
   the photo handling), `templates/profile/` and `templates/mycrew/`; English only, as every crew screen. Every field
   is optional, and what a person fills in is seen by everyone on board the same ship now and by the office.
   - **My certificates** (P39): under the four rows of My profile, the person's own certificates, as on Home (18),
     with "Only you and the office see your certificates. The office keeps them up to date: tell them when
     you renew one." and, for a certificate with a scan, a paperclip **Open**
     link (`/ship/profile/certificates/{id}/file`, see 12). Their crewmates never see them (My crew is unchanged).
     P42 (see 26): the required documents missing or expired come first, marked **Missing** or **Expired**, with the
     office's note and "Contact the office to add it."
   - **Where**: on phones the tab bar's fifth tab is **More** (`/ship/more`): who is signed in, **My profile**, **My
     crew** and **Sign out**. From 768 px the sidebar has **My crew** under On board and **My profile** under Account.
     The person's photo (or initials) is next to their name on Home (18) and on My profile.
   - **My profile** (`/ship/profile`): the office's details as the header (name, "rank · ship", read only), "Your
     crewmates and the office can see what you fill in.", then four full-width rows, each its own page with **Save**
     and **Cancel** (Save comes back with "Saved."; nothing is stored or audited when nothing changed):
     - **Languages** (up to 5, ISO 639-1, in the person's order): the chosen ones with **Move up**, **Move down** and
       **Remove**, and every language (English names) with **Add**. These are links that only change the page's
       working list (`?languages=el,en`); nothing is saved before **Save**. **Find** searches on the server (a GET
       form; accents do not matter, "bokmal" finds Norwegian Bokmål); with `js/profile.js` the list filters as you
       type.
     - **Nationality**: one country from a list (ISO 3166 codes, English names), or "Not given".
     - **Bio**: plain text, at most 280 characters, line breaks kept, with the shared character counter and draft
       keeping (P05).
     - **Photo**: JPEG or PNG, at most 5 MB (said before choosing). With `js/profile.js` the browser first makes it at
       most 800 px on its longest side and shows a preview, so a phone photo is ~100 KB over the satellite link;
       without the script the original is sent. Either way the server reads it (the EXIF orientation applied), makes a
       512 px version (longest side) and a 96 px square, and writes new JPEGs with no metadata at all (no EXIF, no
       GPS); the upload itself is never stored. Files: `<app.library.storage-dir>/profile-photos/<uuid>-512.jpg` and
       `-96.jpg` (back them up with the library). **Delete my profile photo** asks first and goes back to the
       initials ("Your photo is deleted."). A replaced or deleted photo's files are removed after the change is
       committed, and so are a deleted crew member's (8).
     Each change is in the audit log (10): "Profile languages changed" (`en, el`), "Profile nationality changed",
     "Profile bio changed" (its length, not the text), "Profile photo added" (`384x512 JPEG`), "Profile photo
     deleted".
   - **My crew** (`/ship/crew`): the ship as the eyebrow, "Crew on board · 6", then one card per department (Deck,
     Engine, Galley, then "Rank not set") with its count: each person is one full-width link (56 px) with their photo
     or initials, name and rank, in rank order then by name; the signed-in person's own row says "· you". Only
     enabled users with a crew role on that ship now: never invited people (they are invitations, not users), the
     deactivated, other ships or the office. Alone on board: "You're the only one on board in Nereonet so far."
   - **A crewmate** (`/ship/crew/{id}`): name, "rank · department", the 512 px photo and what they shared; with
     nothing: "Nikos hasn't added anything to their profile yet." **Back to My crew** at the end.
   - Never on these pages: email addresses, join or sign-off dates, certificates. Anyone not on board the person's
     ship now (another ship, deactivated, an office user, no such id) is a 404, not a 403, and so is their photo
     (`/ship/crew/{id}/photo/{key}/{96|512}`, and only with the person's current key). Photos are sent with
     `Cache-Control: max-age=31536000, private, immutable`: a new photo has a new key, so a cached one is never
     stale, and shared caches never keep them.
   - **The office** sees the same four fields read only in a **Profile** card on the crew member's page (8), with
     the photo (`/office/crew/{id}/photo/...`), languages and country names in the page language.
   - My crew is one query for the people and their profiles (`mycrew/CrewmateQueries`, a left join), so the page
     makes the same number of queries for 3 people or 30 (`MyCrewFlowTest` checks it). Rows load the 96 px photos
     lazily (about 2 KB each). The My crew pages load no script of their own; `js/profile.js` is only on the
     Languages and Photo pages (deferred), and every form works without it.
   - Dev data: when they are missing, the first ship also gets `engineer.demo@example.com` (Nikos Pappas, Chief
     Engineer, a full profile), `ab.demo@example.com` (Maria Santos, Able Seaman) and `steward.demo@example.com`
     (Arjun Mehta, Steward, nothing filled in), password `crew123`; the demo Master and Chief cook get their ranks if
     they have none.
   - To try it: as `cook.demo@example.com` / `crew123`, **More** (phone) or **My profile** (sidebar): add two
     languages and a photo; then **My crew** shows the cook's photo with "· you", and Nikos Pappas's page shows his
     profile. As `office@example.com`, Crew → Demo Cook shows the Profile card.

21. Approvals (P33, the Master only): the item requests of the Master's own ship, waiting for their decision.
   `approval/ApprovalController` and `ApprovalService`, `templates/approval/`; English only, as every crew screen.
   `/ship/approvals/**` is for MASTER only (`SecurityConfig`): the Chief cook and crew members get 403. A request of
   another ship is a 404.
   - **Where**: **Approvals** in the sidebar under On board, with the number waiting as a pill; on phones it is the
     first row of **More** (`/ship/more`), with the same number, and the More tab carries it as a badge.
   - **List** (`/ship/approvals`): two tabs (ordinary links). **Waiting** (the default, with its count): PENDING
     requests, oldest first. **Decided** (`?tab=decided`): approved and declined, newest decision first, each with
     "Approved" or "Declined" and the date. Each row is one link: who asked ("Demo Cook · Chief cook"), the request
     number, when (UTC) and the number of items.
   - **A request** (`/ship/approvals/{id}`): who asked, when, its status ("Waiting for the master", "Approved",
     "Declined", "Cancelled"), who decided and when, and for a declined one the reason; then its lines in one card per
     category (Provisions, then Personal items): name, quantity and unit. A line for an item not in the ship's list
     yet has a **New item** badge (also on the office's request page, 15; such a line is under Provisions, as only
     the Chief cook asks for new items, and the CSV has the name and unit given).
   - **Submitting** (`core/request/RequestService.submit`, for the Chief cook's and crew pages that come later): saves
     the request as PENDING with its lines (each line has either an item of the ship or a new item name, optionally
     with a unit; quantity above zero), records "Request submitted" in the audit log (`Request #12, 3 lines`, by the
     person who asked) and notifies the enabled Master of the ship: "New request to approve", linking to the request.
   - Existing databases: Hibernate never changes a column it created, so on startup
     `core/request/RequestSchemaMigration` drops the old not null on `request_line.item_id` and re-creates the check
     constraints `item_request_status_check` and `notification_type_check` from the enums (the new `CANCELLED` and
     `REQUEST`). It runs on every startup and changes nothing once done.
   - Dev data: while the first ship has no PENDING request, two are submitted for its Master: one from the Chief cook
     (Basmati rice, Olive oil, Eggs) and, 18 hours later, one from Maria Santos (Shower gel, a personal item, and
     Eggs, as in the cook's request). The Master gets their two notifications.
   - To try it: as `master.demo@example.com` / `crew123`, **Approvals** shows the two requests under Waiting; open
     Maria Santos's to see Provisions and Personal items. As `cook.demo@example.com`, `/ship/approvals` is a 403.

22. Approving and declining a request, and the ship order (P34, the Master only). `core/request/RequestService`
   (`approve`, `decline`), `core/order/`, and `approval/ApprovalController` with `DeclineForm`.
   - **Buttons**: a PENDING request (`/ship/approvals/{id}`) has **Approve** and **Decline** in its header; any other
     status has none. A request is decided as a whole; quantities are adjusted later, on the ship order.
   - **Approve** (`POST /ship/approvals/{id}/approve`): APPROVED, with who and when. Each new-item line is linked to
     the ship's item of that name (ignoring case) or, if there is none, to a new active Provisions item with the name
     and unit asked for (`pcs` when no unit was given; "Item added" in the audit log). Then
     `ShipOrderService.addApprovedRequest` adds the lines to the ship's OPEN order. Nothing is sent to the office.
   - **Decline**: a modal with a required **Reason** (up to 500 characters). `POST /ship/approvals/{id}/decline`:
     REJECTED with the reason, who and when. An empty reason shows the request again with the modal open and the
     error, and nothing is saved.
   - **After either**: the person who asked gets a notification, "Your request was approved" or "Your request was
     declined: <reason>", linking to `/ship/requests/{id}` (their request page, which comes later); "Request
     approved" or "Request declined" goes in the audit log; the Master is back on `/ship/approvals` with a message.
   - **Only once**: the request row is locked while deciding. A request already decided or cancelled (a second click,
     another tab) changes nothing and the request page says so. Another ship's request is a 404; the Chief cook and
     crew get 403.
   - **The ship order** (`ShipOrder`, `ShipOrderLine`): status OPEN, SENT, CONFIRMED or CLOSED, with requested port,
     needed by, sent by/at, confirmed port, delivery date, confirmed by, exported at. A ship has at most one OPEN
     order: `addApprovedRequest` locks the ship's row, finds the OPEN order or creates it, and per request line adds
     the quantity to the order line of the same item (one line per item) or makes that line. Each request line keeps
     a link to its order line (`RequestLine.shipOrderLine`), so the order can show who asked for what. Its page is
     section 23.
   - To try it: as `master.demo@example.com`, open a waiting request and **Approve** it; open the other and
     **Decline** it with a reason. Both requesters see their notification.

23. The ship order and "Send to office" (P35, the Master only). `core/order/ShipOrderService` (`changeQuantity`,
   `removeLine`, `send`) and `shiporder/` (`ShipOrderController`, `ShipOrderPageService`, `SendOrderForm`).
   - **Ship order** in the sidebar (on phones under More), with the number of lines in the OPEN order.
   - **`/ship/order`**: the ship's OPEN order, its lines by category (Provisions, then Personal items), each with the
     item name, quantity and unit and, under it, who asked for it: "Chief Cook 6, A. Santos 4" (the Master and the
     Chief cook by rank, others as initial and surname, each person once with what they asked for in all). Without an
     OPEN order, or with no lines: "Nothing to order yet. Approved requests are collected here."
   - **Editing** (only while OPEN): each line's quantity is a small form with **Save**
     (`POST /ship/order/lines/{id}/quantity`; above zero, at most 3 decimals, otherwise an error and nothing saved),
     and **Remove** (`POST /ship/order/lines/{id}/remove`; the requests stay approved, their lines are unlinked). Each
     saves at once; nobody is notified and nothing goes in the audit log.
   - **Send to office**, under the lines: **Requested port** (required, up to 100 characters), **Needed by** (required,
     not before today in UTC) and an optional **Note to the office** (up to 1000). `POST /ship/order/send`: only an
     OPEN order with at least one line becomes SENT, with sent by and at; "Order sent to office" goes in the audit log
     and every enabled office user gets a notification "New order from <ship name>" (since P36 it opens the order,
     `/office/orders/{id}`). Then the order is read-only, on `/ship/orders/{id}`; the next approved request opens a new OPEN
     order. Every change and the send lock the ship's row, as approvals do, so an approval never lands in an order
     being sent.
   - **`/ship/orders`**: the past orders (SENT, CONFIRMED, CLOSED), newest sent first, with status, requested port,
     needed by and, once the office has set them, confirmed port and delivery date. `/ship/orders/{id}` shows one
     with its lines (the OPEN one redirects to `/ship/order`).
   - Status labels: OPEN "Collecting", SENT "Sent to office", CONFIRMED "Confirmed by office", CLOSED "Received".
   - Another ship's line or order is a 404; the Chief cook and crew get 403. New column `ship_order.office_note`
     (nullable, Hibernate adds it); new notification type ORDER (`RequestSchemaMigration` re-creates the check).
   - To try it: as `master.demo@example.com`, approve the waiting requests, open **Ship order**, change a quantity
     and send it with port "Rotterdam"; `office@example.com` sees the notification.

24. Ship orders in the office (P36, office users only). `core/order/ShipOrderService` (`findSent`, `confirm`,
   `export`, `countNotExported`) and `officeorder/` (`OfficeOrderController`, `ConfirmOrderForm`).
   - **Orders** in the sidebar (`/office/orders`), with the number of orders sent and not exported. The list: the
     orders the ships have sent (SENT, CONFIRMED, CLOSED; never OPEN ones, which are still being put together on
     board), newest sent first, with ship, sent by, sent at, requested port, needed by, status (Sent, Confirmed,
     Closed) and **Exported** (when, or "Not yet"). Filters: ship (`?shipId=`), status (`?status=sent`, `confirmed`,
     `closed`) and export status (`?exported=no`: not exported yet, the Overview's link). Search the number (`#12`),
     the ship name or IMO number; 10 per page, 20, 50 or 100 on request (paged in the database). A row opens its order.
   - **`/office/orders/{id}`**: status, sent by and when, requested port, needed by, the Master's note, the
     confirmation once set (port, delivery date, by whom) and when it was last exported; then its lines by category
     (Provisions, then Personal items): item, unit, quantity. Read-only: the office never changes lines or quantities.
     An OPEN order is a 404.
   - **Confirm delivery** (while SENT or CONFIRMED): **Confirmed port** (required, up to 100 characters, starts as
     the requested port) and **Delivery date** (required). `POST /office/orders/{id}/confirm` sets confirmed port,
     delivery date, confirmed by and CONFIRMED; "Order confirmed by office" goes in the audit log and the ship's
     enabled Master (linking to `/ship/orders/{id}`) and Chief cook (no link: the order pages are the Master's) get
     "Order confirmed: Antwerp, 20 Nov 2026". It can be corrected (**Correct the delivery**) as often as needed, each
     time recorded and notified, until the order is CLOSED; then the form is gone and a confirmation changes nothing.
     The order's row is locked while confirming.
   - **Export**: on the order page, and on the list for the checked rows (the header checkbox checks the page).
     `POST /office/orders/export` (`ids`) answers with the Excel file `ship-orders-<date>.xls` (P39; CSV before; `.xls` since P40; the
     format is in 12), sheet "Orders", one row per order line, the orders newest sent first and each order's lines by category
     and item name: `imo_number,ship_name,order_id,sent_at,requested_port,needed_by,confirmed_port,delivery_date,category,item_name,unit,quantity`
     (`sent_at` a date and time cell in UTC, `needed_by` and `delivery_date` date cells, `quantity` a number,
     category `Provisions` or `Personal items`). It sets
     `exportedAt` on the exported orders and records "Orders exported" (counts and ids). An order can be exported
     again. Nothing checked: back to the list with "Select at least one order to export.". The page stays as it is
     (reload to see the Exported mark): the forms are `data-file`, so `js/save.js` never makes their buttons busy
     and their checkboxes are not unsaved changes.
   - **Request quotes** (P44, see 28): while the order is SENT, ask suppliers for prices; once one is awarded the
     page shows "Awarded to VANOS (Piraeus) · EUR 1,240.50" and Confirm delivery starts with its port and date.
   - Tests: `officeorder/OfficeOrderFlowTest`; the Overview's rows and counts in `DashboardFlowTest`.
   - To try it: send the order as in 23, then as `office@example.com` open **Orders**, confirm a port and date
     (the Master and the Chief cook see the notification) and export it.

25. Vetting (P40, office users only): how charterers vet each vessel, starting with RightShip. `core/vetting/`
   (`ShipRightShipStatus`, `RightShipReport`, `VettingService`, `RightShipView`, `RightShipSyncService`,
   `RightShipClient`) and `vetting/` (`VettingController`, `RightShipForm`, `RightShipReportForm`); templates in
   `templates/vetting/`, styles in `app.css` section "Vetting (P40)".
   - **Vetting** in the sidebar (Operations, after Certificates; a shield with a tick), with the number of active
     vessels whose RightShip inspection has expired or expires within 60 days, or (P41) whose SIRE 2.0 needs attention
     (comments overdue or due within 3 days, report no longer current or ending within 60 days, an observation past
     its target date), each vessel once ("2 need attention").
   - **`/office/vetting`**: every active vessel: name (and IMO number), type, Safety Score ("3/5" with a five-segment
     bar, "0/5", or "N/A" when not rated), GHG Rating, RightShip inspection (a badge with icon and words: "Valid until
     21 Dec 2026", "Expires in 38 days", "Expired", "Unacceptable", "Not required", "No inspection") and valid-until
     date. Most urgent first: inspection expired or unacceptable, then expiring, then the lowest Safety Score (not
     rated last), then name. Search (name or IMO number), filters **Vessel type** and **Status** (All, Needs
     attention: inspection expired / unacceptable / expiring or Safety Score 2 or below; Valid; Not rated), sorting by
     column and pages (P19); the list is worked out in memory (the fleet is small). The whole row opens the vessel's
     Vetting page. P41: a **SIRE 2.0** column (see SIRE 2.0 below) and a **Regime** filter.
   - **`/office/vetting/{shipId}`**: a card per vetting regime, the whole card opening the regime's page. Breadcrumbs
     Vetting › vessel; **Open vessel** goes to the vessel page. An inactive or unknown vessel is a 404 on every Vetting
     page below it. P41: a tanker shows SIRE 2.0 first, then RightShip; any other type RightShip first and, while it
     has no SIRE inspection, SIRE 2.0 as one muted line ("SIRE 2.0 applies to tankers.") with **Add inspection**.
   - **`/office/vetting/{shipId}/rightship`**: from 992 px two columns, the dashboard (7/12) and the official document
     (5/12); below that the document comes after the dashboard. No chart library and no script needed.
     - Score header: the vessel, "RightShip Safety Score", the bar and the large "3/5", type and calculation date.
       0/5 shows "No safety score" (error icon; sanctioned), an indicative score "Indicative" (warning icon). Without
       data "N/A" and the empty state **Add RightShip data**.
     - **Subscore summary**: the six subscores in three groups, Highest impact (Vessel incident performance, DOC
       company performance), Medium (PSC deficiencies, Detentions), Lowest (Flag performance, Class performance), each
       a bar and "4/5"; the lowest one(s) in the warning tone and marked "Lowest" (in words; not marked when every
       known one is 5/5), an unknown one a muted dash ("Not known" for screen readers).
     - **GHG Rating** (the letter, "A+" with a plus, Verified / Unverified, "N/A" for U; RightShip's colour of the letter
       on the leaf icon only: A `#00763B` … E `#D71920`) and **RightShip Inspection** (badge, valid until, last outcome,
       required).
     - **Official document**: the newest report (by document date) as an image at the card's width, or for a PDF a link
       that opens it in a new tab (P22); its date, who uploaded it and when, **Open** and **Download**
       (`/rightship/reports/{id}/file`, `?download=true`; private, cached 1 day). Earlier reports listed under it,
       newest first. Without one the empty state **Upload report**.
     - Header: **Open in RightShip** (when a platform link is set, new tab), **Refresh from RightShip** (only while the
       API is configured), **Open vessel**, **Edit RightShip data** and **Upload report** (the primary). An empty state
       that holds an action takes it out of the header (P25), so the page keeps one primary button.
     - Who entered the data and when ("Entered by Jane Doe", or "Updated from RightShip"); a restricted vessel shows
       "Restricted by RightShip" and RightShip's comments.
   - **Edit RightShip data** (`/rightship/edit`): Safety Score (Not rated, 0/5 to 5/5), Indicative, calculated on; the
     six subscores (1 to 5 or Not known); GHG Rating (A–E or U), A+, Verified, rated on; inspection required, last
     outcome, valid until; restricted, RightShip comments (up to 2,000 characters) and the link to the vessel on the
     platform (`https://` only). Every field may stay empty. Dates: calculated and rated on within the last 20 years
     and not in the future, valid until 10 years back to 5 years ahead. Each save adds a row (the history); only the
     newest is current. Recorded as "RightShip data updated".
   - **Upload report** (`/rightship/reports/new`): a PDF, PNG or JPEG of at most 10 MB (checked by extension and first
     bytes, stored like the library's files under `LIBRARY_STORAGE_DIR`) and the date on it (required). Recorded as
     "RightShip report uploaded".
   - Elsewhere: the vessel page header's badge row (5; P43: each badge a link to this page), the Vessels list's Safety
     Score column (5) and two kinds of Overview item (4): inspection expired, unacceptable or expiring within 60 days,
     and Safety Score 2 or below, right after expired certificates, each with **Open RightShip**. P43: the Vetting
     list shows each vessel's flag under its name, its rows have a checkbox and **Snooze** for the vessel's open
     vetting items (the inspection, the score, the SIRE 2.0 report, comments and observations; one item each), a
     snoozed row is muted with the snooze note and **Undo**, and the Status filter has **Snoozed** (see 27).
   - **RightShip API sync** (optional, read only, off by default): set `RIGHTSHIP_BASE_URL`, `RIGHTSHIP_CLIENT_ID` and
     `RIGHTSHIP_CLIENT_SECRET` (see Configuration). With any of them empty the app makes no request to RightShip at
     all and the data is entered by hand. When set, the server (never the browser) gets an OAuth 2 client-credentials
     token (`RIGHTSHIP_TOKEN_URL`, default `<base-url>/oauth/token`) and, nightly at 02:30 UTC
     (`RIGHTSHIP_SYNC_CRON`), calls `GET /vessel/v2/updates?SinceDate=<day of the last run that worked>` and then
     `POST /vessel/v2/info` (`{"imos": [...]}`, 100 at a time) for the changed IMO numbers of the active vessels; on the
     first run, or when the last run that worked is more than 7 days ago, for every active vessel. A vessel whose data
     did not change gets no new row. The API gives the Safety Score, the DOC subscore, GHG Rating, inspection and
     restrictions, not the other five subscores: those are kept from the vessel's last manual entry and marked "from
     report, 21 Oct 2026"; the API's values are marked "from RightShip". **Refresh from RightShip** does the same for one
     vessel at once. A failure is logged and saved, never an error page: the Vetting list and the RightShip page show
     "Last sync failed at …" until a run works again. Recorded as "RightShip data synced" (by the system) or "RightShip
     data refreshed". The JSON field names are read leniently in `HttpRightShipClient`, the one place to adjust them.
   - Tests: `vetting/RightShipFlowTest` (a fake `RightShipClient`), the counter in `layout/NavigationFlowTest`, the dev
     data and the Overview's query count in `DashboardFlowTest`.
   - **SIRE 2.0** (P41): the OCIMF inspections of tankers, entered by hand from the report (the app never connects to
     OCIMF; no PIQ, booking or CDI). `core/vetting/` (`SireInspection`, `SireObservation`, `SireService`, `SireView`,
     `SireSettings`, `SireStatus`, `SireReportState`, `ObservationType`, `ObservationStatus`) and `vetting/`
     (`SireController`, `SireInspectionForm`, `SireReportForm`, `SireObservationForm`); styles in `app.css` section
     "SIRE 2.0 (P41)". Every figure comes from the vessel's latest inspection (by inspection date).
     - Kept per inspection: date, port, submitting company, inspector, report reference, report status (Draft
       received, Operator comments sent, Published), published on, and the report (one PDF, replaced by a new upload).
       Per observation: VIQ chapter (1 to 12, with its name), question number ("9.1.2"), type (Hardware, Process,
       Human), the observation, operator comment, corrective action, owner (an office user), target date and closed
       on. Status: Closed once it has a closed date, Overdue when open past its target date, else Open.
     - Two company settings, shown on the SIRE page as company policy, not OCIMF rules: `SIRE_REPORT_CURRENT_MONTHS`
       (default 12: a report is treated as current until that many months after the inspection, "Report current until
       12 Jun 2027"; ending within 60 days is a warning, after it "Report no longer current since …") and
       `SIRE_COMMENT_DAYS` (default 14: while the report is Draft received, operator comments are due that many days
       after its published date, or the inspection date while there is none).
     - **List column**: "Published · 12 Jun 2026 · 2 open" (status, inspection date, open observations, overdue ones
       included) over one badge for what needs attention (comments due or overdue, report ending or no longer current,
       "1 overdue"); a muted dash without an inspection. Sorts by the latest
       inspection date. **Regime**: All, RightShip, SIRE 2.0 (only tankers and vessels with a SIRE inspection); with a
       regime chosen, **Status** looks at that regime only (Not rated: no SIRE inspection yet). Needs attention, and the
       urgency order, take both regimes.
     - **`/office/vetting/{shipId}/sire`**: the RightShip page's frame. Breadcrumbs Vetting › vessel › SIRE 2.0. The
       inspection header (date, status, report current until, port, submitting company, inspector, reference,
       published on, the policy note), the operator comment banner while the draft is received ("Operator comments due
       26 Jun 2026 · in 5 days": info, within 3 days warning, then danger, "2 days overdue"), the observations
       ("5 observations · 2 open", counts by type, by VIQ chapter and question number; **Open** by default or **All**,
       `?show=all`; each row opens its edit form) and the report card (**Open** inline in a new tab, **Download**,
       `/inspections/{id}/report/file`, private). Earlier inspections are listed and open on the same page
       (`?inspection={id}`, marked "This is an earlier inspection"). Header: **Open vessel**, **Upload report**,
       **Add observation**, **Add inspection** (the primary); without an inspection the empty state holds it.
     - **Add / Edit inspection** (`/sire/inspections/new`, `/{id}/edit`), **Upload report** (`/{id}/report`, a PDF of
       at most 10 MB, checked by extension and first bytes, stored under `LIBRARY_STORAGE_DIR`), **Add / Edit
       observation** (`/{id}/observations/new`, `/observations/{oid}/edit`; closing one is entering its closed date).
       Inspection date within the last 20 years, not in the future; published on not before it; target date 5 years
       either way. Recorded as "SIRE 2.0 inspection added / updated", "SIRE 2.0 report uploaded", "SIRE 2.0
       observation added / updated". An inspection or observation of another vessel (or inspection) is a 404.
     - Elsewhere: the vessel page header's badge row (tankers), the sidebar counter (each vessel once) and three kinds
       of Overview item (4), each with **Open SIRE 2.0**: operator comments overdue or due within 3 days (before the
       RightShip inspection), report no longer current or ending within 60 days, and an observation past its target
       date (linking to its row), both before the Safety Score.
     - Tests: `vetting/SireFlowTest`.

26. Required documents (P42, office users only): which certificates and documents each rank needs, on all vessels or
   on one vessel type, and who is missing what. `core/requirement/` (`DocumentRequirement`,
   `DocumentRequirementRepository`) and `requirement/` (`RequirementController`, `RequirementService`,
   `RequirementGrid`, `RequirementGridForm`, `RequirementRules`, `RequirementState`, `RequiredDocument`, `Compliance`,
   `ComplianceService`, `ComplianceExport`, `StarterRequirements`); template `templates/requirement/grid.html`, the
   shared fragments in `crew/parts.html` (`documentsSummary`, `documentState`, `requiredDocumentsCard`,
   `documentsMissingLink`, `myMissingDocuments`); styles in `app.css` section "Required documents (P42)". This is the
   company's own list: the page says it does not state what any regulation requires; flag-state and charterer rules
   are not checked, and the crew cannot upload documents themselves.
   - **Required documents** in the sidebar (Administration, after Office users; a document with a checklist),
     `/office/requirements`: a grid with the certificate types as rows and the ranks as columns, by department
     (names turned upright), then **Optional** and **Note** (up to 200 characters). **Vessel type or vessel** (P43,
     one combined filter; **Vessel type** before): a select with two groups, the vessel types (All vessels first,
     then Bulk carrier, Tanker, …) and the active vessels; choosing a vessel (`?key=ship-12`) shows the grid of its
     type, with a line saying so ("Rules for Tanker, which apply to MV Demo and MV Other"; a vessel without a type
     shows the rules for all vessels). With JavaScript (`js/requirements.js`) the select becomes a search box with
     the same options ("Type a vessel type or a vessel name"), an ARIA combobox; without it the plain select and its
     **Show** button. On a type's grid a rank already required on all vessels is a grey tick, not a checkbox. A plain
     form of checkboxes, so it saves without JavaScript (**Save for Tanker**); the first column stays put while the
     grid scrolls sideways inside its card (also on phones; the page itself never does). P43: each row starts with
     an **All ranks** checkbox ("Select all ranks for Passport"): ticked, saving requires the type of every rank the
     row can take (those inherited from all vessels stay grey); with JavaScript it ticks and unticks the row at once
     and follows the row's boxes. Each row ends with a **Delete row** trash icon (a confirmation, see "confirmations
     (P10)": "Remove Passport? Removes Passport from the requirements on Tanker vessels only. All vessels and the
     other vessel types keep theirs."; `POST /office/requirements/delete`, without JavaScript a confirmation page),
     which removes the row's own requirement for the chosen type, recorded as "Required documents changed"
     ("TANKER: Passport removed"); a row that only inherits from all vessels has nothing to remove and says so.
     **Add a document type** adds a row at the top to tick. Rows: the starter template's types, then every other
     type required or held, A to Z. Saving a type's grid changes only what was posted; a row without ranks, note or
     Optional is removed. Recorded as "Required documents changed" ("TANKER: Tanker Endorsement removed",
     "All vessels: Passport +COOK").
   - **Starter template**: on first start with no requirements, the app adds a suggestion (marked **Suggestion**, with
     a notice to review it): STCW Basic Safety Training, Medical Fitness Certificate, Seaman's Book, Passport and
     Security Awareness for every rank; Certificate of Competency, Flag State Endorsement, Advanced Fire Fighting and
     Medical First Aid for the Master, deck officers, engineers and ETO; GMDSS Operator for the Master and deck
     officers; Tanker Endorsement for every rank on tankers. Saving a vessel type's grid confirms its rows. Added once
     (recorded as "Required documents starter template added"), so a template the office cleared stays clear;
     `app.requirements.starter-template: false` turns it off (tests).
   - **States**, worked out on every read, nothing derived stored: the certificate of that type (same type ignoring
     case and surrounding spaces) that expires last; **Valid**, **Expires in 12 days** (within 30), **Expired**
     (counts as missing), **Missing**. A certificate whose expiry is beyond the P39 plausible range (30 years) is ignored. An optional
     document is shown, last and marked "Optional", never counted. Without a rank or a vessel nothing applies: the
     person is listed under **Rank needs review** (8).
   - **Where it shows**: the office's crew member page, card **Required documents** ("For Chief Officer on MV Demo
     (vessel type: Tanker)", missing and expired first, each with its badge, **Open** for a file and **Add
     certificate** with the person and type filled in, coming back to the page; **Change what is required**); the Crew
     list and a vessel's Crew tab (8: **Documents** "5 of 7" with an icon, sortable, most missing first; **Missing
     documents** filter, `?documents=missing`; the column, filter and export only once any requirement is set); the
     vessel's certificate matrix (12, **Missing**); the Overview (4); and for the seafarer My profile (20) and Home
     (18).
   - **Export required documents** (`/office/crew/documents/export`, `/office/ships/{id}/crew/documents/export`, the
     list as filtered, every page): `required-documents[-<IMO>]-<date>.xls`, sheet "Required documents", one row per
     person and document in the card's order: `imo_number, ship_name, crew_member, rank, requirement, required` (yes,
     or no for an optional one)`, state` (missing, expired, expiring, valid)`, expires_on` (a date cell). People
     without required documents have no row.
   - Queries: the Crew list adds two whatever its size (the requirements, and the certificates of the page's people;
     its sort and filter apply the same rules in SQL, `crew/PeopleQueries`), 15 for the whole page; the Overview adds
     one, and two more when requirements are set.
   - Tests: `requirement/RequirementsFlowTest`.

27. Snoozing alerts and the Overview redesign (P43, office users only). An office user can put a certificate or
   vetting item aside for a while, with a reason, so the counters and the Overview show what still needs a decision.
   `core/alert/` (`AlertKind`, `AlertKey`, `AlertLevels`, `Alert`, `AlertSnooze`, `AlertSnoozeRepository`,
   `SnoozeReason`, `SnoozeEndReason`, `SnoozeService`) and `alert/` (`AlertController`, `AlertService`, `AlertView`,
   `SnoozeForm`, `AlertReturn`); templates `alert/snooze.html` and the fragments in `alert/parts.html`; styles in
   `app.css` section "Snoozing alerts (P43)"; `dashboard/SnoozeSyncJob`. The Overview itself is described in 4.
   - **What can be snoozed**: a certificate expired or expiring (one item), and a vessel's open vetting items: the
     RightShip inspection, the Safety Score, the SIRE 2.0 report, the SIRE 2.0 operator comments, the SIRE 2.0
     observations past their target date (one item each; `AlertKind`). Orders, invitations, announcements and
     required documents cannot be snoozed: they have an action of their own.
   - **Where**: the Certificates list (12), the Vetting list (25) and the Overview's Needs attention list (4) have a
     checkbox per row and a **Snooze** link per row; **Snooze selected** in the header (with JavaScript, `js/list-rows.js`:
     the count of checked rows, select all on the page, the button disabled while nothing is checked; without it the
     checkboxes still post, through the `form` attribute of a GET form). Both open the snooze page.
   - **The snooze page** (`/office/alerts/snooze?items=CERTIFICATE_EXPIRING:12,…&returnTo=…`): the items reviewed
     (type, person or vessel, how far the date is; with several, each a ticked checkbox so one can be left out), the
     **Reason** (Renewal in progress, Crew member signs off before expiry, Not needed on this vessel, Other with a
     required note of up to 200 characters, shown only for Other with JavaScript) and **How long**: 7 days, 30 days,
     until a date (tomorrow to a year ahead) or until it expires (only when every item has a date ahead). An expired
     item (the certificate, the inspection, the report; a missing or overdue one too) can be snoozed for 30 days at
     most, and the page says so. **Snooze** and **Cancel** go back to the list or the Overview as it was
     (`returnTo`, only local paths; "2 items snoozed."). An item that no longer needs attention is dropped from the
     selection ("Nothing to snooze" when none is left).
   - **While snoozed**: the item leaves the sidebar counters (Certificates, Vetting), the Overview (the list, the
     tiles, By category, Next 30 days and the Fleet counts; the Items tile says "3 snoozed") and the certificate
     reminder emails; it stays in its list, muted, with "Snoozed by Jane Office until 12 Dec 2026" and **Undo** (any
     office user; POST, comes back with "Snooze undone: the item is back."). The Status filters of the two lists leave
     snoozed items out; **Snoozed** lists them. The certificate or the vetting data do not change.
   - **It ends by itself** (`SnoozeService.sync`, run on every Overview load and by the daily job
     `SnoozeSyncJob` on `app.certificates.reminder-cron`): when its date passes; when the item is resolved (a renewed
     certificate: the expiring one no longer exists as an alert; the inspection renewed; the comments entered); or
     when it gets worse than when it was snoozed, so an expiring certificate snoozed at "within 60 days" comes back at
     "within 30 days", and at expiry (`AlertLevels`: expired 0, within 30 days 1, 60 days 2, 90 days 3; the level at
     the snooze is stored on the row). A snooze that ends keeps its row with `endedAt` and the reason (`SnoozeEndReason`:
     UNDONE, DATE_PASSED, RESOLVED, WORSENED). The certificates query does the same rule in JPQL
     (`CertificateRepository.SNOOZED`), so the counter and the reminders agree with the lists.
   - **Audit log**: "Alert snoozed" (the item, the reason, the note, the date), "Alert snooze undone", "Alert snooze ended" (why:
     "worsened (level 2 to 1)", "resolved", "date passed", by "System").
   - Table `alert_snooze` (kind, subject id, ship, who, reason, note, until, level, created at, ended at, end reason;
     Hibernate creates it on start).
   - Also in P43: the crew Home's sign-out line no longer repeats the name (18); the sidebar shows the user's photo
     where there is one (`NavigationAdvice`'s `currentPhoto`, `CrewProfile.photoUrl(size)` with a version
     parameter so a new photo shows at once), initials otherwise; the Greek ship types read "Bulk carrier / Tanker /
     Container / General cargo / Other" in Greek and the vetting filter "Σύστημα αξιολόγησης"; the page size of every
     list is 10 by default (20, 50 or 100; `ListQuery.SIZES`).
   - Tests: `alert/SnoozeFlowTest`, `dashboard/DashboardFlowTest`, `requirement/RequirementsFlowTest`.

28. Suppliers and requests for quotes (P44, office users only). From a vessel order the ship has sent, the office asks
   several suppliers for a price by email, types in their answers, compares them per item and awards one, all in the
   app. `core/supplier/` (`Supplier`, `SupplierService`, `SupplierChannel`, `EmailChannel`, `SupplierEmails`),
   `core/rfq/` (`Rfq`, `RfqLine`, `RfqQuote`, `RfqQuoteLine`, `RfqService`, `RfqComparison`, `Units`, `Money`,
   `RfqWorkbook`, `RfqAwardListener`), `supplier/` and `rfq/` (controllers and forms); templates in
   `templates/supplier/`, `templates/rfq/` and `templates/email/supplier.html`; styles in `app.css` section
   "Suppliers and quotes (P44)".
   - **Suppliers** in the sidebar (Operations, after Orders; a truck; `/office/suppliers`): name, contact email,
     ports served (free text, "Piraeus, Perama"), currency (three letters, EUR by default), website, active. The
     list (P19): search name, email or port, filter Active / Inactive, sort by name or currency, 10 per page; with
     none yet, the empty state with **Add supplier** (P25). **Add supplier** and **Edit** share one form; a name
     already used (any case) is refused. An inactive supplier stays on old requests but cannot be asked again.
     Audit: "Supplier added", "Supplier changed". The dev data adds **VANOS (Piraeus)** (`https://www.vanos.gr/`,
     EUR, Piraeus, Perama, Elefsina) with a test address, `quotes.vanos@example.com`, so a dev request never reaches
     the real company, and **Harbour Provisions (Test)** in USD; once, while there is no supplier.
   - **How a request is sent** is one seam: `SupplierChannel` (`sendRfq(rfq, supplier)`, `cancel(rfq)`, and
     `notSelected(rfq, supplier)` for the award), one bean per `ChannelType`, chosen by the supplier's `channel`
     field. Only EMAIL exists (`EmailChannel`); a marketplace or an API would be another implementation.
   - **Request quotes** (on `/office/orders/{id}` while the order is SENT, i.e. waiting for the office): the form
     starts with the order's requested port and needed-by date, a reply-by date 3 days ahead, and the active
     suppliers as checkboxes, those whose ports served mention the port first ("Serves Piraeus", ignoring case and
     accents); at least one, the reply-by date on or before the delivery date (dates P39-plausible). **Review**
     (P10) shows the port, the dates, the suppliers with their addresses, what the email contains and the items;
     nothing is sent until **Send to 3 suppliers**. Sending copies the order's lines (item, name, unit, category,
     quantity) into the request, so later changes to items do not change it. An order can have many requests over
     time but only one open: **Request quotes** then becomes **Open RFQ #3**. The order's page lists its requests
     (number, status, quotes received).
   - **The email** (English, `SupplierEmails`; HTML in the P36 layout with a plain-text part, `MailService.send` with
     Reply-To and attachments): subject "RFQ #3: request for quotation, MV Demo at Piraeus"; "Dear VANOS (Piraeus),"
     then the vessel and IMO number, the port, the delivery date and the reply-by date; a table of the items by
     category (item, quantity, unit); what to give per item (unit price, quantity offered, pack size and unit, a
     note) and overall (delivery cost, delivery date, validity), in the supplier's currency unless they say
     otherwise; the footer names the office user, whose address is the Reply-To, and the reference. Attached:
     `rfq-3.xls` (P40 format: sheet "RFQ", `rfq,vessel,imo_number,port,delivery_date,reply_by,category,item_name,unit,quantity,unit_price,quantity_offered,pack_size,pack_unit,note`,
     the last five empty for the supplier to fill in). Each supplier gets its own email; none sees the others. An
     email that cannot be sent marks that supplier **Failed** with the reason and **Send again**.
   - **Entering a quote** (`/office/orders/{id}/rfqs/{rfqId}/quotes/{quoteId}`): per line the unit price (of one
     pack), the quantity offered (packs), the pack size and unit (empty: one of the item's unit; "5" "lt" for a 5 L
     can of an item in lt; the unit must convert to the item's: volume, mass or count, `Units`) and a note; a line
     with no price is not offered. Then currency, delivery cost, delivery date (required) and valid until, and the
     supplier's own PDF (optional, up to 10 MB, kept with the request like a library file). **Declined** is one click
     on the request page, with an optional reason; entering a quote later undoes it. Both can be corrected while
     the request is open.
   - **Statuses per supplier**: Sent, Quoted, Declined, No reply (sent, reply-by date passed), Failed.
   - **The comparison** (on the request page, once two suppliers have quoted; sealed: suppliers never see each
     other's prices): one table per currency with at least two quotes (no conversion: other currencies are listed
     apart, "Other currencies, not compared"); items as rows by category (P32), suppliers as columns; each cell the
     price per item unit (a 5 lt can at EUR 12.00 is EUR 2.40 / lt), the packs, the line total, "Less than asked"
     when it covers less, the note, and "lowest" for the row's lowest price; a line not offered says **Not offered**,
     never zero. Then items, delivery cost, grand total ("Incomplete: 2 items offered" when lines are missing),
     delivery date and validity per supplier. The **cheapest complete quote** (every line offered, lowest grand total)
     has a tick with "Cheapest complete quote" and a green tint. On phones the table scrolls sideways inside its card
     with the item column fixed. Amounts are `BigDecimal` (prices to 4 decimals, totals to 2), shown "EUR 1,240.50"
     (Greek "EUR 1.240,50", `@money`).
   - **Award** (one quote for the whole request, no split by line; confirmed first): the request is Awarded, every
     other supplier still asked gets a short "RFQ #3: not selected" email, and the vessel order shows "Awarded to
     VANOS (Piraeus) · EUR 1,240.50" with its **Confirm delivery** form starting with the request's port and the
     quote's delivery date (24). The purchase order is not part of P44: `RfqAwardListener` beans are called after an
     award and none exists yet. **Cancel request** withdraws an open request ("request withdrawn" email to those
     still asked); a new one can then be sent.
   - **Overview** (4): "2 quotes ready to compare" (an open request with at least two quotes, **Compare quotes**) and
     "Reply-by date passed" (fewer than two quotes and the date passed, **Open request**), category **Quotes**. The
     sidebar's Orders counter is unchanged.
   - **Audit log**: "RFQ sent" (lines, port, dates, suppliers; sent again), "Quote entered" (supplier, total, lines
     offered, the PDF), "Quote declined" (with the reason), "RFQ awarded" (supplier and total), "RFQ cancelled".
   - Tables `supplier`, `rfq`, `rfq_line`, `rfq_quote`, `rfq_quote_line` (Hibernate creates them on start).
   - Tests: `supplier/SupplierFlowTest`, `rfq/RfqFlowTest`, `core/rfq/UnitsTest`; the Overview rows in
     `RfqFlowTest`, the query count in `DashboardFlowTest`.
   - To try it: as the Master send an order with port "Piraeus" (23); as `office@example.com` open it, **Request
     quotes**, choose both suppliers and send (the emails are in Mailpit); enter a quote for each, compare and
     award.

Run the tests (H2 in memory, no Docker needed):

```
./mvnw test
```

### Frontend assets

The app makes no request to any other host: everything a page needs is served from
`src/main/resources/static/`.

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
  next to the name. `nereonix-logo-on-dark-600.png` (600×422), the inline logo of the emails (since P30 no page uses
  it); `nereonix-mark-512.png`, the large mark, kept as source and not linked. Every `<img>` has its
  `width` and `height`. Replace a file under the same name; the content hash changes its URL.
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
    letter-spacing 0.04em. Rows get a hover background; counts (`.num`) are right-aligned. The record's name
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
    - character limits: every field with a `@Size(max)` in its form class shows a counter under it
      (`layout/parts :: counter`; the limit is read from the form class by the `formFields` bean,
      `core/form/FormFields`). Without JavaScript it reads "Up to 5,000 characters"; `js/forms.js` turns it into
      "123 / 5,000" while typing, and "5,012 / 5,000 · 12 too many" in red and bold over the limit. Announcement
      messages and ship particulars count a line break as one character, as the server does;
    - pop-up or page: a pop-up only for short forms (add and edit ship, invite office user, add crew member; the
      last one has five fields until P18 shortens it; also on a ship's Crew tab, P17), everything else is a full page;
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
      a card header as well. **Export** on **Orders** (P36; Excel since P39) is in the page header and still submits the list's
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
    - office: **Operations** (Overview `/office`, Ships, Crew, Certificates, Vetting (P40, see 25), Announcements,
      Orders (P36, see 24), Suppliers (P44, see 28)),
      **Administration** (Office users, Audit log), **Account** (Notifications; the language switch moved to the top
      bar, P29); crew: **On board**
      (Home `/ship`, Announcements `/ship/announcements`, Library) and **Account** (Notifications). The user block (name,
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
      once ("2 need attention"), **Orders** sent by the ships and
      not exported (P36), **Notifications** unread. One count query each on every page (`navCounts` from `NavigationAdvice`,
      office users only; `unreadNotifications` from `NotificationBellAdvice`). The Notifications pill and the bell
      follow Mark as read without a reload (13, P23; the entry has `data-nav="notifications"`);
    - rail and preference (P29), from 768 px: the sidebar is a 64 px rail by default (the mark, the entries' icons, the
      user's initials and a sign-out icon); each entry's name shows as a tooltip beside it on hover and on keyboard
      focus, and stays in the link for screen readers; the active entry keeps its pink bar; a counter becomes a
      10 px dot with the number and its meaning still in the link's text. **Expand menu** / **Collapse menu**
      (`aria-expanded`, `aria-controls="sidebar"`; `layout/parts :: sidebarToggle`) switches to the full 256 px
      sidebar and back. Since P39 it is at the top, next to the brand mark (under it, centred, in the rail), where it
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
      and the page behind does not scroll while it is open. Ship crew have no menu button and no bell: the bottom tab bar (18, P26) has the same
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
      button and the way back to where it started (e.g. `/office/ships/3/deactivate?tab=crew`);
    - an action that asks only sometimes (until P36 **Export selected** on Requests, when a selected request was
      exported before) keeps its submit button and adds `data-confirm-when="<selector>"`: the dialog opens when an element of
      the form matches it, and Confirm sends the form with `confirmed=true`. Without the script the POST itself
      answers with the plain confirmation page, which posts the same fields with `confirmed=true`.

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

  - sign-in and registration (P13): a first-time seafarer on a phone gets in without help (see 7 for the flow and
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
    All of it is `js/save.js`, on every page; without the script every form still submits as before. No offline
    queue and no service worker:
    - sent once: when a POST form is sent, all its submit buttons are disabled and the one pressed shows a spinner
      and "Saving…" (sign-in "Signing in…", sign-out "Signing out…": `data-busy-label`).
      A second click, a double click or Enter sends nothing more. Back to the page (browser Back, `pageshow`) gives
      the buttons back. A form answered with a download (`data-file`, the order export, P36) leaves the page as it
      is, so it is never made busy and its fields never count as unsaved changes. The
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
    - unsaved changes: leaving a signed-in page with a form whose fields changed (or with a file chosen) asks first
      (`beforeunload`), except while a form is being sent. Fields of a closed pop-up do not count; signed-out pages
      never ask;
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
| `MAIL_FROM_NAME` | optional; the sender name the inbox shows, default `Nereonix` |
| `ORGANISATION_NAME` | optional; the company's name at the bottom of the sign-in panel, e.g. `Nereonix Bulk Carriers`; nothing is shown when empty |
| `ORGANISATION_CODE` | optional; a short code under the name, e.g. `NEREONIX` |
| `APP_BASE_URL` | public URL used in email links, no trailing slash, e.g. `https://nereonet.example.com` |
| `CERTIFICATE_REMINDER_CRON` | optional; when the certificate reminder job runs (UTC), default `0 0 6 * * *` |
| `LIBRARY_STORAGE_DIR` | folder for library files, created if missing, e.g. `/var/lib/nereonet/library`; back it up with the database |
| `INVITATION_JWT_SECRET` | random secret, at least 32 characters, for signing invitation and password reset links |
| `RIGHTSHIP_BASE_URL` | optional (P40); the address of the RightShip API (from RightShip); the sync is off while it, the client id or the secret is empty (see 25) |
| `RIGHTSHIP_CLIENT_ID` | optional; the RightShip API client id |
| `RIGHTSHIP_CLIENT_SECRET` | optional; the RightShip API client secret |
| `RIGHTSHIP_TOKEN_URL` | optional; where the OAuth token comes from, default `<RIGHTSHIP_BASE_URL>/oauth/token` |
| `RIGHTSHIP_SYNC_CRON` | optional; when the nightly RightShip sync runs (UTC), default `0 30 2 * * *` |
| `SIRE_REPORT_CURRENT_MONTHS` | optional (P41); company policy: months a SIRE 2.0 report is treated as current after the inspection, default `12` (see 25) |
| `SIRE_COMMENT_DAYS` | optional (P41); company policy: days for the operator comments on a draft SIRE 2.0 report, default `14` |
| `BOOTSTRAP_OFFICE_EMAIL` | first office user; required only while no office user exists |
| `BOOTSTRAP_OFFICE_PASSWORD` | first office user; required only while no office user exists |
| `SUPPORT_CONTACT` | optional free text shown as "Office contact" on the "link is invalid or has expired" page and as "Questions? Contact: …" in the footer of the invitation and password reset emails, e.g. `Crewing department · crewing@example.com · +30 210 000 0000`; not shown when empty |

Outside dev, SMTP login and STARTTLS are required (no plain-text fallback). Keep secrets out of
checked-in files (`application*.yml`, `docker-compose.yml`) and do not enable `mail.debug` or DEBUG
logging for `jakarta.mail` / `org.eclipse.angus.mail`: that output contains the SMTP login.
