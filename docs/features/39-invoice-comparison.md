# 39. The office compares order, delivery and invoice

The office compares order, delivery and invoice (P64, office users only). Once the Master confirms receipt of an
order with a read invoice (37, 38), the office sees per line what was ordered, received on board and invoiced, with
the invoice's photos beside what was read from them; when all agree one click closes it, otherwise the office deals
with each difference. `core/order/InvoiceComparison` (pure), `InvoiceComparisonService`, `InvoiceDecision`,
`InvoiceMatchStatus`; `officeorder/OfficeInvoiceController`; templates `officeorder/compare.html` (the card),
`invoice.html`, `invoice-match.html`; styles in `app.css` section "Order, delivery and invoice (P64)".
- **When**: closing the receipt (24, 38) makes an order with a read invoice (READ or PARTLY_READ, with lines)
  `TO_MATCH` (`ship_order.invoice_status`; null: none, the P51 shortage flow applies unchanged). The Master's P51
  receipt without a count matches the invoice's lines first (`InvoiceMatchService.ensureMatched`), so it compares
  too. `invoice_diff_lines` keeps the number of lines that differ for the Overview and the list.
- **The card "Order, delivery and invoice"** on `/office/orders/{id}` (`#compare`), under the order details:
  "All 40 lines agree" / "3 of 40 lines differ"; the invoice (supplier, number, date, net, total); by category
  ("Provisions: 24 lines, all agree", the lines not ordered apart); the ordered value at the quoted prices (when
  the order was awarded through a request for quotes, [28](28-suppliers-and-quotes.md)), the invoiced total and the value of the differences;
  **View invoice**. Then **To review** (only when there is something), the lines that differ, the lines that agree
  folded ("37 lines agree: show") and **Mark as matched**.
- **A row** per order line not removed, and per invoice line linked to no order line. What differs, in words
  (`InvoiceComparison.DiffKind`): "Received 10 kg less than invoiced: a credit to claim", "Received ... more than
  invoiced", "Invoiced 5 kg more (less) than ordered", "Not ordered" (an invoice line the cook marked so, never
  chose, or a line added on receipt), "Not on the invoice" (with "Received ... less than ordered"), "The invoiced
  quantity was not read", and "Invoiced 0.05 more (less) per kg than quoted" when the invoice's price per item unit
  is more than 1% from the awarded quote's. Each row shows Ordered, Received, Invoiced in the item's unit with the
  invoice's own in small text ("12 bags × 10 kg"), the invoice's unit price ("per bag") and amount, the quoted
  price per item unit, the cook's note (and the Master's when different) and the money at stake: what was invoiced
  and did not arrive at the invoice's price (otherwise invoiced against ordered; a line not ordered at its amount),
  plus the price above the quote on the quantity invoiced. Amounts in the quote's currency, without a code when
  there is no award.
- **To review** (what the ship was deliberately not asked about, P62, P63): the invoice's checks that failed
  ("Page 1: the lines add up to ..."), a different order reference, lines read with a flag (corrected, hard to read,
  quantity not read), matches to review ("FETA 2KG → Feta cheese") and conversions to review ("BASMATI RICE 10KG,
  BAG → × 10 kg"). **Correct** confirms one (a match or a conversion is then learned for the supplier,
  `InvoiceMatchService.learn`); **Change**: another product (`/office/orders/{id}/invoice/lines/{lineId}/match`: the
  order's lines, the vessel's other items, which become a line added on receipt with nothing received, or Not
  ordered), another factor (inline, more than 0, at most 100000), or a line corrected on View invoice. Changing a
  match or a conversion corrects the comparison and the learned name only, never the store: the message and the
  point say "The inventory was updated with the old match. Ask the vessel to count these items." and the vessel's
  Chief cook gets "Order 12: the office corrected "BASMATI RICE 10KG" (Basmati rice). The inventory was updated before
  the correction: please count these items." (linking to Inventory; English only). A decided point stays listed with
  who and when.
- **Accept** a difference with a required note (up to 500 characters, "Credit note requested from the supplier").
  A decision holds only while the difference stays what it was (`InvoiceDecision.detail`): a later correction that
  changes it needs a new one.
- **Mark as matched** (`POST /office/orders/{id}/invoice/matched`) once every difference is accepted and every point
  reviewed ("2 points are left to decide" and the button disabled until then): `MATCHED`, who and when; a P51
  shortage of the receipt is marked handled with it. This replaces **Shortage handled** for orders with a read
  invoice (the card does not show it). Once matched nothing changes ("This comparison can no longer be changed.").
- **View invoice** (`/office/orders/{id}/invoice?page=1`): the photographed page on the left (opens full size in a
  new tab to zoom; a PDF opens), "Page 1 of 2" with Previous and Next, and on the right the lines read from that page
  (number, description, quantity, unit, unit price, amount, the product it was matched to, the flags in words) and
  the page's checks (and the invoice's). **Correct this line** (while not matched): quantity, unit price, amount,
  description, an empty field keeps its value (a quantity 0 or more with at most 3 decimals, a unit price with 4, an
  amount with 2, up to 1000000; "1,5" and "1.5" both read). The values as read stay (`readQuantity`, ...,
  `descriptionRead`), with who changed it and when ("As read: ...", "Changed by"). On phones the photo is above the
  lines; side by side from 992 px.
- **Overview** ([4](04-overview.md)): "Order #12 · invoice to match", kind "Invoice to match" in the Orders category
  (`AttentionType.ORDER_INVOICE`), "3 lines differ" or "All lines agree: confirm", with **Compare**; most
  differences first. It replaces the P51 "received short" row for those orders.
- **Orders list** ([24](24-office-orders.md)): an **Invoice** column ("Matched", "3 differ", "All agree: confirm", "None") and the filter
  **Invoice: Lines differ** (`?invoice=differ`).
- **Export** ([24](24-office-orders.md)): five more columns, `invoiced_quantity` (the item's unit), `invoice_unit`, `invoice_unit_price`,
  `invoice_amount` (the invoice's) and `difference` (received minus invoiced; nothing invoiced counts 0), empty
  without a read invoice; and one row per invoice line not ordered (`office_change` `not_ordered`, quantity 0, the
  invoice's unit).
- **Audit**: "Invoice difference accepted", "Invoice point marked correct", "Invoice line product changed by office",
  "Invoice pack conversion changed by office", "Invoice line corrected by office" (before and after), "Invoice
  matched" (lines, the differences accepted and their notes).
- **Data model** (Hibernate adds them): `ship_order.invoice_status`, `invoice_diff_lines` (default 0),
  `invoice_matched_at`, `invoice_matched_by_id`; `delivery_invoice_line.description_read`, `office_changed_by_id`,
  `office_changed_at`; new table `invoice_decision` (order, item key, kind ACCEPTED / CONFIRMED / CHANGED, detail,
  note, who, when; one per key).
- Office screens in English and Greek; the check notes are stored in English ([37](37-invoice-photo.md)). Never on a ship page: the ship
  still sees no prices.
- Tests: `officeorder/InvoiceComparisonFlowTest` (the count and approval, the card, the Overview, the list and its
  filter, Accept, Correct and learning, a changed conversion and match with the cook told, a line corrected,
  Mark as matched, the export, access; all agree in one click).
- To try it: as in [38](38-invoice-guided-count.md), have the cook count the sample invoice with a row different and the Master approve; as
  `office@example.com` open the Overview's "Invoice to match", **Compare**, review the points, accept the
  differences and **Mark as matched**.
