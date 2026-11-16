# 21. Approvals

Approvals (P33, the Master only): the item requests of the Master's own ship, waiting for their decision.
`approval/ApprovalController` and `ApprovalService`, `templates/approval/`; English only, as every crew screen.
`/ship/approvals/**` is for MASTER only (`SecurityConfig`): the Chief cook and crew members get 403. A request of
another ship is a 404.
- **Where**: **Approvals** in the sidebar under Supplies (P65), with the number waiting as a pill; on phones it is
  the Master's second tab (P65; a row of More before), with the same number as a badge.
- **List** (`/ship/approvals`): three tabs (ordinary links), each with its count (P48). **Pending** (the default):
  PENDING requests, oldest first. **Approved** (`?tab=approved`) and **Declined** (`?tab=declined`): APPROVED or
  REJECTED requests, newest decision first, each with "Approved" or "Declined" and the date. The old
  `?tab=decided` is a 301 to Approved. Each row is one link: who asked ("Demo Cook · Chief cook"), the request
  number, when (UTC) and the number of items.
- **A request** (`/ship/approvals/{id}`): who asked, when, its status ("Waiting for the master", "Approved",
  "Declined", "Cancelled"), who approved or declined it and when ("Approved" / "Declined"), and for a declined one the reason; then its lines in one card per
  category (Provisions, then Personal items): name, quantity and unit. A line for an item not in the ship's list
  yet has a **New item** badge (also on the office's request page, 15; such a line is under the category the
  Chief cook chose for it, Provisions or Personal items, and the CSV has the name and unit given).
- **Submitting** (`core/request/RequestService.submit`, called by the Chief cook's and crew's Requests pages, [31](31-requests.md)): saves
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
- To try it: as `master.demo@example.com` / `crew123`, **Approvals** shows the two requests under Pending; open
  Maria Santos's to see Provisions and Personal items. As `cook.demo@example.com`, `/ship/approvals` is a 403.
