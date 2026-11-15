# 27. Snoozing alerts

Snoozing alerts and the Overview redesign (P43, office users only). An office user can put a certificate or
vetting item aside for a while, with a reason, so the counters and the Overview show what still needs a decision.
`core/alert/` (`AlertKind`, `AlertKey`, `AlertLevels`, `Alert`, `AlertSnooze`, `AlertSnoozeRepository`,
`SnoozeReason`, `SnoozeEndReason`, `SnoozeService`) and `alert/` (`AlertController`, `AlertService`, `AlertView`,
`SnoozeForm`, `AlertReturn`); templates `alert/snooze.html` and the fragments in `alert/parts.html`; styles in
`app.css` section "Snoozing alerts (P43)"; `dashboard/SnoozeSyncJob`. The Overview itself is described in 4.
- **What can be snoozed**: a certificate expired or expiring (one item), and a vessel's open vetting items: the
  RightShip inspection, the Safety Score, the SIRE 2.0 report, the SIRE 2.0 operator comments, the SIRE 2.0
  observations past their target date (one item each; `AlertKind`). Orders, invitations, announcements and
  required documents cannot be snoozed: they have an action of their own.
- **Where**: the Certificates list ([12](12-certificates.md)), the Vetting list ([25](25-vetting.md)) and the Overview's Needs attention list ([4](04-overview.md)) have a
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
  tiles, the chips, Next 30 days and the Fleet counts; the Items tile says "3 snoozed") and the certificate
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
- The crew Home's sign-out line does not repeat the name ([18](18-crew-home.md)); the sidebar shows the user's photo
  where there is one (`NavigationAdvice`'s `currentPhoto`, `CrewProfile.photoUrl(size)` with a version
  parameter so a new photo shows at once), initials otherwise; the Greek ship types read "Bulk carrier / Tanker /
  Container / General cargo / Other" in Greek and the vetting filter "Σύστημα αξιολόγησης"; the page size of every
  list is 10 by default (20, 50 or 100; `ListQuery.SIZES`).
- Tests: `alert/SnoozeFlowTest`, `dashboard/DashboardFlowTest`, `requirement/RequirementsFlowTest`.
