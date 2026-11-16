# 38. The invoice guides the count

The invoice guides the cook's count, and the Master approves the differences (P63). The invoice's lines read in
P62 are matched to the order's lines; the cook counts each row against what the invoice says, in the invoice's
unit, changing only the numbers that differ, and saves the list once; the Master sees only the differences and approves in one step. Without an
invoice (none photographed, or not read) the order is the reference. Ship screens English only; prices, the
invoice's checks and the uncertain matches are kept for the office (P64).
- **Matching** (`core/order/InvoiceMatcher`, pure; `InvoiceMatchService.ensureMatched`, at the end of the invoice's
  reading and when the count opens):
  names folded (case, accents, punctuation, final sigma), stop words and plurals dropped, pack sizes ("10KG",
  "6X1,5LT", "33cl") kept apart. Per invoice line, in order: a name learned for this supplier (LEARNED); the
  order's lines (MATCHED when clearly ahead, CHECK when likely); the vessel's other items, a row of their own; the
  vessel's item that has the name the new item would get (P92, ignoring case: MATCHED, with the pack conversion);
  otherwise a new item's row (PICK). Two lines matched to the same order line: the second is CHECK. The supplier
  is found by its name (company forms dropped); an unknown one is learned under its name as read.
- **Claude matches what the words could not** (P85; `core/ai/InvoiceLineMatcher`: `ClaudeInvoiceLineMatcher`, and
  `FakeInvoiceLineMatcher`, which matches nothing, with `CLAUDE_FAKE`): once per reading, at the end of the background
  reading (`InvoiceReadingService.matchWithClaude`), never when a page opens, the lines that ended as PICK go to
  Claude in one call (Messages API, `app.claude.model`, low effort, structured output). **Sent**: per line a number,
  the product with the pack size after it ("Black pepper, 500 g", P92) and the unit as the invoice has it; per candidate a number, the item's name and unit (the order's
  lines first, then the vessel's other active items); nothing else. The answer (per line a candidate or 0, and how
  sure from 0 to 1) is checked in Java (`InvoiceMatchAnswer`: a line or a candidate not sent is ignored). At least
  `app.invoice.ai-match-min` (0.8) sure: the line is linked as AI, its unit worked out as for a MATCHED line; else it
  stays PICK. `delivery_invoice_line.ai_match_tried` keeps a line from being asked twice (a page read again makes new
  lines). No key, a timeout, a refusal or any error: one line in the log, the lines stay PICK. Never learned by
  itself: only a person's choice is (the office's **Correct** or **Change**). The cook's row is the
  item's, its reference "Invoice 20 kg (MINCED BEEF FROZEN) · Matched by Claude" with **Not this item** (P92: one
  button, `POST .../count/lines/{lineId}/unmatch`, `DeliveryCountService.unmatch`: the match is undone, the line is
  a new item's row and the page comes back at it; Claude is not asked about it again; "Invoice line match undone on
  board" in the audit log); the Master's rows show the same words; for the office it is a
  match to review ([39](39-invoice-comparison.md)). Audit: "Invoice lines matched by Claude" (`Order #12, 3 invoice
  lines matched by Claude`), one per reading that linked something.
- **Units** (`InvoiceMatcher.conversion`): the same unit counts in the item's; another unit of the same measure
  (g for kg) or a pack whose size the description gives ("BASMATI RICE 10KG", BAG, for kg: 10) counts in the
  invoice's unit ("Basmati rice, 10 kg bag", "Invoice 12 bags") and stores the item's (110 kg). Anything that
  cannot be worked out counts in the item's unit. A factor worked out (not learned) is marked to review.
