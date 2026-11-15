# 37. Photographing the delivery invoice

Photographing the delivery invoice and reading it (P62). When a delivery arrives, the vessel's Chief cook (or the
Master, when no count is waiting for him) photographs the supplier's invoice first; the server reads it into
invoice lines with its own OCR (P67; Mistral OCR when chosen) and checks them. The checks are stored for later (P64 shows them to the office); the lines guide the cook's count ([38](38-invoice-guided-count.md)). The
invoice only helps: **Count without the invoice** is always there, and a reading that fails never loses a photo.
- **Where**: while the order is CONFIRMED and no invoice page is photographed yet, **Count delivery** (the cook,
  36) and **Confirm receipt** (the Master, with no count of the cook to approve) on Orders in progress and on the
  order page open `/ship/orders/{id}/invoice` instead of the count or the receipt
  (`ShipOrderPageService.invoiceFirstForCook`/`invoiceFirstForMaster`); afterwards they go straight on, and the
  count and the receipt say "Invoice: 2 pages photographed" with a link back.
- **The photo page** (`shiporder/InvoiceController`, `shiporder/invoice.html`, `js/invoice.js`, phone first):
  "Photograph every page. Hold it flat, in good light." **Take a photo** (`accept="image/*"
  capture="environment"`: the back camera) and **Choose a file** (photos or a PDF). One page at a time: a thumbnail
  per page with **Retake** (a new file, same page number) and **Remove** (the pages after it move up), **Add page**,
  at most 6 (`OrderReceiptService.MAX_INVOICE_PAGES`). JPEG, PNG, WebP, HEIC and PDF, told by their content, not
  their name (`invoicePageContentType`), up to 10 MB. The script makes a photo at most 2000 px (JPEG 0.8, turned
  as the camera held it) and shows "Sending page 2: 40%"; a dropped connection says so, keeps the pages sent and
  offers **Try again**. Without JavaScript the same form posts and comes back to the page.
- **The pages are receipt files** (`order_receipt_file.invoice_page`, `invoice_page_status`,
  `invoice_page_total`, `invoice_page_check`; nullable, Hibernate adds them): the Master sees them in his receipt's
  files ("Page 1 · Photograph the invoice"), the office with the received order ([24](24-office-orders.md)). They are not among the
  cook's photos of the delivery note on the count.
