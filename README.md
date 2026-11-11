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

   **Overview** (`/office`, the office home page): fleet totals (active ships, active crew, open items that need
   attention) and one card per active ship with crew on board (enabled users), pending invitations, certificates
   expired / expiring within 30, 60 and 90 days, announcements not yet acknowledged by everyone on that ship,
   approved requests not exported and Required library items not acknowledged by everyone on board (links to the
   ship's library). Each number links to its page filtered to that ship. A ship with an expired
   certificate, one expiring within 30 days, or an approved request not exported gets a "Needs attention" badge and
   is listed first; these are the "open items". With the dev data, the demo ship needs attention (two approved
   requests not exported); export them on **Requests** and it no longer does.

5. Ships (signed in as an office user): **Ships** in the sidebar (`/office/ships`). **Add ship** (a pop-up form) asks for
   the IMO number (7 digits with a valid check digit, e.g. `9074729`; `IMO 9074729` also works), name,
   type and flag (type to filter the countries, e.g. "Greece (GR)"; only the code `GR` is stored). Ships can be edited in a pop-up (except the IMO number) and deactivated or activated, never deleted.
   Inactive ships stay in the list but cannot get new crew invitations.

6. Invitations (signed in as an office user). The email with the `/register?token=...` link
   appears in Mailpit.
   - Office users: **Office invitations** in the sidebar (`/office/invitations`), then **Invite office user** (opens a pop-up form).
     The list shows each invitation's status (Pending, Expired, Accepted, Cancelled; "Expired" is a pending invitation
     past its expiry date) and can be filtered by status. Pending invitations, expired or not, have **Resend** (new link
     and expiry date, new email; the old link stops working) and **Cancel** (the link then shows the "invalid or expired" page).
   - Crew members: **Add crew member** on the **Crew** page (see 8), or open
     `/office/ships/{shipId}/invitations/new` (the demo ship is usually id `1`). The form also asks for an optional rank (from a fixed list),
     which is copied to the user on registration. A ship can have only one active Master and one
     active Chief cook; inviting a second one shows an error on the role field.

7. Registration: open the `/register?token=...` link from the invitation email (signed out, e.g. in a
   private window), set a password, then sign in. Each link works once; after that, or after it expires,
   it shows an "invalid or expired" page.

8. Crew (signed in as an office user): **Crew** in the sidebar (`/office/crew`) lists every user with a ship role
   and every pending crew invitation (status "Invited"), filterable by ship. **Add crew member** opens a pop-up form
   (full name, email, optional rank, role, active ship) that sends an invitation email (see Mailpit);
   the member appears at once as "Invited" and becomes a user when they register. **Edit** changes full name, rank, role
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

9. Office users (signed in as an office user): **Office users** in the sidebar (`/office/users`).
   Any user, crew or office, can be deactivated (they can no longer sign in) and activated again. A deactivated user
   can also be deleted (**Delete**, after a confirmation): the user and every invitation for their email are removed,
   so they can be invited again. Their audit log entries stay and show "Deleted user"; invitations they sent stay.
   They are removed from the recipient lists of announcements; announcements they sent stay and show "Deleted user".
   You cannot deactivate or delete yourself.

10. Audit log (signed in as an office user): **Audit log** in the sidebar (`/office/audit`). Lists who did what and
   when, newest first, 50 rows per page, filterable by ship and date range (UTC days). Recorded so far: ship added,
   edited, deactivated, activated; invitation sent, resent, cancelled; crew imported (counts only); user registered, edited, deactivated, activated, deleted;
   item added, edited, deactivated, activated; items imported (counts only); certificate added, edited, deleted;
   certificate reminder sent (by "System"); announcement sent; requests exported (count and ids).
   Entries are read-only.

11. Galley items (signed in as an office user): **Items** on a ship's row in **Ships** (`/office/ships/{id}/items`).
   Each item has a name (unique per ship, ignoring case), a short unit (e.g. `kg`, `lt`, `pcs`) and an optional category.
   **Add item** and **Edit** open a form; items can be deactivated and activated, never deleted.
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
   **Edit** and **Delete**. **Certificates** in the sidebar (`/office/certificates`) lists every certificate that
   expires within 90 days or has expired, soonest first, filterable by ship, with a badge: Expired, 30, 60 or 90 days.

   A daily job (06:00 UTC; change it with `CERTIFICATE_REMINDER_CRON`, a Spring cron such as `0 0 6 * * *`) reminds
   about certificates of active users that expire within 90, 60 or 30 days: the crew member gets an email and an in-app
   notification, every active office user gets an email. Each certificate gets one reminder per threshold; one entered
   late (e.g. 45 days before expiry) gets only the 60-day reminder now, not the 90-day one as well. Changing the expiry date starts the reminders again.
   To try it without waiting, set `CERTIFICATE_REMINDER_CRON` to e.g. `0 */1 * * * *` (every minute), add a
   certificate expiring in 60 days, and watch Mailpit.

13. Notifications (any signed-in user): the bell in the top bar (phones) and **Notifications** in the sidebar show the
   number of unread notifications. `/notifications` lists them, newest first; opening it marks them as read
   (new ones stay highlighted on that visit).

14. Announcements (signed in as an office user): **Announcements** in the sidebar (`/office/announcements`) lists every
   announcement, newest first, with its target (All ships or a ship), date and e.g. "1 of 2 acknowledged".
   Filter by ship to see the announcements sent to that ship or to all ships.
   **New announcement** asks for the target (All ships or one active ship; nothing is preselected), a title and a
   plain-text message. The recipients are fixed when it is sent: every active user with a ship role on that ship
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
   403). Office users open a ship's library from the **Library** button on its row in **Ships**
   (`/office/ships/{id}/library`, same content as the crew page) and add items there
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
   **Publish new version** makes it the version the crew see and records it in the audit log. Earlier versions and
   their acknowledgements stay in the database. Nobody has acknowledged the new version, so a Required item shows
   "Required · to acknowledge" again and the ship's users get "Library · New document to read" again. To try it:
   as `cook.demo@example.com` acknowledge "Drug and alcohol policy"; as `master.demo@example.com` open it, press
   **Edit this item**, enter a new version label and press **Publish new version**; sign in as the cook again.

   **Retire an item**: the Master sees **Retire this item** on an item of their ship (Chief cook and crew members
   do not; `POST /ship/library/{id}/retire` gives them 403); office users have it at the bottom of the item's
   **Edit this item** page (`POST /office/ships/{shipId}/library/{id}/retire`). A confirmation asks "Retire this
   item? Crew will no longer see it. This is final." **Retire** hides the item from every list, its page and file
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
  - type h1 28 px, card title 18 px, body 16 px, tables and helper text 14 px, eyebrow 12 px; `.num` gives
    tabular figures for IMO numbers, dates, counts and quantities;
  - spacing 4 / 8 / 12 / 16 / 24 / 32 px: 24 px between page sections, 16 px inside cards, card padding 20 px.

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

Outside dev, SMTP login and STARTTLS are required (no plain-text fallback). Keep secrets out of
checked-in files (`application*.yml`, `docker-compose.yml`) and do not enable `mail.debug` or DEBUG
logging for `jakarta.mail` / `org.eclipse.angus.mail`: that output contains the SMTP login.
