# 35. Sensible limits on every input

Sensible limits on every input (P60). One rule for every quantity a person types, by the kind of the item's unit
(`core/form/QuantityRules`, a bean named `quantityRules` that templates call too), so a slip such as 1000000 kg
or "12,5 pcs" never reaches an order or the store.
- **Kinds**: `kg` and `lt` take up to 3 decimals; `g`, `ml` and every countable unit (`pcs`, `box`, `can`, `tin`,
  `dozen`, `carton`, ...) take whole numbers. Units are matched ignoring case, spaces, a trailing dot or "s" and
  common spellings (`kilo`, `L`, `ltr`, `litre`, `gr`, `κιλό`, `λίτρο`, ...); an unknown or missing unit counts as
  countable ("units"). A comma is read as the decimal point.
- **Maxima** (`application.yml`, `app.quantity.max.*`): 2000 kg, 2000 lt, 50000 g, 50000 ml, 5000 for countable
  units, for one line of a request, an office order line and a receipt line. A store count holds the whole store:
  `app.quantity.store-factor` ([10](10-audit-log.md)) times the line maximum. An item's own **Maximum per request** (11, the item
  form, optional, in the item's unit) replaces the maximum in requests, office order lines and receipt lines (not
  in the store count or a quote). A value already saved may stay above the maximum (an edit that leaves it unchanged
  is accepted); a new or changed value may not.
- **Where 0 is allowed**: the receipt (nothing of that line arrived) and the store count (empty). Everywhere else a
  quantity is above zero.
- **Server**: every quantity is checked by the rule before anything is saved (`ShipRequestService`,
  `ShipOrderController.changeQuantity`, `OfficeOrderController` (change and **Add an item**),
  `OrderReceiptController`, `StoresController` and `VoiceCountController`, `RfqController` for the quantity offered
  and the pack size); the item form uses `@PlausibleQuantity`. The messages say what is wrong and the limit:
  "Use whole numbers for pcs.", "Use at most 3 decimals for kg.", "Up to 2000 kg per line. For more, split it or
  ask the office." (office: "...set the item's maximum per request"; receipt: "Check the quantity received"; count:
  "Check the count"). What was typed is shown again with the error under its field.
- **Browser** (`js/quantity.js`, every `input[data-quantity]` from the `layout/parts :: quantityInput` fragment):
  text inputs with `inputmode` numeric or decimal (no number spinners, which round and accept "e"), letters and
  extra separators filtered as they are typed, a comma turned into a dot, the error shown live in the slot under
  the field (`quantityHint`, empty while the value is fine: no limit is shown before a value breaks it; in the
  field's `aria-describedby`). On the Chief cook's new item the rule follows the unit typed beside it; on the office's Add
  an item it follows the item picked. Without the script the fields are plain text inputs and the server checks
  them.
- **Other inputs** (the P60 audit): text fields keep a `maxlength` matching their column and a `@Size` on the form
  (added where missing: the supplier email counter, the SIRE observation number, the forgot-password email); dates
  have `min`/`max` where a range is known (the receipt date not before the order was sent, the date range filters);
  the quote's unit price is above 0 with at most 2 decimals and, like the delivery cost, at most 1,000,000; the
  confirm password is at most 72 characters.
- Data model: `item.max_per_request` (numeric 12,3, nullable), created by Hibernate.
- Configuration: `QUANTITY_MAX_KG`, `QUANTITY_MAX_LT`, `QUANTITY_MAX_G`, `QUANTITY_MAX_ML`,
  `QUANTITY_MAX_COUNTABLE`, `QUANTITY_STORE_FACTOR` (see Configuration outside dev).
- Tests: `core/form/QuantityRulesTest` (kinds, decimals, maxima, zero, the item's maximum, unchanged values),
  and the flow tests of each screen: `ShipRequestFlowTest`, `ShipOrderFlowTest`, `OfficeOrderEditFlowTest`,
  `OrderReceiptFlowTest`, `StoresFlowTest`, `VoiceCountFlowTest`, `ItemFlowTest`.
- To try it: as `cook.demo@example.com`, **Requests › New request**, type `1,5` for an item in pcs ("Use whole numbers for pcs."
  appears under the field) or `3000` for an item in kg and send: the form comes back with the error under
  the row. As the office, give an item a **Maximum per request** on its Edit form and request more than that.
