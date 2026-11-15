# 3. Signing in and the demo data

Open http://localhost:8080. Signed out, it goes to the sign-in page; signed in, it goes to
your dashboard (office users `/office`, ship crew `/ship`). Office users can switch language
with **EN | ΕΛ** at the top right of the sign-in page and of every office page (P29). Switching changes only the language: the page, its
filters, page number and token stay (see "language switch and required notices (P16)" in [frontend assets](../frontend-assets.md)).

On first start the dev profile creates an office user (`office@example.com` / `office123`)
and a demo ship (`MV Demo`, IMO 9074729). While there are no item requests and the first ship (by name) has no
active Master / Chief cook, it creates the users `master.demo@example.com` and `cook.demo@example.com` (password
`crew123`). The first ship also gets three more crew members with sample profiles for My crew (see [20](20-my-profile-and-my-crew.md)),
and, while it has none, two requests waiting for its Master (see [21](21-approvals.md)), with four demo items. No approved requests are seeded: the Master approves these and sends the ship order ([23](23-ship-order.md)), which the office then sees
on **Orders** ([24](24-office-orders.md)). While that ship has no stock at all, its provisions get some for the Chief cook's
**Stores** ([30](30-stores.md)): several OK, two Low and two Out of stock.
While that ship's library is empty it also gets two text items: "Galley hygiene rules" (Ship rules) and
"Drug and alcohol policy" (Company policies, required).
Once (while there is no ship with IMO 9300001): three bulk carriers with RightShip data entered by hand,
calculated 21 Oct 2026, each with its sample report from `samples/rightship/` (read from the working directory, so
start the app from the project folder): **ALDER** (9300001, Safety Score 3/5, GHG C unverified, inspection valid
for 38 more days), **LAUREL** (9400007, 5/5, A+ verified, 9 months) and **ROWAN** (9500003, 4/5, B verified,
6 months). MV Demo has no RightShip data. The sample reports are synthetic images made for the demo, not
RightShip documents. The demo ship is found by its IMO number 9074729 (the vetting vessels sort before it by name).
Once (while there is no ship with IMO 9600009): the tanker **BIRCH** (9600009) with the SIRE 2.0
inspection of the synthetic sample report `samples/sire/birch-sire-sample.pdf` (fictional vessel and company, not
an OCIMF document): 12 Jun 2026 at Piraeus, published 26 Jun 2026, five observations, three closed, one open
(target in 30 days) and one overdue (target 12 days ago), with the PDF as its report. So the sidebar shows
**Vetting 2** and the Overview lists ALDER's inspection and BIRCH's overdue observation (see [25](25-vetting.md)).

The database notes (Hibernate and an existing database) are in the [README](../../README.md#database-notes).
