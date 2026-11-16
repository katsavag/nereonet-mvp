# 23. The ship order and Send to office

The ship order and "Send to office" (P35, the Master only; the Chief cook reads it, see [32](32-cook-reads-order.md)). `core/order/ShipOrderService` (`changeQuantity`,
`removeLine`, `send`) and `shiporder/` (`ShipOrderController`, `ShipOrderPageService`, `SendOrderForm`).
- **Ship order** ("Vessel order" on screen) in the sidebar under Supplies (on phones the Master's third tab, under More for the Chief cook), with the Master's
  counter (the Chief cook has the entry without it, P55). It counts orders, not lines (P59): the orders that need the Master, the OPEN order once it has a line (to send) plus the
  CONFIRMED ones (to receive). One collecting order shows 1 whatever its number of lines; a collecting order and a
  scheduled delivery show 2; SENT orders wait for the office and do not count. Screen readers hear "2 orders need
  you". `NavigationService.ordersToAct` (model attribute `ordersToAct`), one count query
  (`ShipOrderRepository.countToActOnShipOf`).
- **`/ship/order`**: the ship's OPEN order, its lines by category (Provisions, then Personal items), each with the
  item name, quantity and unit and, under it, who asked for it: "Chief Cook 6, A. Santos 4" (the Master and the
  Chief cook by rank, others as initial and surname, each person once with what they asked for in all). Without an
  OPEN order, or with no lines: a compact card, "Nothing to order yet. Requests the Master approves are added here."; for the Master with requests
  waiting, also a link to Approvals ("3 requests are waiting for you").
- **Three views** (P74), switched by links under the header (`layout/parts :: viewSwitch`, `?view=`): **Current
  order** (`?view=current`, also for an unknown value: the OPEN order and Send to office), **In progress**
  (`?view=progress`) and **Received** (`?view=received`), the last two with their count on the tab when above zero.
  Without `?view=` the page opens on Current order while it has lines, else on In progress when there are orders in
  progress, else on Current order; the line changes and the send form come back to `?view=current`.
  `ShipOrderPageService.page` loads only the chosen view. When an order in progress needs the user (the Master: a
  scheduled delivery to receive; the Chief cook: one to count), the Current order view says so in one full-width
  warning row above the order, "1 delivery to receive", a link to In progress.
- **In progress** (P59, its own view since P74): one card per order of the vessel that is SENT ("Pending") or
  CONFIRMED ("Delivery scheduled"), most urgent first: the scheduled deliveries by delivery date (earliest first),
  then the orders waiting for the office by sent date (waiting longest first). Each card: "Order #12", the status
  badge (`shiporder/parts :: statusBadge`), the number of items (lines the office did not remove) and one line:
  "Delivery: Antwerp, 20 Nov 2026" once scheduled (the wording of Inventory's Next delivery), or "Sent on 14 Nov 2026 ·
  waiting for the office to schedule the delivery". A delivery whose date (UTC) has passed and is not received yet
  keeps its place and says "Delivery was due 12 Nov 2026, Antwerp", in words with a warning icon and border. The
  enabled Master has **Confirm receipt** (the primary, to `/ship/orders/{id}/receipt`, P51) and **Open order** on
  a scheduled delivery; everyone else (the Chief cook, [32](32-cook-reads-order.md)) only **Open order**. Without any: "Nothing in
  progress". One query (`findAllByShipIdAndStatusIn`).
- **Editing** (only while OPEN): each line's quantity is a small form with **Save**
  (`POST /ship/order/lines/{id}/quantity`; above zero, at most 3 decimals, otherwise an error and nothing saved),
  and **Remove** (`POST /ship/order/lines/{id}/remove`; the requests stay approved, their lines are unlinked). Each
  saves at once; nobody is notified and nothing goes in the audit log.
- **Send to office**, under the lines: **Requested port** (required, up to 100 characters), **Needed by** (required,
  not before today in UTC) and an optional **Note to the office** (up to 1000). `POST /ship/order/send`: only an
  OPEN order with at least one line becomes SENT, with sent by and at; "Order sent to office" goes in the audit log
  and every enabled office user gets a notification "New order from <ship name>" (it opens the order,
  `/office/orders/{id}`). Then the order is read-only for the ship, on `/ship/orders/{id}` (the office may still correct
  its lines, 24; the page then shows each change, P50); each line keeps the quantity sent (`originalQuantity`); the next approved request opens a new OPEN
  order. Every change and the send lock the ship's row, as approvals do, so an approval never lands in an order
  being sent.
- **Received** (`/ship/order?view=received`, P74): the CLOSED orders, newest sent first, all of them, each row
  with the delivery port and date, a neutral status and when it was sent. `/ship/orders` (the list of every sent
  order before P74) redirects there. `/ship/orders/{id}` shows one order
  with its lines (the OPEN one redirects to `/ship/order`).
- Status labels (`order.status.*`, the same on the ship's pages and the office's, English and Greek, P65): OPEN
  "Collecting", SENT "Pending", CONFIRMED "Delivery scheduled" (P51), CLOSED
  "Received". On the ship's pages a Pending order also says "Waiting for the office to schedule the delivery" (the
  order page under the badge, the Master's home on its row, the In progress card).
- Another ship's line or order is a 404; crew members get 403, and so does the Chief cook for every POST (the
  cook reads the pages, P55). New column `ship_order.office_note`
  (nullable, Hibernate adds it); new notification type ORDER (`RequestSchemaMigration` re-creates the check).
- **The Master's home** (`/ship` as MASTER, `masterhome/`): P59 adds **Deliveries to receive** to its cards,
  after Vessel order: the number of the vessel's CONFIRMED orders, highlighted ("to do") above zero like Requests to
  approve; it opens Vessel order, or the receipt itself when there is exactly one. (The Vessel order card still
  counts the lines of the OPEN order.)
- Tests: `shiporder/ShipOrderFlowTest` (orders in progress, their order, the Master's and the cook's actions, the
  overdue delivery, the three views, the counter), `masterhome/MasterHomeFlowTest` (Deliveries to receive).
- To try it: as `master.demo@example.com`, approve the waiting requests, open **Ship order**, change a quantity
  and send it with port "Rotterdam"; `office@example.com` sees the notification. The sent order is under In
  progress; once the office schedules its delivery, the card has Confirm receipt and the counter shows it.
