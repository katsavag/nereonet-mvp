# 30. Inventory

Inventory (P52, counting P53; the Chief cook only): the stock of the vessel's provisions. `core/stock/` (`StockLevel`,
`StockMovement`, `StockMovementType`, `StockStatus`, `StockService` and the two repositories), `stores/`
(`StoresController`, `StoresService`, `StoresHistoryFilter`), `templates/stores/`, `js/stores-changed.js`; styles in
`app.css`, "Stores (P52)" and "Counting the inventory". English only, as every crew screen. `/ship/stores` and `/ship/stores/**` are for CHIEF_COOK only (`SecurityConfig`, next to
the Master's rules): the Master and crew members get 403. The pages always show the signed-in cook's own vessel.
- **The stock** (`core/stock`, shared: the request page, the receipt on board and the office use it later). Only
  PROVISIONS items have stock; personal items never appear in the store.
  - `StockLevel`: one row per item at most (`uk_stock_level_item`), the quantity in the item's unit (zero or more,
    at most 3 decimals and 9 whole digits, the rule of `ShipOrderService.isValidQuantity`; `StockService.isValidQuantity`)
    and `updatedAt`. An item without a row has quantity 0.
  - `StockMovement`, the store's history: item, type `COUNT` or `RECEIPT`, quantity before and after, by (null
    once that user is deleted), at, an optional note (200 characters) and, for a receipt, the ship order. Never
    changed or deleted: `@Immutable`, no setters (`StockMovement.of`), and the repository can only add and read.
  - `StockService`: `levels(ship)` every active PROVISIONS item of the vessel, by name, with its quantity and status;
    `status(quantity)` **Out of stock** at 0, **Low** above 0 and below `app.stock.low-threshold`
    (`STOCK_LOW_THRESHOLD`, default 10, one threshold for every item), otherwise **OK**; `history(item)` its
    movements, newest first; `lastCount(ship)` the latest COUNT movement; `count(cook, item, quantity, note)` and
    `history(ship, type, from, to, page)` (P53, below).
  A delivery adds to the stock when the Master confirms its receipt (P56, `receive`, see [33](33-delivery-into-store.md)).
- **Where**: **Inventory** in the sidebar under Supplies (P65), with the number of provisions Low or Out of stock as a
  pill (one count query, `StockLevelRepository.countBelowOnShipOf`); on phones it is the Chief cook's second tab
  (P65; a row of More before) with the same number as a badge.
  The cook's Home ([18](18-crew-home.md)) has an **Inventory** card above To read with both counts ("2 low · 1 out of stock"), tinted
  while either is above zero. The Master's home gets its Inventory card later.
