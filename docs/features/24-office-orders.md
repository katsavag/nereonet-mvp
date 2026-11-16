# 24. Ship orders in the office

Ship orders in the office (P36, office users only). `core/order/ShipOrderService` (`findSent`, `scheduleDelivery`,
`export`, `countNotReceived`) and `officeorder/` (`OfficeOrderController`, `ConfirmOrderForm`).
- **Orders** in the sidebar (`/office/orders`), with the number of open orders: Pending plus Delivery scheduled (P51;
  "2 open"). The list: the
  orders the ships have sent (SENT, CONFIRMED, CLOSED; never OPEN ones, which are still being put together on
  board), newest sent first, with ship, sent by, sent at, requested port, needed by, status (Pending, Delivery
  scheduled, Received) and invoice. Filters: ship (`?shipId=`), status (`?status=sent`
  Pending, `delivery_scheduled` Delivery scheduled (P65; the older `delivery_pending` and `confirmed` still work),
  `closed` Received) and invoice. Search the number (`#12`),
  the ship name or IMO number; 10 per page, 20, 50 or 100 on request (paged in the database). A row opens its order.
- **`/office/orders/{id}`**: status, sent by and when, requested port, needed by, the Master's note, the
  scheduled delivery once set (port, delivery date, by whom) and when it was last exported; then its lines by category
  (Provisions, then Personal items): item, unit, quantity. While the order is pending the office can change them
  (P50, below); once the delivery is scheduled they are read-only. Once received (P51) each line shows Ordered and
  Received.
  An OPEN order is a 404.
- **Schedule delivery** (P51; "Confirm delivery" before; while SENT): **Delivery port** (required, up to 100
  characters, starts as the requested port) and **Delivery date** (required). `POST /office/orders/{id}/confirm`
  (`ShipOrderService.scheduleDelivery`) sets the port, delivery date, scheduled by and CONFIRMED ("Delivery
  scheduled. The master and the chief cook were notified."); "Delivery scheduled by office" goes in the audit log
  and the ship's enabled Master and Chief cook, both linking to `/ship/orders/{id}` (read only for the cook, P55;
  no link for the cook before), get "Delivery scheduled: Antwerp, 20 Nov 2026". While CONFIRMED the same form is **Change delivery**
  ("Delivery changed by office", "Delivery changed: ..."), as often as needed. The office cannot close an order:
  only the Master's receipt does (P51, below); once CLOSED the form is gone and a POST changes nothing ("This order
  was received on board."). The order's row is locked while scheduling.
  P65: the page has one primary button, its next step: **Schedule delivery** while Pending (**Done editing**
  secondary, **Add to order** an outline primary); while Delivery scheduled, **Save delivery changes** ("Save
  change" before) is an outline button until a field of the form changes (`js/forms.js`,
  `data-primary-on-change`; primary too when the form came back with errors), so the page has none until then;
  once Received, **Shortage handled** (or, with a read invoice, **Mark as matched**, P64).
