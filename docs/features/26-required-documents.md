# 26. Required documents

Required documents (P42, office users only; P58: per vessel): which certificates and documents each rank needs on
each vessel, and who is missing what. `core/requirement/` (`DocumentRequirement`,
`DocumentRequirementRepository`) and `requirement/` (`RequirementController`, `RequirementService`,
`RequirementGrid`, `RequirementGridForm`, `RequirementSelection`, `RequirementRules`, `RequirementState`,
`RequiredDocument`, `Compliance`, `ComplianceService`, `ComplianceExport`, `StarterRequirements`,
`VesselRequirementsMigration`); template `templates/requirement/grid.html`, the
shared fragments in `crew/parts.html` (`documentsSummary`, `documentState`, `requiredDocumentsCard`,
`documentsMissingLink`, `myMissingDocuments`); styles in `app.css` section "Required documents (P42)". This is the
company's own list: the page says it does not state what any regulation requires; flag-state and charterer rules
are not checked, and the crew cannot upload documents themselves.
- **Required documents** in the sidebar (Administration, after Office users; a document with a checklist),
  `/office/requirements`: a grid with the certificate types as rows and the ranks as columns, by department
  (names turned upright), then **Optional** and **Note** (up to 200 characters). P58: every vessel has its own
  rules (`document_requirement.ship_id`), so the **Vessel** filter lists only the active vessels, A to Z, each with
  its type ("ALDER · Bulk carrier"); the first is shown by default, another with `?vessel=12` (an unknown or
  inactive vessel: the first, with "That vessel is not active, so nothing was changed."). With JavaScript
  (`js/requirements.js`) the select becomes a search box ("Type a vessel name"), an ARIA combobox; without it the
  plain select and its **Show** button. Without any vessel the page says "No vessels yet" and links to **New
  vessel**. Above the grid: "Required on ALDER", "Based on the defaults for Bulk carrier." and **Reset to Bulk
  carrier defaults**. A plain form of checkboxes, so it saves without JavaScript; the first column stays put while
  the grid scrolls sideways inside its card (also on phones; the page itself never does). P43: each row starts with
  an **All ranks** checkbox ("Select all ranks for Passport"): ticked, saving requires the type for every rank of
  this vessel; with JavaScript it ticks and unticks the row at once and follows the row's boxes. Each row ends
  with a **Delete row** trash icon ("Remove Passport from ALDER's requirements"; a confirmation, see
  "confirmations (P10)": "Remove Passport? Removes Passport from ALDER's requirements. The other vessels keep
  theirs."; `POST /office/requirements/delete`, without JavaScript a confirmation page), recorded as "Required
  documents changed" ("ALDER: Passport removed"); a row the vessel does not have says there is nothing to remove.
  **Add a document type** adds a row at the top to tick. Rows: the vessel's defaults, then every other type
  required or held, A to Z. Saving changes only what was posted, on the shown vessel only; a row without ranks,
  note or Optional is removed. Recorded as "Required documents changed" ("ALDER: Tanker Endorsement removed",
  "ALDER: Passport +COOK").
- **Defaults by vessel type** (`StarterRequirements.defaults(type)`, the P42 starter template): STCW Basic Safety
  Training, Medical Fitness Certificate, Seaman's Book, Passport and Security Awareness for every rank;
  Certificate of Competency, Flag State Endorsement, Advanced Fire Fighting and Medical First Aid for the Master,
  deck officers, engineers and ETO; GMDSS Operator for the Master and deck officers; Tanker Endorsement for every
  rank on tankers. A vessel without a type has the general defaults (all but Tanker Endorsement).
  - A **new vessel** gets a copy of its type's defaults when it is created (`ShipService.create`, always, also
    with the starter template switched off), each row marked **Suggestion** with a notice to review it; saving
    the vessel's grid confirms them.
  - A row that differs from the defaults (other ranks, Optional, a note, or a type the defaults do not have) is
    marked **Changed** (a neutral badge).
  - **Reset to Bulk carrier defaults** (`GET`/`POST /office/requirements/reset?vessel=12`, a confirmation, P10,
    listing every change: "Passport is added back", "Tanker Endorsement is removed", "Seaman's Book goes back to
    the default ranks, not optional and without a note"; "ALDER already has these defaults. Nothing will change."
    when there is none) replaces the vessel's rows with its type's defaults (confirmed, not suggestions);
    recorded as "Required documents reset to defaults" ("ALDER: reset to the BULK_CARRIER defaults: Passport
    restored").
  - **Vessel type changes** (Edit vessel): the form asks "Required documents if the type changes", **Keep this
    vessel's required documents** (the default) or **Reset them to the new type's defaults**; with JavaScript the
    question shows only while the type differs from the saved one (`data-changed-from`, `js/forms.js`).
  - Starter template on first start: with no requirements yet and vessels present, every vessel gets its type's
    defaults as suggestions, once (recorded as "Required documents starter template added"), so a template the
    office cleared stays clear; `app.requirements.starter-template: false` turns it off (tests).
- **Migration from vessel types** (`VesselRequirementsMigration`, on startup, before the starter template, in one
  transaction): the rows saved before P58 had a vessel type (or none: all vessels) and no vessel. Each vessel,
  active or not, gets its own copy of the rules that applied to it, its type's and the all-vessels ones, merged
  per document type as compliance merged them: the ranks for which any was required (or, if none was, the
  optional ranks, still optional), the type's note before the all-vessels one, a suggestion only when every row
  was one. A vessel that already has a row of that type keeps its own. Then the old rows are deleted, so it runs
  once and no seafarer's compliance changes. Recorded as "Required documents moved to vessels" ("11 rules by
  vessel type copied to 4 vessels as 41 rows"). The old `ship_type` column stays, unused.
- **States**, worked out on every read, nothing derived stored: the certificate of that type (same type ignoring
  case and surrounding spaces) that expires last; **Valid**, **Expires in 12 days** (within 30), **Expired**
  (counts as missing), **Missing**. A certificate whose expiry is beyond the P39 plausible range (30 years) is ignored. An optional
  document is shown, last and marked "Optional", never counted. Without a rank or a vessel nothing applies: the
  person's Documents column is a dash ([8](08-crew.md)).
- **Where it shows**: the office's crew member page, card **Required documents** ("For Chief Officer on MV Demo
  (vessel type: Tanker)", missing and expired first, each with its badge, **Open** for a file and **Add
  certificate** with the person and type filled in, coming back to the page; **Change what is required**); the Crew
  list and a vessel's Crew tab (8: **Documents** "5 of 7" with an icon, sortable, most missing first; **Missing
  documents** filter, `?documents=missing`; the column, filter and export only once any requirement is set); the
  vessel's certificate matrix (12, **Missing**); the Overview ([4](04-overview.md)); and for the seafarer My profile ([20](20-my-profile-and-my-crew.md)) and Home
  ([18](18-crew-home.md)).
- **Export required documents** (`/office/crew/documents/export`, `/office/ships/{id}/crew/documents/export`, the
  list as filtered, every page): `required-documents[-<IMO>]-<date>.xls`, sheet "Required documents", one row per
  person and document in the card's order: `imo_number, ship_name, crew_member, rank, requirement, required` (yes,
  or no for an optional one)`, state` (missing, expired, expiring, valid)`, expires_on` (a date cell). People
  without required documents have no row.
- Queries: the Crew list adds two whatever its size (the requirements, and the certificates of the page's people;
  its sort and filter apply the same rules in SQL, `crew/PeopleQueries`), 15 for the whole page; the Overview adds
  one, and two more when requirements are set.
- Tests: `requirement/RequirementsFlowTest`.
