# 8. Crew

Crew (signed in as an office user): **Crew** in the sidebar (`/office/crew`) lists every user with a ship role
and every pending crew invitation in one list, searchable (name or email) and filterable by ship and by **Status**: Active, Invited, Invitation expired,
Inactive (query parameters, so other pages can link to a filtered list: `/office/crew?status=expired`,
`?shipId=1&status=invited`; an unknown value shows every status), sortable by name (the default), email, rank, ship
and status, 10 per page, 20, 50 or 100 on request (P43; see "lists: search, sort and pages (P19)" below; users and invitations are paged together
in the database, one native SQL `UNION ALL` in `crew/PeopleQueries`). The ship's **Crew** tab is the same list for
that ship, without the ship filter. An invitation shows its status with its date,
"Invited · expires in 3 days" or, in the warning tone, "Invitation expired · 2 days ago", and has, in the row's **⋯** menu, **Resend** (one
click, also when expired: new link, new expiry date, new email) and **Cancel invitation**
(asks first, see [6](06-invitations.md)); both come back to the list as it was: search, filters, sort and page (P17, P19). **Add crew member** opens a pop-up form
(full name, email, rank, active ship; no role, see [6](06-invitations.md)) that sends an invitation email (see Mailpit) and names the address in
its success message;
the member appears at once as "Invited" and becomes a user when they register. Clicking a member's name (or **Edit** in the row's **⋯** menu) opens their page; it changes full name, rank
and ship (active ships only; the email cannot be changed), and saving sets the rank's role. Picking another ship
moves the crew member. The one active Master / Chief cook per ship rule is checked here too, on the rank field.
The list shows the rank; there is no Role column and no access badge. Users from before P18 whose rank does
not set the role they have, or who have no rank, keep their role; the list does not mark them, only
their page
names the access they have now ("Access now: Master. Saving applies
the access of the rank above."). Saving there applies the mapping. Under the form a **Profile** card (P31) shows,
read only, what the crew member has filled in about themselves (photo, languages, nationality, bio; "Not given"
where empty): only they can change it (see [20](20-my-profile-and-my-crew.md)). Deleting a crew member also deletes their photo files.
P42 (see [26](26-required-documents.md)): a **Documents** column ("5 of 7" required documents held, sortable), the **Missing documents**
filter (`?documents=missing`), **Export required documents** and a **Required documents** card on the member's
page.

**Import from file** (`/office/crew/import`) invites many crew members at once, from the file the crewing system
produces or from the template. Four steps, shown at the top of every page (**Upload**, **Map columns**,
**Review**, **Done**; the current one marked, `aria-current="step"`), with **Cancel** on each (it forgets the
upload and goes back to the crew list). Nothing is saved and no email is sent before **Import and invite** on
the Review step. The steps are shared with the item import (see [11](11-galley-items.md) and "Import from a file" under Frontend assets):
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
  one it checked. The server checks every row again on submit; without the script the page still works); **Change
  column mapping** goes back. The row number and name columns stay in place when the table scrolls sideways. A
  file with a `Role` column (the template from before P18) is accepted: that column is ignored and the review
  page says so. **Import and invite** (enabled when no included row has errors) sends one invitation email per
  row.
- **Done**: how many were invited and skipped (excluded), with a link to the crew list.
- To try it on the dev database: upload [`samples/crew-sample.xlsx`](samples/crew-sample.xlsx), 10 invented crew
  members (example.com emails) for the demo ship `MV Demo` (IMO 9074729): one Master, one Chief cook and eight crew
  members. Regenerate it with `./mvnw test -Dtest=CrewSampleFileGenerator -DgenerateSample=true`.
