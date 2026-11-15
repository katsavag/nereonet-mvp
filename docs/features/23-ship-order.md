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
  OPEN order, or with no lines: "Nothing to order yet. Approved requests are collected here."
- **Orders in progress** (P59): above the OPEN order, one card per order of the vessel that is SENT ("Pending") or
  CONFIRMED ("Delivery scheduled"), most urgent first: the scheduled deliveries by delivery date (earliest first),
  then the orders waiting for the office by sent date (waiting longest first). Each card: "Order #12", the status
  badge (`shiporder/parts :: statusBadge`), the number of items (lines the office did not remove) and one line:
  "Delivery: Antwerp, 20 Nov 2026" once scheduled (the wording of Stores' Next delivery), or "Sent on 14 Nov 2026 ·
  waiting for the office to schedule the delivery". A delivery whose date (UTC) has passed and is not received yet
  keeps its place and says "Delivery was due 12 Nov 2026, Antwerp", in words with a warning icon and border. The
  enabled Master has **Confirm receipt** (the primary, to `/ship/orders/{id}/receipt`, P51) and **Open order** on
  a scheduled delivery; everyone else (the Chief cook, [32](32-cook-reads-order.md)) only **Open order**. No section without any; with some,
  the OPEN order follows under "Current order", unchanged. `ShipOrderPageService.inProgress`, one query
  (`findAllByShipIdAndStatusIn`).
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
- **`/ship/orders`**: **All orders** (P59; "Past orders" before, same URL): every order sent (SENT, CONFIRMED,
  CLOSED), newest sent first, with status, requested port,
  needed by and, once the office has set them, confirmed port and delivery date. `/ship/orders/{id}` shows one
  with its lines (the OPEN one redirects to `/ship/order`).
- Status labels (`order.status.*`, the same on the ship's pages and the office's, English and Greek, P65): OPEN
  "Collecting", SENT "Pending", CONFIRMED "Delivery scheduled" (P51), CLOSED
  "Received". On the ship's pages a Pending order also says "Waiting for the office to schedule the delivery" (the
  order page under the badge, All orders and the Master's home on its row, the Orders in progress card).
- Another ship's line or order is a 404; crew members get 403, and so does the Chief cook for every POST (the
  cook reads the pages, P55). New column `ship_order.office_note`
  (nullable, Hibernate adds it); new notification type ORDER (`RequestSchemaMigration` re-creates the check).
- **The Master's home** (`/ship` as MASTER, `masterhome/`): P59 adds **Deliveries to receive** to its cards,
  after Vessel order: the number of the vessel's CONFIRMED orders, highlighted ("to do") above zero like Requests to
  approve; it opens Vessel order, or the receipt itself when there is exactly one. (The Vessel order card still
  counts the lines of the OPEN order.)
- Tests: `shiporder/ShipOrderFlowTest` (orders in progress, their order, the Master's and the cook's actions, the
  overdue delivery, All orders, the counter), `masterhome/MasterHomeFlowTest` (Deliveries to receive).
- To try it: as `master.demo@example.com`, approve the waiting requests, open **Ship order**, change a quantity
  and send it with port "Rotterdam"; `office@example.com` sees the notification. The sent order is under Orders in
  progress; once the office schedules its delivery, the card has Confirm receipt and the counter shows it.
