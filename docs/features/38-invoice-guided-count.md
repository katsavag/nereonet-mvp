# 38. The invoice guides the count

The invoice guides the cook's count, and the Master approves the differences (P63). The invoice's lines read in
P62 are matched to the order's lines; the cook counts each row against what the invoice says, in the invoice's
unit, changing only the numbers that differ, and saves the list once; the Master sees only the differences and approves in one step. Without an
invoice (none photographed, or not read) the order is the reference. Ship screens English only; prices, the
invoice's checks and the uncertain matches are kept for the office (P64).
- **Matching** (`core/order/InvoiceMatcher`, pure; `InvoiceMatchService.ensureMatched`, when the count opens):
  names folded (case, accents, punctuation, final sigma), stop words and plurals dropped, pack sizes ("10KG",
  "6X1,5LT", "33cl") kept apart. Per invoice line, in order: a name learned for this supplier (LEARNED); the
  order's lines (MATCHED when clearly ahead, CHECK when likely); the vessel's other items, a row of their own;
  otherwise the cook chooses (PICK). Two lines matched to the same order line: the second is CHECK. The supplier
  is found by its name (company forms dropped); an unknown one is learned under its name as read.
- **Units** (`InvoiceMatcher.conversion`): the same unit counts in the item's; another unit of the same measure
  (g for kg) or a pack whose size the description gives ("BASMATI RICE 10KG", BAG, for kg: 10) counts in the
  invoice's unit ("Basmati rice, 10 kg bag", "Invoice 12 bags") and stores the item's (110 kg). Anything that
  cannot be worked out counts in the item's unit. A factor worked out (not learned) is marked to review.
- **Data model** (nullable columns, Hibernate adds them): `delivery_invoice_line` match, order line, item, pack
  factor and label, conversion to review, counted, note, approved; `ship_order_line` approved, invoiced quantity,
  invoice line, match to review, conversion to review, added on receipt; new `supplier_item_name` (supplier or
  supplier name, description key, item, pack factor, who, when).
- **The count** (`shiporder/DeliveryCountController`, `core/order/CountSheet`, `shiporder/count.html`,
  `js/count.js`, phone first): one list and one Save (P82). Under the title one line of help. One row per line, by
  category: the name, under it the reference ("Ordered 10 bags · Invoice 12 bags", "Ordered 30 pcs", "Invoice: not
  read", "Not on the invoice") and **Add a note**, the input **Arrived** with the unit, holding the saved count or,
  for a row never saved, the reference (the invoice's quantity, or the quantity ordered without an invoice; empty
  when there is none), and **Did not arrive** (a checkbox that looks like a quiet button: the row is saved as 0,
  whatever the input says; typing 0 ticks it); under the input the difference in words ("2 more than the invoice",
  "3 fewer than the invoice", "Did not arrive"). A row that differs has a left border in the warning colour, a row
  without a number in the danger colour. The bar at the bottom of the screen says what Save will record ("37 as the
  invoice · 2 different · 1 without a number", `js/count.js`) and has **Retake invoice photo** (**Photograph the
  invoice** when there is no page) and **Save count** (`POST .../count`, `DeliveryCountService.saveRows`, P60's
  receipt rule in the row's unit): every row with a number is counted, a row left empty is not. A refused value:
  nothing is saved, every typed value stays, the message is at its row. Rows without a number: the rest is saved and
  the page comes back at the first of them ("3 items still need a number. If something did not arrive, tick Did not
  arrive."). Everything counted: on to the page that sends the count. What is typed and not saved is kept as a
  draft in the browser (`data-draft`, `js/save.js`). **Read
  wrong?** at the end of the reference (a `details`, no JavaScript needed; "Enter it" for a quantity not read; only
  a row with one invoice line) opens "The paper invoice says" with **Correct invoice** (a form of its own,
  `POST .../count/invoice`, `DeliveryCountService.correctInvoice`; the numbers typed in the other rows come back
  from the draft): the invoice line's quantity is replaced and what the photo read stays
  (`DeliveryInvoiceLine.correctQuantity`, shared with the office's correction; `quantity_corrected_at`), recorded as
  "Invoice quantity corrected on board" with the old and the new value. The reference then reads "Invoice 21 bottles
  (corrected, photo read 12)", also on the Master's approval ("Invoice numbers corrected by the Chief
  cook") and beside the office's flags; a row already counted keeps its count, compared with the new number. Lines
  on the invoice not found: "On the invoice, not found on the order", **Choose the product**
  (`.../count/choose/{lineId}`: the order's lines not matched, the vessel's other items, a search, or **Not
  ordered**); a choice is learned for the supplier ("Invoice line matched by hand" in the audit log). The page that
  sends the count (`.../count/send`): the summary (two tiles, the differences with **Change**, back to that row, an
  optional note) and **Send to Master**. Sending needs every row counted and every invoice line chosen (P77:
  `DeliveryCountService.send` answers NOT_FINISHED, "Count every item before sending. 3 still to do."; opening the
  page before that leads back to the list with the same words). Counts already saved are kept when the invoice is
  read again. Count by
  voice and the cook's photos of the delivery note are not on this page (the invoice photos take their place;
  voice stays on the stores count, [34](34-voice-count.md)).
- **The Master** (`/ship/orders/{id}/receipt` while a count waits, `shiporder/receipt-approve.html`): "Approve the
  delivery", who counted, the two tiles, the differences with **Change** (that row's input and **Did not arrive**,
  in the row's unit, saved with **Confirm**: "Changed from 23"),
  **Show all**, the invoice photos, **Send back to cook** (as 36, his changes dropped) and **Approve**
  (`POST .../receipt/approve`): received at the time the cook sent the count unless **Change date**; at least one
  file (the invoice photos count). It closes the order as 24 with the approved numbers, puts the provisions in the
  store ([33](33-delivery-into-store.md)), notifies the office and the cook. P51's form posts redirect here while a count waits.
- **Kept for the office**: per order line the invoiced quantity and invoice line, and the marks match to review
  (CHECK) and conversion to review; the vessel's items not on the order become lines "added on receipt" (ordered
  0); the CHECK lines the cook counted are learned at approval.
- Tests: `core/order/InvoiceMatcherTest`, `shiporder/DeliveryCountFlowTest` (without an invoice end to end; with
  one: rows, units, choosing, learning, approval and what is kept; a quantity not read; access).
- To try it: as in [37](37-invoice-photo.md), photograph the sample invoice and press **Done: start counting**; change a few numbers, choose
  a product for a line not found, **Save count** and **Send to Master**; as `master.demo@example.com` open the
  notification, change a row and **Approve**.
