# 11. Galley items

Galley items (signed in as an office user): the **Items** tab of a ship page (`/office/ships/{id}/items`, see [5](05-ships.md)).
Each item has a name (unique per ship, ignoring case), a unit chosen from one list (P78, `core/form/ItemUnits`: kg, g, lt,
ml, pcs, box, bottle, can, bag, pack, dozen; an item that already has another unit keeps it as one more option, and the
import still takes any unit), a required category
(P32, `core/item/ItemCategory`): **Provisions** (food and drink for the ship's meals) or **Personal items** (things
crew buy for themselves, such as cigarettes, soft drinks, toiletries), and, for provisions only, an optional
**Place** where it is stored (e.g. "Dry store", "Cold room"). On the add and edit forms Category is a select and
Place shows only while Category is Provisions (`js/forms.js`, `data-shown-for`; without the script it stays
visible, and the place of a personal item is never saved).
An optional **Maximum per request** (P60, in the item's unit) replaces the usual limit of its unit for one
request line, office order line or receipt line (see [35](35-input-limits.md)).
**Add item** and the item's name (or **Edit** in the row's **⋯** menu) open a form; items can be deactivated and activated (the row's **⋯** menu), never deleted. Deactivate asks first
("The crew of MV Demo cannot request Basmati rice until it is activated again."). The list shows name, unit,
category, place and status; it is searchable (name and place), filterable by status (Active, Inactive) and
category (Provisions, Personal items), sortable by name (the default), unit, category, place and status, 10 (20, 50 or 100)
per page (paged in the database).
A vessel added through **Add vessel** starts with the standard galley list (P78, `core/item/StarterItems`: the rows of
`src/main/resources/starter/galley-items.csv`, `name;unit;place`, all Provisions and active, no quantities; one audit
entry "Standard galley list added" with the number of items). A wrong row of the file stops the application at startup.
A vessel with no items at all has **Add the standard list** in the empty state of this tab
(`POST /office/ships/{id}/items/starter`); a name the vessel already has is skipped.
**Import** (`/office/ships/{id}/items/import`) adds and updates many items at once, from the file the purchasing
system produces or from the template, in the same four steps as the crew import (see [8](08-crew.md)): **Upload** (`.xlsx`,
`.xls` or `.csv`, any columns; **Download template** gives `items-template.xlsx` with
`Name | Unit | Category | Place` and an **Instructions** sheet in the user's language, which skips the mapping),
**Map columns** (Name, Unit, Category, and the optional Place; guesses such as Description, UoM, Product group,
Storage), **Review**, **Done**. There are no values to match: Category must be `Provisions` or `Personal items`
(ignoring case), and Place is kept for provisions only. The mapping is saved and suggested again
as for crew, for every ship (it describes the purchasing system's file, not the ship).
- **Review**: every row, editable. A name the ship does not have (ignoring case) is **New**; a name it has
  **Updates** that item's unit, category and place, or is **No change** (skipped) when they are the same. The rows
  that add or update are checked (missing name, unit or category, a category that is not Provisions or Personal
  items, value too long, name repeated in the file). Filters, **Exclude
  all rows with errors**, the live check of each cell (P39) and the sticky first columns as for crew.
- **Import items** saves the new items and the updates; **Done** shows how many were added, updated and skipped,
  with a link to the ship's items. Nothing is saved before that. An import started for one ship is never continued
  or saved for another: its steps lead back to that ship's upload.
To try it, save this as `items.csv` and upload it twice (the second time all three are "No change"; change a unit
to see an update):

```
Name,Unit,Category,Place
Basmati rice,kg,Provisions,Dry store
Eggs,pcs,Provisions,Cold room
Cigarettes,box,Personal items,
```

Data model (P32): `item.category` was free text and is now `ItemCategory` (stored as its name), and `item.place`
is a new nullable column. Hibernate keeps the old category column, so on startup `core/item/ItemCategoryMigration` sets
`PROVISIONS` on every item whose category is missing or not `PROVISIONS` / `PERSONAL_ITEMS` (every item before
P32 was a provision) and makes the column not null. It changes nothing on a new database. For development you can
simply recreate the database instead (drop it, or `docker compose down -v`, and start the app again: Hibernate
creates the schema and the dev profile seeds the demo data).