- **The store** (`/ship/stores`, titled "Inventory"): a summary first, **Low** and **Out of stock** (each a link to
  that filter, tinted when above zero) and **Last count** (a link to History; the date of the latest COUNT movement, or "Not counted
  yet") and **Requests waiting**: the cook's own requests still waiting for the Master, a link to
  Requests filtered to Waiting ([31](31-requests.md)); **Next delivery** ("Antwerp, 20 Nov 2026", a link to that order,
  read only for the cook, 32; "None scheduled" without one). The five are one strip of chips (`.stat-strip`), each one line, the label and its value in bold: one row on a
  desktop, wrapping to two on a phone.
  Then the search (part of the
  name, ignoring case, `?q=`) and the status filter (All, Low, Out of stock; `?status=low|out`), in the shared
  filter bar with its chips (P19), handled on the server. The items follow in one card per storage place,
  alphabetical, the items without a place last under **Other**; each row is one link to the item with its
  quantity and unit and its status badge (OK, Low, Out of stock; words, the colour only repeats them). The cards are two per row from 768 px. The header has one button, **Count**. No paging:
  the whole store. Nothing found: "No results for these filters" with **Clear all**; no provisions at all: "No
  provisions yet".
- **An item** (`/ship/stores/items/{id}`): its quantity and status, unit and place ("Not set"), then its history,
  newest first: date and time, type (Count, Delivery received with the order number, a link to the order, [33](33-delivery-into-store.md)), before, after, who
  ("Deleted user" once the account is gone) and the note. An item of another vessel, or one that is not
  PROVISIONS, is a 404.
- **Counting by hand** (P53). `StockService.count(cook, itemId, counted, note)` sets the item's stock to the
  counted quantity at once and saves a COUNT movement: before is what the store expected, after what was counted,
  by the cook, now. A count equal to what was expected is saved too (the count page does not send such a row). Audit log "Inventory
  counted on board" with "Flour: 10 -> 7 kg". No approval, no count session. Checks: zero or more with
  the store's precision (`isValidQuantity`), a note of at most 200 characters (IllegalArgumentException); the item
  an active PROVISIONS item of the cook's vessel (otherwise nothing changes); the user an
  enabled Chief cook with a vessel (AccessDeniedException). The item's row is locked first (PESSIMISTIC_WRITE, as
  the order code locks the vessel's row), so two tabs counting one item are saved one after the other. The lock is
  on the item rather than its `StockLevel` because an item never counted has no `StockLevel` row yet.
  - **Count** (a button on Inventory, only with provisions) opens `/ship/stores/count`: every active provisions item
    by place, as on Inventory, each row with the quantity the inventory has now in a number input
    (`inputmode="decimal"`) and its unit. One form for the page (`CountForm`), `POST /ship/stores/count`
    (`quantities[id]`, "2,5" accepted, and the hidden `shown[id]`, the quantity the page showed), sent by one
    **Save count** in a bar stuck to the bottom of the screen. Nothing is saved before it. In one transaction
    (`StoresService.saveCount`): a row whose number is not the one the page showed is saved as a count, a row left
    as it was writes nothing. Then back to Inventory with "Count saved: 3 items changed." (or "Nothing was changed,
    so nothing was saved."). A wrong value (blank, not a number, below zero, too many decimals, above the maximum)
    saves nothing at all: the page comes back with every typed value and the message under each wrong row. With
    JavaScript (`js/stores-changed.js`) a changed row is marked (a blue left edge and "changed") and the bar says
    "3 items changed". The form carries `data-draft`, so `js/save.js` keeps what is typed in the browser if the
    connection drops and removes it once the count is saved.
  - **Add an item that is not on the list** (P75), at the end of the same form: Name, Unit, Quantity counted and
    Where it is kept (optional, the vessel's places as suggestions). As soon as one field is filled, name, unit and
    quantity are required (the lengths of `Item`, the unit chosen from the list of `ItemUnits`, the quantity by the rule
    of that unit). The same Save creates
    the item (`core/item/NewItemService.createProvisions`: PROVISIONS, active, the cook's own vessel, audit "Item
    created" by the cook) and counts it. A name the vessel already has (ignoring case): "This item is already on the
    list." and nothing is saved. One new item per save. So the Chief cook adds items to the vessel's list without
    the Master or the office.
  - **History** (the Last count chip on Inventory) opens `/ship/stores/history`: every movement of the vessel, newest first, 50 a
    page (`StockService.HISTORY_PAGE_SIZE`, Newer and Older): date and time, item (a link), type, before, after,
    difference ("+20", "-3", "0"), who. Filter by type (All, Count, Delivery received) and by UTC days From and To
    (both included; not a date: its error under the field, nothing filtered). P58: the dates use the shared
    date range (`layout/parts :: dateRange(bean, presets, actions)`, `core/list/DatePreset`, CSS "Date range
    (P58)"): quick choices **Last 30 days**, **Last 60 days**, **Last 90 days** as segmented links (From = today
    minus N, To = today, UTC; they keep the type and work without JavaScript), the one matching the filter marked
    with `aria-current` and a tick; From and To side by side at every width (also 360 px), **Filter** and
    **Clear** on the same row from 768 px and under them on phones. Typing a date removes the mark (`js/forms.js`,
    `[data-date-range]`). One query with the item and who
    (`StockMovementRepository.findOnShip`). Plain on purpose: it will be redesigned.
  - An item's history has a **Difference** column: counted minus expected for a count ("-3"), a dash for a
    delivery. The summary's **Last count** is the date of the latest count.
- Tables `stock_level` and `stock_movement` (Hibernate creates them on start).
- Dev data: while the demo vessel has no stock at all, its provisions get some, counted by Demo Cook two days ago
  (one COUNT movement each): Basmati rice 40 kg, Flour 25 kg, Eggs 180, Tomatoes 12 kg, Chicken breast 30 kg (OK),
  Olive oil 4 lt and Coffee 6.5 kg (Low), Sugar and Butter (Out of stock), and Salt 15 kg without a place (Other).
- Tests: `stores/StoresFlowTest` (P53 too: counting, the history); the dev data in `approval/ApprovalFlowTest`.
- To try it: as `cook.demo@example.com` / `crew123`, **Inventory** shows 4 in the sidebar; open it, choose **Low**,
  then open Olive oil for its history. As `master.demo@example.com`, `/ship/stores` is a 403. P53: press **Count**,
  change Basmati rice to 7 and press **Save count**: Inventory shows it as Low; type -2 for Eggs for the error.
  **History** then shows the count, 40 → 7, -33.