- **Export**: on the order page, and on the list for the checked rows (the header checkbox checks the page).
  `POST /office/orders/export` (`ids`) answers with the Excel file `ship-orders-<date>.xls` (the format is in 12), sheet "Orders", one row per order line, the orders newest sent first and each order's lines by category
  and item name: `imo_number,ship_name,order_id,status,sent_at,requested_port,needed_by,confirmed_port,delivery_date,category,item_name,unit,quantity,sent_quantity,office_change,office_note,received_quantity,received_at`
  (`status` `pending`, `delivery_scheduled` or `received` ; P51: `received_quantity`
  a number and `received_at` a date and time cell in UTC, both empty until the order is received; `sent_at` a date and time cell in UTC, `needed_by` and `delivery_date` date cells, `quantity` a number,
  category `Provisions` or `Personal items`; P50: `quantity` is what to deliver, 0 for a line the office removed,
  `sent_quantity` what the ship sent (empty for a line the office added), `office_change` `changed`, `removed`,
  `added` or empty, `office_note` the office's note). It sets
  `exportedAt` on the exported orders and records "Orders exported" (counts and ids). An order can be exported
  again. Nothing checked: back to the list with "Select at least one order to export.". The page stays as it is
  (reload to see the Exported mark): the forms are `data-file`, so `js/save.js` never makes their buttons busy
  and their checkboxes are not unsaved changes.
- P64: received with a read invoice, the card "Order, delivery and invoice", the list's Invoice column and filter
  (`?invoice=differ`) and five more export columns ([39](39-invoice-comparison.md)).
- **Request quotes** (P44, see [28](28-suppliers-and-quotes.md)): while the order is SENT, ask suppliers for prices; once one is awarded the
  page shows "Awarded to VANOS (Piraeus) · EUR 1,240.50" and Schedule delivery starts with its port and date.
- **Changing the items of a pending order** (P50; `core/order/OfficeOrderEditService`, `OfficeOrderEditJob`).
  While the order is SENT ("Pending") the office corrects it before arranging delivery, on its page, with the
  Master's controls (P35): each line's **Quantity** (same rule, `ShipOrderService.isValidQuantity`) with an optional
  **Note** (up to 200 characters, kept in the field) and **Save** (`POST /office/orders/{id}/lines/{lineId}/quantity`);
  **Remove** (`POST .../lines/{lineId}/remove`), which asks first (P10, danger) with an optional note in the dialog
  (`data-confirm-note` on `layout/parts :: confirmForm`; without JavaScript the plain confirmation page,
  `GET .../remove`, has the note field: `ConfirmPages.note`); and **Add an item** (`POST .../lines/add`: `itemId`,
  `quantity`, `note`). P59 (a list box of every item before): two panels, stacked on phones and side by side from
  768 px. **Not on the order**: the vessel's active items not on the order, grouped by category, each a radio button
  (`name="itemId"`) with its name and unit as the label, in a list that scrolls within 320 px; a removed line's item
  is there too, and adding it brings the line back with the quantity given. **On the order**: its lines (name,
  quantity, unit), read only, for reference; they are changed in the line cards above. Then **Quantity**, **Note**
  and **Add to order**. `js/item-search.js` adds the search field above the radios, "Adding: Basmati rice (kg)"
  above the quantity (its description) and the unit in its label, "Quantity (kg)"; without it the radios post as
  they are. With nothing chosen the button stays enabled and the server answers "Choose the item to add.". An item
  already on the order is no longer offered, but the server still adds it to its line (a stale page). With every
  active item on the order: "Every active item of the vessel is on the order already." and no fields.
- **Layout** (P59): Add an item and Schedule delivery are as wide as the line cards above them (`.form-box-full`,
  `app.css`; every other form keeps `.form-box`'s 720 px) and from 992 px lay out their fields in one row with the
  button at its end (`.form-row-wide`: Schedule delivery's port and date side by side, then the button). Every change is a plain POST form (CSRF,
  sent once by `js/save.js`), saves at once and comes back with a message; a wrong quantity, a note over 200
  characters or no item chosen changes nothing. An item of another vessel, an inactive one or another order's line is
  a 404; the crew get 403.
  - Each line keeps what the ship sent (`ShipOrderLine.originalQuantity`, set when the Master sends the order; for
    orders sent before P50, by the office's first change). A changed line says "Changed by office: 50 kg → 40 kg ·
    “Out of season, reduced”"; a removed line stays, struck through, "Removed by office" (not delivered, no
    controls, not in requests for quotes or the Overview's line count); an added line "Added by office". The same on
    the Master's `/ship/orders/{id}` (`shiporder/parts :: officeChange`) and in the export.
  - Each change is audited with the line, before, after and note: "Order line changed by office" (`Order #12,
    Basmati rice: 50 kg -> 40 kg, note: Out of season, reduced`), "Order line removed by office" (`6 pcs -> removed`),
    "Order line added by office" (`added 12 lt`; added to a line: `50 kg -> 60 kg (added 10 kg)`).
  - The ship is told once per editing session, not per click: until then a note above the lines says the master and
    the chief cook haven't been told yet, with **Done editing** (`POST /office/orders/{id}/edits/done`). They are
    told when it is pressed, 10 minutes after the last change (`OfficeOrderEditJob` checks every minute;
    `app.orders.office-edit-quiet`, default `PT10M`), or when the delivery is scheduled (before "Delivery scheduled"). The
    Master (linking to the order) and the Chief cook get "The office changed order 12: 2 lines changed, 1 removed, 1
    added"; a line changed back to what the ship sent is not counted, and nothing is sent when nothing is left.
    New columns `ship_order.office_edited_at`, `ship_order_line.original_quantity`, `removed`, `added_by_office`,
    `change_note`, `office_change_pending` (Hibernate adds them; the flags default to false).
  - Once the delivery is scheduled the lines are read-only again; a change sent anyway (another tab) changes nothing
    and says so.
- **The receipt on board** (P51; `core/order/OrderReceiptService`, `OrderReceiptFile`, `shiporder/OrderReceiptController`,
  `ReceiptForm`). Only the vessel can say the items arrived.
  - While the order is CONFIRMED the Master's `/ship/orders/{id}` has **Confirm receipt** with the hint "When the items
    arrive, confirm receipt and attach the delivery receipt.". `/ship/orders/{id}/receipt` lists each line (not the removed ones)
    with Ordered and **Received** (prefilled with the ordered quantity; 0 or more, at most 3 decimals), **Not
    delivered** (sets it to 0; without JavaScript the box alone counts) and an optional **Note** (up to 200
    characters); then **Delivery receipt**: files (required, at least one; PDF, JPEG or PNG, at most 10 MB each, up
    to 10, stored like library files by `LibraryFileStorage`, table `order_receipt_file`) and **Date and time received
    (UTC)** (now to start with; not before the order was sent, not more than 5 minutes ahead).
  - **Review** (`POST /ship/orders/{id}/receipt`) saves the chosen files on the order as staged (only the Master sees
    them; ✕ removes one, `removeFileIds`), checks the rest and shows the review page (P10 style): each line with
    "Short by 5 kg" or "Not delivered", the files (open them, `GET .../receipt/files/{fileId}`), the time; nothing
    is received yet. **Back to edit** (`POST .../receipt/edit`) keeps everything; **Confirm receipt**
    (`POST .../receipt/confirm`) locks the order, saves received quantities, notes, received at/by and the number
    of short lines, and makes it CLOSED; "Receipt confirmed on board" goes in the audit log and every enabled office
    user gets "ABC IRIS confirmed receipt of order 12 (2 lines short)" (linking to the order). The provisions received go into the Chief cook's store in the same transaction ([33](33-delivery-into-store.md)). A second confirm
    (another tab, a double click) changes nothing and says so. A GET of `/edit` or `/confirm` goes back to the form.
  - `js/receipt.js` (P15): shows the chosen files, makes photos smaller (at most 2000 px, JPEG) before they are
    sent, uploads each one by XHR to `POST .../receipt/files` with a progress bar (204, or 422/409 with the reason),
    then sends the form; the buttons are busy once, and the form is a draft (P15) except the files. Without
    JavaScript the files go with the Review POST; a request over the multipart limit comes back to the form with
    "Larger than 10 MB. Choose a smaller file." (`GET .../receipt/files?fileTooLarge`).
  - The Master then sees "Received <date> by <Master>", the files and per line Received, with the shortages.
  - P61, P63: the Chief cook may count the delivery first; while his count waits the Master approves it on its
    own page instead of this form (36, 38).
  - P62: Confirm receipt opens the photo page of the invoice first, unless a count waits ([37](37-invoice-photo.md)).
  - The office's order page: a **Receipt on board** card, "Received 14 Nov 2026 · 19:45 UTC by Demo Master", "2
    lines arrived short", the files with **Open** (inline, P22) and **Download**
    (`GET /office/orders/{id}/receipt/{fileId}`, 404 until the order is received), and per line Ordered and
    Received with "Short by 5 kg" or "Not delivered" and the Master's note.
  - P64: an order received with a read invoice is compared and matched instead ([39](39-invoice-comparison.md)); the rest of this point is for
    the orders without one.
  - A shortage stays in the Overview's Needs attention ("Order #12 · received short", Orders group,
    `AttentionType.ORDER_SHORTAGE`) until an office user presses **Shortage handled** with a note (required, up to
    1000 characters; `POST /office/orders/{id}/shortage-handled`): "Shortage handled by office" in the audit log,
    and the card shows who, when and the note.
  - New columns `ship_order.received_at`, `received_by_id`, `receipt_confirmed_at`, `short_lines` (default 0),
    `shortage_handled_at`, `shortage_handled_by_id`, `shortage_note`, `ship_order_line.received_quantity`,
    `receipt_note` (Hibernate adds them). Crew texts are English only; the office's in English and Greek.
- Tests: `officeorder/OfficeOrderFlowTest`, `officeorder/OfficeOrderEditFlowTest` (P50; P59 the two panels),
  `shiporder/OrderReceiptFlowTest` (P51); the Overview's rows and counts in `DashboardFlowTest`.
- To try it: send the order as in [23](23-ship-order.md), then as `office@example.com` open **Orders**, change a quantity, remove a line
  and add an item, press **Done editing** (the Master sees the notification and the changes), schedule the delivery
  (the Master and the Chief cook see the notification) and export it. Then as `master.demo@example.com` open the
  order, **Confirm receipt** with one line short and a photo of the delivery note, review and confirm; the office
  sees the receipt, the shortage on the Overview, and marks it handled.
