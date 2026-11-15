# 32. The Chief cook reads the vessel order

The Chief cook reads the vessel order (P55). Read only: the cook never creates, changes, sends or receives an
order; that stays the Master's (23, 24). The cook also counts a scheduled delivery for the Master to approve ([36](36-delivery-count.md)).
- **Access** (`SecurityConfig`, by HTTP method and path): `GET /ship/order`, `GET /ship/orders` and
  `GET /ship/orders/{id}` (`/ship/orders/*`, one segment) for MASTER and CHIEF_COOK; everything else under
  `/ship/order/**` and `/ship/orders/**` (every POST, the receipt pages and files under `/ship/orders/{id}/receipt`)
  stays MASTER only. Crew members get 403 on all of them.
- **The services, not the pages, enforce it.** `ShipOrderService.changeQuantity` (now before it checks the
  quantity), `removeLine` and `send`, and `OrderReceiptService.stageFile`, `removeFiles` and `confirmReceipt`
  refuse anyone but the ship's enabled Master (`AccessDeniedException`); `addApprovedRequest` refuses a request not
  decided by an enabled Master of its ship. `ShipOrderPageService` reads (`open`, `past`, `detail`) for the
  enabled Master or Chief cook, and gives the receipt pages (`order`) and the services' user (`master`) only to the
  enabled Master. `scheduleDelivery`, `export`, `markShortageHandled` and the office's line changes stay the
  office's.
- **The pages**: the same templates. `ShipOrderController` sets one model flag, `canManage`
  (`ShipOrderPageService.canManage`: the enabled Master), and the templates use it instead of role checks. For the
  cook: the OPEN order ("Collecting") with its lines by category and who asked for what, no quantity inputs, no
  Remove, no Send to office; the past orders with their status (`order.status.*`), requested port, needed by and,
  once set, delivery port and date; a sent order's page without Confirm receipt; a received order with what arrived
  per line (Received, "Short by 3 kg"), without the delivery receipt's files. Each page says "Only the Master can
  change or send the order." instead of the controls; the hints read "Orders the Master sent to the office…".
- **Navigation**: **Ship order** in the cook's sidebar and More, without the Master's counter (`ordersToAct` stays null for the cook). P59: the cook sees the Orders in progress on Vessel order ([23](23-ship-order.md))
  with **Open order** only, never Confirm receipt.
- **Notifications**: "Delivery scheduled" / "Delivery changed" ([24](24-office-orders.md)) and "The office changed order 12…" (P50) link
  the cook to `/ship/orders/{id}` too.
- **Stores**: the summary's **Next delivery** ([30](30-stores.md)), `ShipOrderService.nextDelivery`: the vessel's CONFIRMED order
  with the earliest delivery date.
- Tests: `shiporder/ShipOrderFlowTest` (the cook's pages, the 403 on send with the order unchanged, the services'
  checks), `stores/StoresFlowTest` (Next delivery), the notification links in `officeorder/OfficeOrderFlowTest` and
  `OfficeOrderEditFlowTest`.
- To try it: as `master.demo@example.com` approve the requests and send the order; as `office@example.com` schedule
  its delivery. Then as `cook.demo@example.com` / `crew123`: **Ship order** in the sidebar, the notification opens
  the order (no Confirm receipt), and Stores shows **Next delivery**.
