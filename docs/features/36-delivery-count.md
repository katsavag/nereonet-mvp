# 36. The Chief cook counts the delivery

The Chief cook counts the delivery; the Master approves it (P61; the count page, the Master's approval and voice
were redone in P63, see [38](38-invoice-guided-count.md): what follows on them is P61's and kept for the history). While an order is CONFIRMED (delivery
scheduled) the vessel's enabled Chief cook counts what arrived, and the Master approves the count by confirming
receipt ([24](24-office-orders.md), P51), which puts the provisions into the store as before ([33](33-delivery-into-store.md), P56). The count only helps: the Master
can still confirm receipt without one (no cook on board, or he is busy).
- **Data model** (columns, not a new entity; all nullable, Hibernate adds them): `ship_order.delivery_count`
  (`core/order/DeliveryCountStatus`: NONE (null), DRAFT, SENT_TO_MASTER, RETURNED, APPROVED), `counted_by_id`,
  `count_updated_at`, `count_sent_at`, `count_note` (to the Master, up to 500), `count_return_note` (the Master's,
  up to 500), `count_returned_at`; `ship_order_line.counted_quantity` (numeric 12,3) and `count_note` (up to 200).
  The order keeps status CONFIRMED throughout. `core/order/DeliveryCountService` (`saveDraft`, `send`, `sendBack`)
  locks the order's row on every change, like the receipt.
- **The count** (`shiporder/DeliveryCountController`, `shiporder/count.html`, phone first):
  **Count delivery** on the cook's In progress card ([23](23-ship-order.md), P59) and on the order page. `/ship/orders/{id}/count`
  has one block per line the office did not remove, by category: the item, "Ordered 25 kg", **Received** (empty
  until counted; the receipt's rule of P60, 0 = nothing arrived) with **All arrived** (the quantity ordered;
  `js/receipt.js` fills it in, without JavaScript it saves the draft with that line filled), an optional note per
  line (up to 200), one optional **Note for the Master** (up to 500) and **Photos of the delivery note** (the
  receipt's staging: same types and limits, `MAX_FILES`; `OrderReceiptService.stageFile` and `removeFiles` take the
  cook since P61, his own files only, while his count is not with the Master; they are the Master's staged receipt
  files, marked "from the Chief cook"). **Save draft** (`POST .../count`) keeps the count on the order (blank
  lines allowed), **Send to Master** (`POST .../count/send`) needs every line. Nothing goes into the store here.
  Once sent the page is read only ("Sent to the Master on ..., waiting for approval") and the card says
  **View count**.
- **Count by voice**: P57's card (`stores/parts :: voiceCard`, now shared with the Count page) posts to
  `POST .../count/voice`; `VoiceCountService.listen` runs the same two steps (speech to text, then `CountExtractor`,
  unchanged) over the order's lines' items. The answer is JSON with what was heard per line (checked by the
  receipt's rule; anything else goes to Not matched); `js/stores-voice.js` hands it to `js/receipt.js`, which fills
  the lines in place ("Heard: “five kilos of tomatoes”", Low confidence in words) and lists the phrases not matched.
  Nothing is saved until Save draft or Send.
- **Sent to the Master**: "Delivery counted" in the audit log (`Order #12, 3 lines counted, 1 not as ordered`); the
  ship's enabled Masters get "Order 12: delivery counted by Demo Cook, waiting for your approval", linking to the
  receipt page. The In progress cards and the Master's home **Deliveries to receive** card say "Counted by the Chief
  cook, to approve".
- **The Master's receipt** (`ReceiptForm.of`): prefilled with the cook's quantities (0 is Not delivered) and notes,
  a card on top with who counted, when and his note; each line says "Counted: 48 kg" and, once the Master changes
  it, "Changed from 48" (`js/receipt.js` live, the server on Back to edit). The review has a Counted column and
  **Differences**: against the order ("Tomatoes: ordered 25 kg, received 21 kg") and against the cook's count
  ("counted 22 kg, you entered 21 kg"). Confirm receipt then works as before (CLOSED, the office notified, the
  store), and approves the count (APPROVED): the audit entry ends ", counted by Demo Cook", each RECEIPT movement's
  note is "Counted by Demo Cook", and the cook gets the usual "Order 12 received: stock updated for 5 items". A
  draft the cook never sent is left as it was and the receipt says nothing about it.
- **Send back to cook** (`POST /ship/orders/{id}/receipt/return`, `returnNote` required, up to 500): while the count
  is SENT_TO_MASTER. RETURNED, the cook's numbers kept; "Delivery count sent back" in the audit log; the cook who
  counted gets "Order 12: the Master sent the delivery count back: Recount the frozen meat", linking to the count,
  which opens with the Master's note on top. He sends it again.
- **The office**: once received, the order page's details say "Counted by Demo Cook on 14 Nov 2026" (English and
  Greek, as the two audit labels).
- **Access**: `SecurityConfig` gives `/ship/orders/*/count/**` to CHIEF_COOK only (before the Master's rule); the
  services refuse anyone but the vessel's enabled Chief cook (count) or Master (send back, confirm). Another ship's
  order is a 404. Counting is refused while the order is not CONFIRMED and once it is CLOSED; the office may still
  change the delivery's port and date while CONFIRMED, which keeps the count. Ship screens English only.
- Tests: `shiporder/DeliveryCountFlowTest` (draft, All arrived, photos, send, the cards and notifications, send
  back, the prefilled receipt and its differences, approval and "counted by", confirming without a count, access,
  voice with both fakes).
- To try it: as in [32](32-cook-reads-order.md), send an order and schedule its delivery. As `cook.demo@example.com` press **Count delivery**
  on Vessel order, count, add a photo and **Send to Master**. As `master.demo@example.com` open the notification,
  **Send back to cook** with a note; as the cook send it again; as the Master change one line, review and confirm.
