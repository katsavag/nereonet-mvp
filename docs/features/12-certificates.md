# 12. Certificates

Certificates (signed in as an office user): on a crew member's **Edit** page (`/office/crew/{id}/edit`) the
**Certificates** card lists their certificates (type, optional number, expiry date) with **Add certificate**,
**Edit** and, in the row's **⋯** menu, **Delete** (after the confirmation "Delete STCW Basic Safety?").
**Certificates** in the sidebar (`/office/certificates`, P20) lists every certificate, soonest expiry first. The
**Expires on** column shows the date and how far it is ("in 23 days", "expired 5 days ago"); the status badge has
an icon as well as a colour: a warning triangle for Expired, a clock for 30, 60 or 90 days, a tick for Valid.
Searchable (type, number, crew member), sortable by expiry, type, crew member, ship and number, 10 per page (20, 50 or 100)
(paged in the database), filterable by ship, **Active ships only** (`activeShips=true`) and **Status**
(`?status=`): `expired`; `30`, `60`, `90` (not expired, expiring within that many days, today included, so
"Within 60 days" includes "Within 30 days"); `valid` (more than 90 days left); `all`. Without a status, or with one
it does not know: expired and within 90 days. States combine with a comma, which is how the counts link
to exactly what they count (each tested in `CertificatePlanningFlowTest`):
- the sidebar counter (expired or within 30 days, every ship): the **Certificates** entry opens
  `?status=expired,30` while the counter shows; without a counter, the list as it is;
- the Overview's Certificates chip, then **Open the full list** (the same, crew of active ships; P43, "Full
  lists" before, P65 the By category tile before): `?status=expired,30&activeShips=true`;
- the ship page's **Certificates** tab count (expired or within 90 days): the tab's **List** view.
P43: the crew member's name in the list links to their page (`/office/crew/{id}/edit`), as in the Crew list; each
row has a checkbox and **Snooze**, a snoozed certificate's row is muted with "Snoozed by … until …" and **Undo**,
and the Status filter has **Snoozed** (`?status=snoozed`; the other states leave snoozed certificates out, see [27](27-snoozing.md)).
**Export** (page header) downloads the list as filtered, searched and sorted, every page, as an Excel workbook (P39): `certificates-<date>.xls`, sheet "Certificates", with the columns
`imo_number, ship_name, crew_member, rank, type, number, expires_on, days_left, status` (rank as its code,
`SECOND_OFFICER`; `expires_on` a date cell, `days_left` a number, negative once expired; status `expired`,
`30_days`, `60_days`, `90_days` or `valid`, the badge). It is a plain link (GET, nothing changes, not in the audit
log).

**The export format** (P39, every office export: certificates, the certificate matrix and ship orders, see [24](24-office-orders.md);
`core/export/XlsExport`, Apache POI, already used by the imports): one `.xls` workbook (Excel 97-2003,
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
on the Certificates list and on the crew member's **My profile** ([20](20-my-profile-and-my-crew.md)). Only the office
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
| Order **Needed by** (Master, [23](23-ship-order.md)) | today | 2 years ahead |
| Order **Delivery date** (office, [24](24-office-orders.md)) | 1 year ago | 2 years ahead |
| Audit log **From** / **To** ([10](10-audit-log.md)) | 30 years ago | 1 year ahead |

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
P42 (see [26](26-required-documents.md)): the types required of someone on board are columns too, and a required cell with no certificate
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
