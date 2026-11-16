# 33. A received delivery goes into the store

A received delivery goes into the store (P56). When the Master confirms receipt of an order ([24](24-office-orders.md), P51), what arrived
of the provisions is added to the Chief cook's store ([30](30-stores.md)), in the same transaction as the receipt.
- `StockService.receive(order, line, master)`: the item's stock goes up by the line's received quantity (a
  `StockLevel` row is created for an item without one) and a RECEIPT movement is saved: before, after, by the
  Master, at the order's received at (the time the Master entered, not the time of confirming), linked to the
  order. The item's row is locked first, as for a count. An item deactivated since the order still gets its stock:
  what arrived is on board. Refuses (IllegalArgumentException) a line of another order, a removed line, an item
  that is not PROVISIONS, a received quantity not above zero or an order without received at; anyone but the
  enabled Master of the order's vessel gets an AccessDeniedException.
- `OrderReceiptService.confirmReceipt` calls it for every line not removed by the office, whose item is
  PROVISIONS and whose received quantity is above zero. Personal items never touch the store; "Not delivered"
  (0) adds nothing and leaves no movement.
- **No correction needed.** A confirmed receipt never changes: `confirmReceipt` only works on a CONFIRMED order
  and makes it CLOSED, the office changes lines only while SENT (P50), and nothing else writes received
  quantities or received at. So each line reaches the store exactly once.
- **Notification**: when at least one item was stocked, the vessel's enabled Chief cook gets "Order 12 received:
  stock updated for 5 items" (`order.notification.stocked`, type ORDER), linking to `/ship/stores`. No stocked
  item, no notification. The office's notification is unchanged.
- **The store's pages**: on an item's history (and on `/ship/stores/history`) a Delivery received row's "Order
  #12" is a link to `/ship/orders/{id}`, which the cook reads ([32](32-cook-reads-order.md)).
- Tests: `shiporder/OrderReceiptFlowTest` (a provisions line and a personal-item line received 8 and 2: the
  provisions stock 8 higher with one RECEIPT movement linked to the order, no stock for the personal item, the
  cook's notification, the link on the item page).
- To try it: as in [32](32-cook-reads-order.md), send the order and schedule its delivery; as `master.demo@example.com` confirm its receipt.
  Then as `cook.demo@example.com` the notification opens Inventory, and the item's history shows the delivery with
  its order.
