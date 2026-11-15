# 29. Vessel data from VesselAPI

Vessel data from VesselAPI (P45, office users only). The office types an IMO number, presses **Look up by IMO**,
reviews what VesselAPI returns (particulars, last AIS position, declared destination and ETA) and saves only what it
accepts. Opening a vessel never calls VesselAPI, and every value shows how old it is. `core/vesseldata/`
(`VesselApiSettings`, `VesselApiClient`, `HttpVesselApiClient`, `FakeVesselApiClient`, `VesselDataService`,
`VesselDataSnapshot`, `VesselLookup`, `VesselParticulars`, `AisPosition`, `EtaReport`, `VesselJson`,
`VesselDataView`) and `ship/` (`ShipController`, `VesselDataController`, `VesselLookupStore`, `VesselProposal`,
`VesselFormat`); templates `ship/new.html`, `ship/edit.html`, `ship/vessel.html` and fragments in `ship/parts.html`;
styles in `app.css` section "Vessel data (P45)".
- **Configuration**: `app.vesselapi.base-url` (default `https://api.vesselapi.com/v1`, `VESSELAPI_BASE_URL`) and
  `app.vesselapi.api-key` (`VESSELAPI_API_KEY`). The key comes from the environment or from a local `.env` in the
  working directory (`VESSELAPI_API_KEY=...`, read through `spring.config.import: optional:file:.env[.properties]`;
  `.env` is gitignored). It never appears in the repository, a page, a log line, an audit entry or an error
  message. Without a key the lookup buttons are hidden and the forms say "The VesselAPI integration is not
  configured". `VESSELAPI_FAKE=true` answers with built-in sample data instead (IMO 9328716, every call answers;
  IMO 9074729, the demo vessel, flags that disagree, a doubtful position and no ETA report), for dev screens
  without a key; the tests use the same fake.
- **The three calls**, server side only (never from the browser), each a `GET` with `Accept: application/json` and
  `Authorization: Bearer <key>`, 5 s to connect and 5 s to read, made at the same time and independent of each
  other (one failing never cancels the others):
  - `/vessel/{imo}?filter.idType=imo`: particulars (`{"vessel": {...}}`)
  - `/vessel/{imo}/position?filter.idType=imo`: last AIS position (`vesselPosition`, `positionCheck`)
  - `/vessel/{imo}/eta?filter.idType=imo`: destination, ETA and draught (`vesselEta`)
  Each ends as `OK`, `NOT_FOUND` (404), `UNAUTHORIZED` (401/403, "VesselAPI access problem"), `RATE_LIMITED` (429,
  "VesselAPI limit reached, try later"), `TIMEOUT` or `PROVIDER_ERROR` (5xx, no connection, a body that is not
  JSON or larger than 100,000 characters; "VesselAPI temporarily unavailable"). A 404 has its own words per call:
  "VesselAPI does not know this IMO number", "No recent AIS position", "No ETA report available".
- **Add vessel** (the list's pop-up, and `/office/ships/new` as a page where Add vessel leads without JavaScript):
  the IMO field takes "9328716" or "IMO 9328716" (`ImoNumber.normalize` / `isValid`). **Look up by IMO** is a
  submit button with its own action (`POST /office/ships/new/lookup`), so it works without JavaScript; the results
  show on the page. An invalid number is the field error and no call; a number already in the fleet shows
  "Already in the fleet: <name>" with a link to it, and no call. Results prefill name, type (VesselAPI's subtype,
  else type, mapped to the vessel types: bulk, tanker, container, general cargo, else Other) and flag
  (`country_code`, two or three letters, to the ISO code), all still editable, with the provider's values next to
  the ones in the form, the position and the destination with ETA. Nothing is saved until **Save**.
- **Edit vessel**: the IMO number stays fixed. **Refresh from VesselAPI** (`POST /office/ships/{id}/edit/lookup`,
  also on the Vessel data tab) shows the Edit page with a **Saved / VesselAPI** table, one checkbox per field that
  differs; only the ticked fields change, and only on Save. The refresh updates the stored VesselAPI data at once.
- **The lookup token**: the server keeps each lookup (`VesselLookupStore`, in memory, one hour) under a random token
  the form carries; Save takes the provider's values from there, only for the user who looked up, and refuses a
  result whose IMO number differs from the form's (or the vessel's). Every error keeps what the user typed. The
  usual vessel create and edit rights apply (office users).
- **Flags that disagree**: when `country` and `country_code` point to different countries, both are shown and the
  user chooses (a radio pair; on Edit "Keep the saved flag" is the default). The saved flag never changes silently.
- **Flag icons** (P43 rule): every VesselAPI flag field shows the country's flag icon before it, through
  `layout/parts :: country` / `flag`: the lookup result (provider country and the value in the form), the flag
  choice (one icon per option), the Edit comparison, and the Vessel data tab's flag country and code. The ISO code
  comes from `@vesselFormat` (`codeOfName`, `codeOf`, `codeOfField`); an unknown country gets no icon.
- **What is stored**: one `VesselDataSnapshot` row per vessel and call (`ship`, `endpoint` PARTICULARS / POSITION /
  ETA, `rawJson`, `providerReportedAt`, `lastSuccessAt`, `lastAttemptAt`, `lastAttemptResult`). `rawJson` is the
  body exactly as received, unknown fields kept; the pages parse their fields from it on read. A failed call keeps
  the previous body and dates and changes only the last attempt. Written when a looked-up vessel is saved and on
  every refresh, never on opening a page.
- **Vessel data tab** (`/office/ships/{id}/vessel`, after Overview, no count): cards in the card grid, each with only
  the fields VesselAPI gave. Identity (IMO, MMSI, name, AIS name, call sign, type, status, flag country and code,
  port of registry, former names with years, identical entries once); Technical (year built, length and beam with
  units, GT, DWT, engine type and model, power in kW; a muted dash, "Not given" for screen readers, where there is
  no value); **Provider statistics** (average and maximum speed and draught, labelled as VesselAPI's figures over
  about 31 days, not the latest AIS values); Last AIS position (latitude and longitude, report time and its age,
  SOG, COG, heading, navigation status in words; "Possible position error (flagged by VesselAPI)" next to the
  coordinates when VesselAPI flags `suspected_glitch`, `exceeds_typical_speed` or an identity mismatch); Destination
  and ETA (destination as declared, port code, ETA, declared draught, report time, ETA and report time apart, in
  UTC). Each card ends with "Provider time … UTC · fetched … UTC" and, after a failed refresh, "Refresh failed at …
  UTC: rate limit reached". **Full VesselAPI response** (collapsed) shows each stored body indented, with its fetch
  time. The Overview tab has one line: "Last AIS position 2 hours ago · Bound for NL RTM · ETA 20 Nov 2026 · 06:00
  UTC · Vessel data".
- **Audit log**: "VesselAPI lookup" (IMO and each call's result), "VesselAPI data refreshed" (the same, for a
  vessel), "VesselAPI data accepted" (the IMO, the fields taken and the results). Never the key.
- Table `vessel_data_snapshot` (Hibernate creates it on start).
- Tests: `ship/VesselLookupFlowTest` (with the fake), `core/vesseldata/HttpVesselApiClientTest` (the real client
  against a local server: paths, headers, statuses, the read timeout), `ship/ShipFlowTest` (not configured).
- To try it without a key: start with `VESSELAPI_FAKE=true`, open **Vessels › Add vessel**, type `9328716` and
  press **Look up by IMO**; or open MV Demo, **Edit**, **Refresh from VesselAPI**.