- **Data model** (nullable columns, Hibernate adds them): `delivery_invoice_line` match, order line, item, pack
  factor and label, conversion to review, counted, note, approved, `removed_on_board` and `added_on_board` (both
  default false, P83); `ship_order_line` approved, invoiced quantity,
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
  without a number in the danger colour. Each category is a card that can be closed (P87, `layout/parts ::
  foldCard`, a native `<details>`; nothing is remembered, every page load has every card open): its header says
  "Provisions · 20 items · 2 different · 1 without a number", or "all counted" with a tick once closed. A card with a
  row without a number or with a refused one cannot be closed (no chevron; the server renders it so, `js/count.js`
  follows the typing and opens a card whose number is cleared), and what is typed in a closed card is still sent.
  The bar at the bottom of the screen says what Save will record, over every row, open or closed ("37 as the
  invoice · 2 different · 1 without a number", `js/count.js`; with a row without a number the sentence is a link
  that opens its card and goes to it) and has **Retake invoice photo** (**Photograph the
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
  cook") and beside the office's flags; a row already counted keeps its count, compared with the new number.
  **Not on the invoice: remove** (P83; a small quiet button under the number in "Read wrong?", one per invoice line
  of the row, the one action at the end of a new item's reference, and at the end of each "Not ordered" line; `POST .../count/lines/{lineId}/remove`,
  `DeliveryCountService.removeLine`): the line the photo read wrong is kept as read
  (`DeliveryInvoiceLine.removedOnBoard`) and left out of the count, its totals, the differences and the office's
  comparison; an ordered row then reads "Not on the invoice" and is counted against the order. No confirmation: at
  the end of the list the muted **Removed invoice lines (2)** lists each one as the photo read it with **Put back**
  (`.../restore`). **Add a line from the paper invoice** (P83; before the bar, with a read invoice;
  `POST .../count/lines`, `DeliveryCountService.addLine`): a select of the vessel's active items (the order's
  first, then the others), the quantity (the item's unit and rule) and **Add line** create an invoice line marked
  `addedOnBoard` (page 0, so a retaken photo keeps it; matched to the item and to its order line when ordered;
  nothing in the read fields): the row says "Invoice 5 kg (added by hand)" and behaves as any other; it can be
  removed again. An item that has an invoice line already is refused at the select ("This item is already on the
  invoice. Use Read wrong? to change its number."); an item not on the vessel is first added in the inventory.
  Removing, putting back and adding are the vessel's Chief cook's, while the count is not with the Master, each one
  audit entry ("Invoice line removed on board", "put back", "added"); the Master's approval lists them ("Removed by
  the Chief cook: OLIVE OIL 5LT, 12 BOTTLE", "Added by hand by the Chief cook: Basmati rice, 5 kg"). Lines
  on the invoice matched to nothing (P84): a last group "On the invoice, not on the order", each an ordinary row with
  the badge **New item**: the invoice's product in sentence case, without the pack size (P92,
  `InvoiceMatcher.productName`: "BLACK PEPPER 500G" is "Black pepper", "TEA BAGS 100S" stays "Tea bags 100s"), the
  invoice's unit in the app's spelling ("KG" is "kg"; a unit not in the dropdown stays as the invoice has it) with
  that unit's rule, "Invoice 20 kg · not on the order", **Arrived** prefilled with the invoice's quantity, counted
  with **Save count** like any row (no note). When the invoice counts packs (PCS, CAN, BOX...) and the description
  gives the weight or the volume of one (`InvoiceMatcher.newItemPack`), the new item is in kg or lt and the row is
  packed as a matched line's: "Invoice 2 pcs of 500 g · not on the order", counted in pcs, 1 kg into the inventory.
  One action ends its reference: **Not on the invoice: remove**. The cook does not match invoice lines by hand (P92:
  the choose page is gone); that is the office's ([39](39-invoice-comparison.md)). The lines a cook marked **Not
  ordered** before are still listed under that heading; nobody on board marks a new one. The page that
  sends the count (`.../count/send`): the summary (two tiles, the differences with **Change**, back to that row, an
  optional note) and **Send to Master**. Sending needs every row counted (P77:
  `DeliveryCountService.send` answers NOT_FINISHED, "Count every item before sending. 3 still to do."; opening the
  page before that leads back to the list with the same words). Counts already saved are kept when the invoice is
  read again. Count by
  voice and the cook's photos of the delivery note are not on this page (the invoice photos take their place;
  voice stays on the stores count, [34](34-voice-count.md)).
- **The Master** (`/ship/orders/{id}/receipt` while a count waits, `shiporder/receipt-approve.html`): "Approve the
  delivery", who counted, the two tiles, the differences with **Change** (that row's input and **Did not arrive**,
  in the row's unit, saved with **Confirm**: "Changed from 23"),
  **New items in this delivery** (the rows not on the order that arrived, "Minced beef frozen 20 kg arrived", by the
  pack "Black pepper 2 pcs of 500 g · adds 1 kg"; "Approving adds these to the vessel's items and to the inventory.
  If one of these is an item you already have under another name, the office can match it."), **Show all**, the invoice photos, **Send back to cook** (as 36, his changes dropped) and **Approve**
  (`POST .../receipt/approve`): received at the time the cook sent the count unless **Change date**; at least one
  file (the invoice photos count). It closes the order as 24 with the approved numbers, puts the provisions in the
  store ([33](33-delivery-into-store.md)), notifies the office and the cook. P51's form posts redirect here while a count waits.
- **Kept for the office**: per order line the invoiced quantity and invoice line, and the marks match to review
  (CHECK, AI) and conversion to review; the vessel's items not on the order become lines "added on receipt" (ordered
  0); so does a new item's row that arrived (above 0): approval first makes the item on the vessel
  (`RequestService.itemFor`, as the approval of a request: the name as shown, the unit, PROVISIONS, active, no
  storage place, ITEM_CREATED; a name the vessel has, ignoring case, is that item), links the invoice line to it with
  the row's pack conversion (marked to review when there is one) and stocks what arrived; one that did not arrive (0) makes nothing and stays a line not on the order; the CHECK lines the cook counted are learned at approval.
- Tests: `core/order/InvoiceMatcherTest`, `shiporder/DeliveryCountFlowTest` (without an invoice end to end; with
  one: rows, units, new items, learning, approval and what is kept; a quantity not read; access).
- To try it: as in [37](37-invoice-photo.md), photograph the sample invoice and press **Done: start counting**; change a few numbers, count
  the lines not on the order as new items, **Save count** and **Send to Master**; as `master.demo@example.com` open the
  notification, change a row and **Approve**.
