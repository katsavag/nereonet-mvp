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
   and a demo ship (`MV Demo`, IMO 9074729). While there are no item requests it also creates two approved
   requests on the first ship (by name), with three demo items and, when the ship has no active Master / Chief cook,
   the users `master.demo@example.com` and `cook.demo@example.com` (password `crew123`). Since P31 the first ship
   also gets three more crew members with sample profiles for My crew (see 20).
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
     (marks that request exported, as on Requests, and comes back to the Overview, where the row is gone and its CSV
     downloads by itself: "1 request exported · Download again", see 15),
     **Resend invitation** (new link and expiry date, back to the Overview with "Invitation sent again") or **View who
     has not acknowledged**. The card shows the first 50 rows ("Showing the first 50 of 63"); under it **Full lists**
     links each kind to its full list: requests to `/office/requests?exported=no` (not exported yet), certificates to
     `/office/certificates?status=expired,30&activeShips=true` (exactly the certificates counted, see 12), the others
     to this page filtered to that kind (`/office?type=invitations`, `acknowledgements`, every row, with **Show all
     open items** to go back; `/office?type=certificates` still works). **Open certificate** opens the form, which
     comes back to the Overview (with its filter) after Save or Cancel. With nothing open the card says "Nothing needs
     your attention" and when it was checked.
   - **Fleet**: one row per active ship (the name opens the ship page): crew on board, certificate issues (expired or
     within 30 days), not acknowledged (announcements and Required library items as above), requests not exported.
     Ships with open items come first, then by name. A zero is a muted dash and not a link; any other number links to
     its list filtered to that ship and condition: `/office/crew?shipId=…`, `/office/certificates?shipId=…&status=expired,30`,
     `/office?type=acknowledgements&shipId=…`, `/office/requests?shipId=…&exported=no`.
   - `DashboardService.load()` always runs the same 7 queries, whatever the size of the fleet (active ships, crew per
     ship, and one per kind of open item, in `dashboard/DashboardQueries`); `DashboardFlowTest` checks it.
   With the dev data the two seeded approved requests are two rows with **Export**; export them and the card says
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
     `DashboardService.setup()` is one query (`DashboardQueries.setup()`), outside `load()`'s seven.

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
   - **Crew** (`/crew`), **Announcements** (`/announcements`) and **Requests** (`/requests`): the fleet-wide lists
     of 8, 14 and 15 for this ship only, without the ship filter and the Ship column (Requests keeps its Export
     status filter, **Export selected** and **Export all not exported (N)** for this ship, and comes back to the
     tab). The fleet-wide pages keep their ship filter.
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
   and status, 25 or 50 per page (see "lists: search, sort and pages (P19)" below; users and invitations are paged together
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
     with errors** in one action; exclude single rows with the checkbox; **Check again** re-validates; **Change
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
   when, newest first, 25 or 50 rows per page (paged in the database), searchable (who did it, by name or email, and
   the details), filterable by ship and date range (UTC days), sortable by when, who, action and ship. Recorded so far: ship added,
   edited, deactivated, activated; invitation sent, resent, cancelled; crew imported (counts only); user registered, edited, deactivated, activated, deleted; password reset (the user, by their email; see 17);
   item added, edited, deactivated, activated; items imported (counts only); certificate added, edited, deleted;
   certificate reminder sent (by "System"); announcement sent, announcement acknowledged (by the crew member, with the
   title); requests exported (count and ids).
   Entries are read-only.

11. Galley items (signed in as an office user): the **Items** tab of a ship page (`/office/ships/{id}/items`, see 5).
   Each item has a name (unique per ship, ignoring case), a short unit (e.g. `kg`, `lt`, `pcs`) and an optional category.
   **Add item** and the item's name (or **Edit** in the row's **⋯** menu) open a form; items can be deactivated and activated (the row's **⋯** menu), never deleted. Deactivate asks first
   ("The crew of MV Demo cannot request Basmati rice until it is activated again."). The list is searchable (name and
   category), filterable by status (Active, Inactive) and category, sortable by name (the default), unit,
   category and status, 25 or 50 per page (paged in the database).
   **Import** (`/office/ships/{id}/items/import`) adds and updates many items at once, from the file the purchasing
   system produces or from the template, in the same four steps as the crew import (see 8): **Upload** (`.xlsx`,
   `.xls` or `.csv`, any columns; **Download template** gives `items-template.xlsx` with `Name | Unit | Category`,
   which skips the mapping), **Map columns** (Name, Unit, and the optional Category; guesses such as Description,
   UoM, Product group), **Review**, **Done**. There are no values to match. The mapping is saved and suggested again
   as for crew, for every ship (it describes the purchasing system's file, not the ship).
   - **Review**: every row, editable. A name the ship does not have (ignoring case) is **New**; a name it has
     **Updates** that item's unit and category, or is **No change** (skipped) when they are the same. The rows that
     add or update are checked (missing name or unit, value too long, name repeated in the file). Filters, **Exclude
     all rows with errors** and the sticky first columns as for crew.
   - **Import items** saves the new items and the updates; **Done** shows how many were added, updated and skipped,
     with a link to the ship's items. Nothing is saved before that. An import started for one ship is never continued
     or saved for another: its steps lead back to that ship's upload.
   To try it, save this as `items.csv` and upload it twice (the second time all three are "No change"; change a unit
   to see an update):

   ```
   Name,Unit,Category
   Basmati rice,kg,Dry goods
   Olive oil,lt,Oils
   Eggs,pcs,Fresh
   ```

12. Certificates (signed in as an office user): on a crew member's **Edit** page (`/office/crew/{id}/edit`) the
   **Certificates** card lists their certificates (type, optional number, expiry date) with **Add certificate**,
   **Edit** and, in the row's **⋯** menu, **Delete** (after the confirmation "Delete STCW Basic Safety?").
   **Certificates** in the sidebar (`/office/certificates`, P20) lists every certificate, soonest expiry first. The
   **Expires on** column shows the date and how far it is ("in 23 days", "expired 5 days ago"); the status badge has
   an icon as well as a colour: a warning triangle for Expired, a clock for 30, 60 or 90 days, a tick for Valid.
   Searchable (type, number, crew member), sortable by expiry, type, crew member, ship and number, 25 or 50 per page
   (paged in the database), filterable by ship, **Active ships only** (`activeShips=true`) and **Status**
   (`?status=`): `expired`; `30`, `60`, `90` (not expired, expiring within that many days, today included, so
   "Within 60 days" includes "Within 30 days"); `valid` (more than 90 days left); `all`. Without a status, or with one
   it does not know: expired and within 90 days, as before. States combine with a comma, which is how the counts link
   to exactly what they count (each tested in `CertificatePlanningFlowTest`):
   - the sidebar counter (expired or within 30 days, every ship): the **Certificates** entry opens
     `?status=expired,30` while the counter shows; without a counter, the list as it is;
   - the Overview's **Full lists** entry (the same, crew of active ships): `?status=expired,30&activeShips=true`;
   - the Overview's fleet table, **Certificate issues** of a ship: `?shipId=…&status=expired,30`;
   - the ship page's **Certificates** tab count (expired or within 90 days): the tab's **List** view.
   **Export CSV** (page header) downloads the list as filtered, searched and sorted, every page:
   `certificates-<date>.csv`, UTF-8 with a byte order mark and ISO dates, as the request export, with the columns
   `imo_number, ship_name, crew_member, rank, type, number, expires_on, days_left, status` (rank as its code,
   `SECOND_OFFICER`; status `expired`, `30_days`, `60_days`, `90_days` or `valid`, the badge). It is a plain link
   (GET, nothing changes, not in the audit log).

   The ship page's **Certificates** tab (`/office/ships/{id}/certificates`) is a matrix: the active crew on board as
   rows, by department and rank (Rank's order, "Rank not set" last), and the certificate types they hold as columns,
   the most held first. Each cell shows the expiry date and the badge of that person's certificate of that type that
   expires last (a renewed certificate replaces the old one) and opens it; a dash when there is none, which opens
   **Add certificate** with the person and the type filled in. The crew column stays in place while the table scrolls
   sideways (also on phones; the table is a keyboard-focusable region). **Matrix** / **List** switch between the
   matrix and the list above for this ship (`/certificates/list`, without the ship filter, the Ship column and Active
   ships only). **Export CSV** downloads the view on screen: the matrix as `certificate-matrix-<IMO>-<date>.csv`
   (`crew_member, rank`, then one column per type with the expiry date, empty when there is none), the list as
   `certificates-<IMO>-<date>.csv`.

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
   grouped by UTC day (**Today**, **Yesterday**, then the date), 25 to a page (or 50, with the usual pager). Each shows a
   type icon (certificate, library, announcement; the bell for notifications saved before P23), the ship or person it
   concerns ("MV Demo", "Jane Doe · MV Demo"), its text and the time in UTC ("12:40 UTC").
   Notifications stay until handled (P23):
   - showing the list marks nothing as read; leaving it keeps the unread count;
   - a notification is read once it is opened: its text is a POST (`/notifications/{id}/open`) that marks it and goes
     to its link (a library item, for example). A notification without a link (the certificate reminder) has only
     **Mark as read** (`/notifications/{id}/read`). **Mark all as read** (`/notifications/read-all`) is in the page header
     while anything is unread. Each comes back to the same page of the list with "Notification marked as read." or
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
   announcement, newest first, with its target, date and e.g. "1 of 2 acknowledged". The target (P28) is "All ships",
   the ship's name (a link) for one ship, or "3 ships" with the names as its title (and, for screen readers, as hidden
   text; on the phone card they show under it). Filter by ship to see the announcements sent to all ships or with that
   ship among their targets; search the title; sort by sent (the default, newest first), title and target (All ships,
   then one ship by name, then several ships, fewest first); 25 or 50 per page (paged in the database, the
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
   their own ship, never the other target ships. Opening an announcement shows its text, "1 of 3 acknowledged" under
   the heading (with "3 ships" as the eyebrow for several ships), **Acknowledgements by ship** (every target ship and
   every ship of the recipients, by name, "1 of 2 acknowledged" each or "Nobody on board when it was sent", and the
   Total for more than one ship), and two lists, both grouped by ship: acknowledged (name, role, ship, time) and not
   yet acknowledged. Announcements cannot be edited or deleted. The audit entry "Announcement sent" lists the target
   ship ids and names ("Port state control (ship ids 3, 7: MV Alpha, MV Beta; 12 recipients)"), and has the ship
   only when there is one.

   Data model (P28): `announcement.fleet_wide` (true for All ships) and the join table `announcement_ship` (the
   target ships; none for All ships) replace the nullable `announcement.ship_id`. `AnnouncementRecipient.ship` stays
   the ship each recipient was on. On startup, while `announcement.ship_id` still exists,
   `core/announcement/AnnouncementTargetMigration` moves the rows in one transaction (ship_id set: one
   `announcement_ship` row; null: `fleet_wide = true`) and then drops the column, so existing announcements keep their
   target and it runs once.

15. Approved requests (signed in as an office user): **Requests** in the sidebar (`/office/requests`) lists the item
   requests approved by a Master, newest approval first, with ship, requested by, approved by, approved at and number
   of lines. Filter by ship and **Export status** (`?exported=`): **Not exported yet** by default (also `no`, and
   any value it does not know, such as an old `?notExported=true` link), **Exported** (`yes`) or **All** (`all`);
   these two add an **Exported** column: when, in UTC, and by whom ("12 Nov 2026 · 10:32 UTC", "by John Doe", or
   "Not yet"). Search the number (`#12`), the people, the ship name or IMO number; sort by approved at, number, ship,
   requested by and approved by; 25 or 50 per page (paged in the database). Click a request (anywhere on its row but
   the checkbox; its number is the link for the keyboard) to see its items (`/office/requests/{id}`). Creating and
   approving requests on board come later; the dev profile seeds two (see 3).

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
     the name: "Not Demo Cook?" and **Sign out**, one tap from Home, so
     the next person on a shared PC sees whose session it is.
   - **To read**: the Required library items of the person's ship that they have not acknowledged in the current
     version and the announcements they have not acknowledged (P27, see 19), together, oldest first, with their number
     after the heading. Each is a full-width card (at least 56 px) with the type icon (the announcements icon for an
     announcement), the type ("Library" or "Announcement"), the title and "Published 12 Nov 2026" or "Sent 12 Nov
     2026" (UTC date), and opens the item or announcement, where **I have read this** is. Retired items and items of
     other ships never show. A new version puts the item back on the list. Nothing left: "You are up to date."
     **All announcements** and **Open the Library** follow the list.
   - **My certificates**: the person's own certificates, soonest expiry first (so expired, then expiring soon, then
     valid): type, "Expires 3 Nov 2026 · expired 8 days ago", the number when set, and the expiry badge of the office
     lists (Expired, 30 / 60 / 90 days, Valid; words and icon, not colour alone). Read only, with the note "Contact the
     office to update a certificate." Without any: "No certificates on record."
   - **Crew acknowledgements** (the Master only): the Required items that not everyone on board has acknowledged in
     the current version, oldest first, each with "1 of 3 acknowledged" and its bar, linking to the item's
     acknowledgement overview (16). When everyone has: "Everyone on board has acknowledged every required document."
   - From 992 px To read (and the Master's card) and the certificates sit side by side.
   - **Navigation for the crew on phones** (below 768 px): a tab bar fixed at the bottom of the screen (`layout/parts
     :: tabBar`) with **Home**, **Announcements** (P27), **Library**, **Notifications** and **More** (P31: My profile,
     My crew and Sign out, see 20) (the Notifications unread count as a badge on the icon, in words
     for screen readers, kept up to date by `js/notifications.js` after Mark as read), each a 56 px target, the active
     one navy and semibold with a bar on its top edge and `aria-current="page"`. It replaces the offcanvas menu and
     the bell for ship users; office users keep both. From 768 px the sidebar has the same entries (On board: Home,
     Announcements, Library and, since P31, My crew; Account: Notifications and My profile). The page keeps room for the bar, and the offline banner and the
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

19. Crew announcements (P27, any ship user): the crew read and acknowledge the announcements the office sends (14).
   `announcement/ShipAnnouncementController`, `templates/announcement/ship-list.html` and `ship-detail.html`; English
   only, as every crew screen. **Announcements** sits between Home and Library in the sidebar and the phone tab bar.
   - `/ship/announcements`: the announcements the signed-in person is a recipient of, newest first. Each row is one
     link (the whole row) with the title, the date sent (UTC) and "To acknowledge" or "Acknowledged". One to
     acknowledge has a bold title and the "To acknowledge" badge; the words carry the state, weight and colour only
     repeat it. With none: "No announcements yet".
   - `/ship/announcements/{id}`: All ships or the ship (with its IMO number) as the eyebrow, the title, "Office ·
     <sender name>" (just "Office" when the sender was deleted) and the date and time sent in UTC, the message as plain
     text with its line breaks, then **I have read this**, filling the reading column (48 rem wide at most) and, on
     phones, pinned to the bottom of the screen above the tab bar until the message ends, as on a Required library
     item (16).
   - **I have read this** (`POST /ship/announcements/{id}/acknowledge`) sets the person's `acknowledgedAt`, records
     "Announcement acknowledged" in the audit log (10) and comes back with "Thank you. Your acknowledgement is
     recorded."; the button is replaced by "Acknowledged · 12 Nov 2026 · 14:05 UTC". Pressing it again (a second tab,
     a resend over a bad link) changes nothing: same time, no new audit entry, no message.
   - Someone who is not a recipient (another ship, joined after it was sent) gets 404 on both URLs, and never sees it
     in their list. The crew never see who else has or has not acknowledged: only their own state.
   - Unacknowledged announcements are on the Home page's To read (18) until acknowledged.
   - To try it: as `office@example.com` send an announcement to MV Demo (14). As `cook.demo@example.com` /
     `crew123`: **Notifications** has "Announcement · <title>"; open it, press **I have read this**. As the office, the
     announcement's page shows "1 of 2 acknowledged" with Demo Cook and the time under Acknowledged.

20. My profile and My crew (P31, any ship user): a seafarer adds a little about themselves, and sees who is on board.
   `profile/ProfileController`, `mycrew/MyCrewController`, `core/profile/` (the `crew_profile` table, `ProfileService`,
   the photo handling), `templates/profile/` and `templates/mycrew/`; English only, as every crew screen. Every field
   is optional, and what a person fills in is seen by everyone on board the same ship now and by the office.
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
    list's default. Pages: 25 rows, or 50 (`size=50`), with "1 to 25 of 412", **Previous** and **Next**
    (`layout/parts :: pager`, shown when there is more than one page of 25; a disabled link has no `href`); changing
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
    Galley items (Add item, Import); Requests, "No requests yet" (**Go to Ships**; on the tab **Go to galley items**),
    or when every approved request is exported "Nothing left to export" (**Show exported requests**, `?exported=yes`);
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
      a card header as well. The export buttons on **Requests** (**Export selected**, **Export all not exported**,
      P21) are in the page header and still submit their forms without JavaScript (`form="exportForm"`,
      `form="exportAllForm"`);
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
    - office: **Operations** (Overview `/office`, Ships, Crew, Certificates, Announcements, Requests),
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
      exactly those, `/office/certificates?status=expired,30`, P20), **Requests** approved and not
      exported, **Notifications** unread. One count query each on every page (`navCounts` from `NavigationAdvice`,
      office users only; `unreadNotifications` from `NotificationBellAdvice`). The Notifications pill and the bell
      follow Mark as read without a reload (13, P23; the entry has `data-nav="notifications"`);
    - rail and preference (P29), from 768 px: the sidebar is a 64 px rail by default (the mark, the entries' icons, the
      user's initials and a sign-out icon); each entry's name shows as a tooltip beside it on hover and on keyboard
      focus, and stays in the link for screen readers; the active entry keeps its pink bar; a counter becomes a
      10 px dot with the number and its meaning still in the link's text. **Expand menu** / **Collapse menu** at
      the bottom (a chevron, `aria-expanded`, `aria-controls="sidebar"`; `layout/parts :: sidebarToggle`) switches
      to today's full 256 px sidebar and back. The choice is the cookie `sidebar=expanded|collapsed` (one year,
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
    - an action that asks only sometimes (P21: **Export selected** on Requests, when a selected request was exported
      before) keeps its submit button and adds `data-confirm-when="<selector>"`: the dialog opens when an element of
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
      and "Saving…" (sign-in "Signing in…", sign-out "Signing out…", request export "Exporting…": `data-busy-label`).
      A second click, a double click or Enter sends nothing more. Back to the page (browser Back, `pageshow`) gives
      the buttons back. The request export comes back as a page too (P21: its file is a GET the page starts). The
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
| `BOOTSTRAP_OFFICE_EMAIL` | first office user; required only while no office user exists |
| `BOOTSTRAP_OFFICE_PASSWORD` | first office user; required only while no office user exists |
| `SUPPORT_CONTACT` | optional free text shown as "Office contact" on the "link is invalid or has expired" page and as "Questions? Contact: …" in the footer of the invitation and password reset emails, e.g. `Crewing department · crewing@example.com · +30 210 000 0000`; not shown when empty |

Outside dev, SMTP login and STARTTLS are required (no plain-text fallback). Keep secrets out of
checked-in files (`application*.yml`, `docker-compose.yml`) and do not enable `mail.debug` or DEBUG
logging for `jakarta.mail` / `org.eclipse.angus.mail`: that output contains the SMTP login.
