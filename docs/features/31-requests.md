# 31. Requests

Requests (P54, the Chief cook and crew members): asking the Master for items. `shiprequest/`
(`ShipRequestController`, `ShipRequestService`, `RequestForm`, `RequestFilter`), `templates/shiprequest/`,
`js/request-form.js`; styles in `app.css`, "Requests (P54)". English only, as every crew screen.
`/ship/requests` and `/ship/requests/**` are for CHIEF_COOK and CREW_MEMBER (`SecurityConfig`): the Master does not
ask, they decide (21, 22), and gets 403. A request is not an order: only an approved one goes into the ship order.
- **Where**: **Requests** in the sidebar under Supplies (P65), with the number of the person's own requests still
  waiting for the Master (one count query, `ItemRequestRepository.countByRequestedByEmailAndStatus`); on phones the
  Chief cook's third tab (P65) and a row of **More** for crew members, with the same number (the More tab adds it
  to its badge). Home ([18](18-crew-home.md)) has a **My requests** card for crew members ("1 waiting"), and the
  cook's Inventory summary a **Requests
  waiting** tile ([30](30-stores.md)).
- **List** (`/ship/requests`): the signed-in person's own requests, newest first: the request number (one link over
  the row), when it was sent (UTC), the number of items and the status (`request.status.*`: Waiting for the master,
  Approved, Declined, Cancelled). Filters as ordinary links with their counts: **All**, **Waiting**
  (`?status=waiting`, PENDING), **Approved** (`?status=approved`) and **Declined** (`?status=declined`, REJECTED);
  a cancelled request is under All only, and an unknown `?status=` shows All. **New request**
  in the header; with no request yet the empty state carries it. Not paged.
- **A request** (`/ship/requests/{id}`): when it was sent, its status, who decided and when, the Master's reason when
  declined; then its lines by category (Provisions, then Personal items): item, unit, quantity, with a **New item**
  badge on a new-item line. Only the person who asked opens it; anyone else (crewmates, the cook, another vessel)
  gets 404. While it is PENDING it has **Edit** and **Cancel request**; afterwards it is read only and says why
  ("The master approved this request…", "…declined…", "You cancelled this request…").
- **New request** (`/ship/requests/new`) and **Edit** (`/ship/requests/{id}/edit`), one form: the vessel's active
  items of both categories, grouped **Provisions** then **Personal items**, each by name (side by side from 992px; while one is closed, it is as wide as its header and the open one takes the rest);
  each item a row with its name, a quantity field and the unit, in fixed columns so the fields line up. A blank quantity means not picked; a picked one must be above zero with at
  most 3 decimals (`ShipOrderService.isValidQuantity`, the order's rule; "2,5" is read as 2.5), its error under
  the row. No item names are typed. With JavaScript a **Search items** field filters the rows by name as you type
  (`js/request-form.js`, P50's pattern; without it the whole list shows) and Enter never sends the form.
  Each group is a card that can be closed (P87, `layout/parts :: foldCard`): its header says "Provisions · 40 items
  · 3 picked", the closed cards are remembered on the device per vessel (`js/fold-cards.js`), what is typed in a
  closed card is still sent, and the search opens every card with a match.
  - The Chief cook only: the provisions **Out of stock** or **Low** (`StockService`) come first, under **Running
    low**, Out of stock first, each with what is on board and its status badge. Crew members never see stock.
  - The Chief cook only: **Add an item that is not on the list**: Name (required, 100 characters), Unit (optional,
    chosen from the list of `core/form/ItemUnits`), **Which list does it belong to?** (Provisions, the default, or
    Personal items; `RequestLine.newItemCategory`) and Quantity, **Add to request**: it becomes a new-item line
    under the fields, with its category, its quantity and **Remove**. Add and Remove post the form to `#newItems`
    and show it again at that card with everything typed kept, so they work without JavaScript; with it,
    `js/request-form.js` sends them with fetch and replaces only that card. A name already on the list ("basmati RICE") is refused: "Basmati rice is on the list: enter its
    quantity there." Something typed there when **Send to the master** is pressed is added first. On approval
    `RequestService.approve` turns the line into an item ([22](22-approving-requests.md)).
  - **Send to the master** builds the `ItemRequest` and calls `RequestService.submit` (saved PENDING, audited, the
    Master notified, [21](21-approvals.md)), then opens the request with "Request sent to the master." Nothing picked: "Pick at least
    one item: enter its quantity." The form does not repeat the service's checks: `submit` and `update` throw
    `InvalidRequestException` (still an `IllegalArgumentException`) with its `Problem` (`NO_LINES`, ...), which the
    page shows as the form's error. An item made inactive while the form was open: "An item you picked is no longer
    on the vessel's list…".
  - Editing starts from the request's quantities and new items, and also keeps the items already on it if the
    office has since made them inactive; **Save** replaces its lines.
- **While PENDING** (`core/request/RequestService`): `update(user, id, lines)` replaces the lines with the same
  checks as `submit` and records "Request changed"; `cancel(user, id)` makes it CANCELLED (decidedBy and decidedAt
  stay empty: nobody decided) and records "Request cancelled", so it leaves the Master's Pending tab and never
  reaches an order. Both lock the row with `findForDecision`, as approve and decline do, and read it again after
  the lock, so a decision and a change never interleave: once the Master decided, they change nothing and the page
  says "The master has already decided on this request, or it was cancelled. Nothing was changed." Nobody is
  notified of a change or a cancel. Cancel request asks first (P10; `GET …/cancel` is the plain confirmation
  page). Only an enabled Chief cook or crew member, on their own request of their own vessel (`AccessDeniedException`,
  NOT_FOUND).
- Tests: `shiprequest/ShipRequestFlowTest`.
- To try it: as `cook.demo@example.com` / `crew123`, **Requests** → **New request**: Sugar and Butter are under
  Running low; enter 10 for Sugar, add "Saffron", g, 50, and **Send to the master**. Then **Edit** it, or
  **Cancel request**. As `master.demo@example.com` the request is under Approvals; `/ship/requests` is a 403. As
  `ab.demo@example.com` the form has no stock and no new items.
