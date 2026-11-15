# 28. Suppliers and requests for quotes

Suppliers and requests for quotes (P44, office users only). From a vessel order the ship has sent, the office asks
several suppliers for a price by email, types in their answers, compares them per item and awards one, all in the
app. `core/supplier/` (`Supplier`, `SupplierService`, `SupplierChannel`, `EmailChannel`, `SupplierEmails`),
`core/rfq/` (`Rfq`, `RfqLine`, `RfqQuote`, `RfqQuoteLine`, `RfqService`, `RfqComparison`, `Units`, `Money`,
`RfqWorkbook`, `RfqAwardListener`), `supplier/` and `rfq/` (controllers and forms); templates in
`templates/supplier/`, `templates/rfq/` and `templates/email/supplier.html`; styles in `app.css` section
"Suppliers and quotes (P44)".
- **Suppliers** in the sidebar (Operations, after Orders; a truck; `/office/suppliers`): name, contact email,
  ports served (free text, "Piraeus, Perama"), currency (three letters, EUR by default), website, active. The
  list (P19): search name, email or port, filter Active / Inactive, sort by name or currency (P58: the Currency
  column is centred, `.code-cell`), 10 per page; with
  none yet, the empty state with **Add supplier** (P25). **Add supplier** and **Edit** share one form; a name
  already used (any case) is refused. An inactive supplier stays on old requests but cannot be asked again.
  Audit: "Supplier added", "Supplier changed". The dev data adds **VANOS (Piraeus)** (`https://www.vanos.gr/`,
  EUR, Piraeus, Perama, Elefsina) with a test address, `quotes.vanos@example.com`, so a dev request never reaches
  the real company, and **Harbour Provisions (Test)** in USD; once, while there is no supplier.
- **How a request is sent** is one seam: `SupplierChannel` (`sendRfq(rfq, supplier)`, `cancel(rfq)`, and
  `notSelected(rfq, supplier)` for the award), one bean per `ChannelType`, chosen by the supplier's `channel`
  field. Only EMAIL exists (`EmailChannel`); a marketplace or an API would be another implementation.
- **Request quotes** (on `/office/orders/{id}` while the order is SENT, i.e. waiting for the office): the form
  starts with the order's requested port and needed-by date, a reply-by date 3 days ahead, and the active
  suppliers as checkboxes, those whose ports served mention the port first ("Serves Piraeus", ignoring case and
  accents); at least one, the reply-by date on or before the delivery date (dates P39-plausible). **Review**
  (P10) shows the port, the dates, the suppliers with their addresses, what the email contains and the items;
  nothing is sent until **Send to 3 suppliers**. Sending copies the order's lines (item, name, unit, category,
  quantity) into the request, so later changes to items do not change it. An order can have many requests over
  time but only one open: **Request quotes** then becomes **Open RFQ #3**. The order's page lists its requests
  (number, status, quotes received).
- **The email** (English, `SupplierEmails`; HTML in the P36 layout with a plain-text part, `MailService.send` with
  Reply-To and attachments): subject "RFQ #3: request for quotation, MV Demo at Piraeus"; "Dear VANOS (Piraeus),"
  then the vessel and IMO number, the port, the delivery date and the reply-by date; a table of the items by
  category (item, quantity, unit); what to give per item (unit price, quantity offered, pack size and unit, a
  note) and overall (delivery cost, delivery date, validity), in the supplier's currency unless they say
  otherwise; the footer names the office user, whose address is the Reply-To, and the reference. Attached:
  `rfq-3.xls` (P40 format: sheet "RFQ", `rfq,vessel,imo_number,port,delivery_date,reply_by,category,item_name,unit,quantity,unit_price,quantity_offered,pack_size,pack_unit,note`,
  the last five empty for the supplier to fill in). Each supplier gets its own email; none sees the others. An
  email that cannot be sent marks that supplier **Failed** with the reason and **Send again**.
- **Entering a quote** (`/office/orders/{id}/rfqs/{rfqId}/quotes/{quoteId}`): per line the unit price (of one
  pack), the quantity offered (packs), the pack size and unit (empty: one of the item's unit; "5" "lt" for a 5 L
  can of an item in lt; the unit must convert to the item's: volume, mass or count, `Units`) and a note; a line
  with no price is not offered. Then currency, delivery cost, delivery date (required) and valid until, and the
  supplier's own PDF (optional, up to 10 MB, kept with the request like a library file). **Declined** is one click
  on the request page, with an optional reason; entering a quote later undoes it. Both can be corrected while
  the request is open.
- **Statuses per supplier**: Sent, Quoted, Declined, No reply (sent, reply-by date passed), Failed.
- **The comparison** (on the request page, once two suppliers have quoted; sealed: suppliers never see each
  other's prices): one table per currency with at least two quotes (no conversion: other currencies are listed
  apart, "Other currencies, not compared"); items as rows by category (P32), suppliers as columns; each cell the
  price per item unit (a 5 lt can at EUR 12.00 is EUR 2.40 / lt), the packs, the line total, "Less than asked"
  when it covers less, the note, and "lowest" for the row's lowest price; a line not offered says **Not offered**,
  never zero. Then items, delivery cost, grand total ("Incomplete: 2 items offered" when lines are missing),
  delivery date and validity per supplier. The **cheapest complete quote** (every line offered, lowest grand total)
  has a tick with "Cheapest complete quote" and a green tint. On phones the table scrolls sideways inside its card
  with the item column fixed. Amounts are `BigDecimal` (prices to 4 decimals, totals to 2), shown "EUR 1,240.50"
  (Greek "EUR 1.240,50", `@money`).
- **Award** (one quote for the whole request, no split by line; confirmed first): the request is Awarded, every
  other supplier still asked gets a short "RFQ #3: not selected" email, and the vessel order shows "Awarded to
  VANOS (Piraeus) · EUR 1,240.50" with its **Confirm delivery** form starting with the request's port and the
  quote's delivery date ([24](24-office-orders.md)). The purchase order is not part of P44: `RfqAwardListener` beans are called after an
  award and none exists yet. **Cancel request** withdraws an open request ("request withdrawn" email to those
  still asked); a new one can then be sent.
- **Overview** ([4](04-overview.md)): "2 quotes ready to compare" (an open request with at least two quotes, **Compare quotes**) and
  "Reply-by date passed" (fewer than two quotes and the date passed, **Open request**), category **Quotes**. The
  sidebar's Orders counter is unchanged.
- **Audit log**: "RFQ sent" (lines, port, dates, suppliers; sent again), "Quote entered" (supplier, total, lines
  offered, the PDF), "Quote declined" (with the reason), "RFQ awarded" (supplier and total), "RFQ cancelled".
- Tables `supplier`, `rfq`, `rfq_line`, `rfq_quote`, `rfq_quote_line` (Hibernate creates them on start).
- Tests: `supplier/SupplierFlowTest`, `rfq/RfqFlowTest`, `core/rfq/UnitsTest`; the Overview rows in
  `RfqFlowTest`, the query count in `DashboardFlowTest`.
- To try it: as the Master send an order with port "Piraeus" ([23](23-ship-order.md)); as `office@example.com` open it, **Request
  quotes**, choose both suppliers and send (the emails are in Mailpit); enter a quote for each, compare and
  award.