- **Done: start counting** (`POST .../invoice/read`) reads the pages not yet read, one OCR call per page
  (`core/order/InvoiceReadingService`, in the background after the commit; `app.invoice.read-in-background` is
  false in the tests), while `/invoice/reading` shows "Reading page 1 of 2" and reloads itself (a meta refresh, or
  `/invoice/reading/status` with the script). Then on to the count (cook) or the receipt (Master), unless something
  needs the ship: a page hard to read ("This page is hard to read: retake it flat, in good light, with the whole
  page in the picture." with **Retake** and **Continue anyway**), a page that could not be read (**Read again**,
  that page only), nothing read at all ("The invoice could not be read now. Count without it, the photos are
  kept.") or another order's number on the invoice ("This invoice says order SR-000052. Check it is the right
  invoice."). A reading the server never finished (a restart) is given up after `app.invoice.reading-stalled-after`
  (15 min): its pages "timed out", to read again. While reading, the pages cannot change (409).
- **Reading a page on the server** (P67, `core/ai/LocalInvoiceReader`, the default: `app.invoice.reader` `local`):
  PaddleOCR PP-OCRv5 in ONNX Runtime on the CPU, no key, **nothing leaves the server**. `core/ai/OnnxOcr` finds the
  boxes of text (the detection model, the page at most `app.ocr.max-side` 1600 px) and reads each (the Greek and
  English recognition model `el_PP-OCRv5_mobile_rec`, CTC). `core/ai/OcrLayout` writes each word in one alphabet
  (the model may read "BOX" with a Greek Β), measures the photo's tilt and turns the boxes back, groups them into
  rows, finds the header row, takes the rows under it with three boxes or more (and description-only rows that go
  on) as the table, groups the body's boxes into columns where they overlap and puts each column under the header
  it overlaps most (one column per header first: right-aligned quantities may stick out under the price). The text
  and the table, as markdown, go through the same `InvoicePageParser` as Mistral's answer. A line's confidence is
  the lowest of its boxes with a digit (the numbers the checks compare). JPEG and PNG as they are, a PDF at 200 dpi
  (at most 10 pages); WebP and HEIC cannot be decoded here and fail (PROVIDER_ERROR: **Read again** after a retake
  as JPEG). A page with no table whose boxes stand taller than wide is turned 90° one way, then the other. One page
  at a time on the whole server, about 4 s a page on 2 to 4 cores, some 300 MB of memory while the models are
  loaded (on the first page). There is no chat-model fallback: a page without a table is "hard to read". Logs say
  how a page ended (lines, time), never its text. The models are files in `app.ocr.model-dir` (`OCR_MODEL_DIR`,
  default `models/ocr`, gitignored): see [configuration](../configuration.md#local-ocr-models).
- **Reading a page with Mistral** (`core/ai/MistralInvoiceReader`, `app.invoice.reader` `mistral`): `POST /v1/ocr` with
  `model` `app.mistral.ocr-model` (`mistral-ocr-4-1`, OCR 4.1, July 2026, pinned: `mistral-ocr-latest` and
  `mistral-ocr-4` are aliases that move), `document`
  `{"type": "image_url", "image_url": "data:image/jpeg;base64,..."}` (a PDF: `document_url`), `table_format`
  `markdown`, `include_blocks` true and `confidence_scores_granularity` `block`. The answer's `pages[].markdown`
  has the tables as placeholders (`[tbl-0.md](tbl-0.md)`) whose content is in `pages[].tables[]`; a block's
  `confidence_scores` give each table its confidence. `core/ai/InvoicePageParser` finds the table of lines by its
  header (English or Greek: "Α/Α", "Περιγραφή", "Ποσότητα", "Τιμή", "Αξία", ...), reads 1,234.56 and 1.234,56 by
  what the page uses (`InvoiceNumbers`; "4S" is not a number, never 4), a row with only a description goes on with
  the line before, and the supplier, invoice number, date, order reference, "Page 1 of 2", page total, net, VAT and
  total from the text. A page with no table whose text blocks stand sideways is turned 90° and read once more; a
  page with text but no table goes once to the chat model (`app.mistral.model`, JSON mode, the OCR text only) to
  put it in lines. **Only the page image goes to Mistral**: no crew names, no order data. Logs say how a call
  ended, never the key, the image or the text. Timeouts and errors are per page.
- **The checks** (`core/order/InvoiceChecks`, pure): per line quantity × unit price = amount (within 0.01, or 0.5%
  for weighed units: kg, g, lt, ml, κιλά, ...); one value unreadable or with its decimal separator lost is
  recovered from the other two and marked "corrected" (the value read is kept), but a quantity is never taken from
  a value that may have lost its separator, nor from too few lines to tell, nor when it would not be a clean
  quantity: then "quantity not read", never an invented one. A line read with less confidence than
  `app.invoice.min-confidence` (0.85), or whose values do not fit, is "check". Per page: the lines against the page
  total and missing line numbers (also across pages: "Page 2: lines 3 and 4 are missing"), which also make the page
  hard to read. The invoice: the lines against the net amount, and net + VAT = total.
- **Data model**: `core/order/DeliveryInvoice` (`delivery_invoice`, one per order: status READING, READ,
  PARTLY_READ, FAILED; supplier, invoice number and date, order reference, net, VAT, total, started/read at, read
  by, the check notes, the reference warning and whether it names another order) and `DeliveryInvoiceLine`
  (`delivery_invoice_line`: page, line number, description, unit, quantity, unit price, amount, the values as read,
  the raw row, corrected, needs check, quantity not read, confidence). New tables, Hibernate creates them. A
  retaken or removed page drops its lines; the pages after a removed one move up with theirs.
- **Audit**: "Invoice photographed" (`Order #12, 2 pages`, on Done) and "Invoice read" (`Order #12, 40 lines read,
  2 flagged`, with ", 1 page not read" when one failed), English and Greek labels.
- **Access**: `SecurityConfig` gives `/ship/orders/*/invoice/**` to MASTER and CHIEF_COOK; the vessel's enabled
  Master or Chief cook only, another ship's order is a 404. Not before the delivery is scheduled, not for the cook
  once his count is with the Master, never once the order is CLOSED. Ship screens English only.
- **Configuration**: `INVOICE_READER` `local` (default) with `OCR_MODEL_DIR`, `OCR_MAX_SIDE`, `OCR_THREADS`; or
  `mistral` with the same `MISTRAL_API_KEY` as 34, `MISTRAL_OCR_MODEL` and `MISTRAL_OCR_TIMEOUT` (60s per page).
  Without the models (local) or the key (mistral), and not fake, nothing is read: Done keeps the photos and goes on
  to the count.
  `MISTRAL_FAKE=true` (dev, tests) uses `core/ai/FakeInvoiceReader`, which "reads" the synthetic sample invoice in
  `samples/invoice/`: A-4471 of the fictional Kestrel Ship Supply S.A., two pages, 40 lines, Greek number format and
  bilingual headers, as PNG scans and phone-style JPEG photos, with `invoice-A-4471.expected.json` (every line,
  the page totals, net, VAT and total). Every photo uploaded is read as the sample's page of the same number; a
  third page has no table (hard to read). The tests use its hooks (a failed page, a line lost, a low confidence, a
  value changed, another order's reference).
- Tests: `core/ai/InvoicePageParserTest` (the sample read back line for line, English and Greek, both number
  formats, a table given apart, totals, unreadable cells, labels with Greek and Latin letters mixed),
  `core/ai/OcrLayoutTest` (rows, a tilted page, columns, a description going on, one alphabet per word, a line's
  confidence), `core/ai/OnnxOcrTest` (the characters, CTC decoding, the regions), `core/ai/LocalInvoiceReaderTest`
  (the real models on the sample: scans, photos, sideways, a PDF; skipped without the models),
  `core/ai/MistralInvoiceReaderTest` (the request, the
  answer, sideways pages, the chat fallback, errors, nothing secret logged), `core/order/InvoiceChecksTest` (every
  rule), `shiporder/InvoiceFlowTest` (both roles, Retake and Remove, the limits and types, hard, failed and
  unread pages, another order, what is stored, access).
- To try it: as in [36](36-delivery-count.md), schedule a delivery. As `cook.demo@example.com` press **Count delivery**: the photo page.
  Upload `samples/invoice/invoice-A-4471-p1-photo.jpg` and `-p2-photo.jpg`, press **Done: start counting**: the
  count opens with "Invoice: 2 pages photographed". Upload a third page to see "Page 3 is hard to read".
