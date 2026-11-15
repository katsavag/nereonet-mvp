# 22. Approving and declining a request

Approving and declining a request, and the ship order (P34, the Master only). `core/request/RequestService`
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
  declined: <reason>", linking to `/ship/requests/{id}` (their request page, P54, see [31](31-requests.md)); "Request
  approved" or "Request declined" goes in the audit log; the Master is back on `/ship/approvals` with a message.
- **Only once**: the request row is locked while deciding. A request already approved, declined or cancelled (a second click,
  another tab) changes nothing and the request page says so. Another ship's request is a 404; the Chief cook and
  crew get 403.
- **The ship order** (`ShipOrder`, `ShipOrderLine`): status OPEN, SENT, CONFIRMED or CLOSED, with requested port,
  needed by, sent by/at, confirmed port, delivery date, confirmed by (shown as "Delivery port" and "Scheduled by"), exported at and, once received on board (P51), received at/by and the shortage fields. A ship has at most one OPEN
  order: `addApprovedRequest` locks the ship's row, finds the OPEN order or creates it, and per request line adds
  the quantity to the order line of the same item (one line per item) or makes that line. Each request line keeps
  a link to its order line (`RequestLine.shipOrderLine`), so the order can show who asked for what. Its page is
  section 23.
- To try it: as `master.demo@example.com`, open a waiting request and **Approve** it; open the other and
  **Decline** it with a reason. Both requesters see their notification.
