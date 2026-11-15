# 20. My profile and My crew

My profile and My crew (P31, any ship user): a seafarer adds a little about themselves, and sees who is on board.
`profile/ProfileController`, `mycrew/MyCrewController`, `core/profile/` (the `crew_profile` table, `ProfileService`,
the photo handling), `templates/profile/` and `templates/mycrew/`; English only, as every crew screen. Every field
is optional, and what a person fills in is seen by everyone on board the same ship now and by the office.
- **My certificates** (P39): under the four rows of My profile, the person's own certificates, as on Home ([18](18-crew-home.md)),
  with "Only you and the office see your certificates. The office keeps them up to date: tell them when
  you renew one." and, for a certificate with a scan, a paperclip **Open**
  link (`/ship/profile/certificates/{id}/file`, see [12](12-certificates.md)). Their crewmates never see them (My crew is unchanged).
  P42 (see [26](26-required-documents.md)): the required documents missing or expired come first, marked **Missing** or **Expired**, with the
  office's note and "Contact the office to add it."
- **Where**: on phones the tab bar's last tab is **More** (`/ship/more`): who is signed in, **My profile**, **My
  crew**, what else is not a tab (P65, see [18](18-crew-home.md)) and **Sign out**. From 768 px the sidebar has **My crew** under On board and **My profile** under Account.
  The person's photo (or initials) is next to their name on Home ([18](18-crew-home.md)) and on My profile.
- **My profile** (`/ship/profile`): the office's details as the header (name, "rank · ship", read only), "Your
  crewmates and the office can see what you fill in.", then four full-width rows, each its own page with **Save**
  and **Cancel** (Save comes back with "Saved."; nothing is stored or audited when nothing changed):
  - **Languages** (up to 5, ISO 639-1, in the person's order): the chosen ones with **Move up**, **Move down** and
    **Remove**, and every language (English names) with **Add**. These are links that only change the page's
    working list (`?languages=el,en`); nothing is saved before **Save**. **Find** searches on the server (a GET
    form; accents do not matter, "bokmal" finds Norwegian Bokmål); with `js/profile.js` the list filters as you
    type.
  - **Nationality**: one country from a list (ISO 3166 codes, English names), or "Not given".
  - **Bio**: plain text, at most 280 characters, line breaks kept, with the shared character counter and draft
    keeping (P05).
  - **Photo**: JPEG or PNG, at most 5 MB (said before choosing). With `js/profile.js` the browser first makes it at
    most 800 px on its longest side and shows a preview, so a phone photo is ~100 KB over the satellite link;
    without the script the original is sent. Either way the server reads it (the EXIF orientation applied), makes a
    512 px version (longest side) and a 96 px square, and writes new JPEGs with no metadata at all (no EXIF, no
    GPS); the upload itself is never stored. Files: `<app.library.storage-dir>/profile-photos/<uuid>-512.jpg` and
    `-96.jpg` (back them up with the library). **Delete my profile photo** asks first and goes back to the
    initials ("Your photo is deleted."). A replaced or deleted photo's files are removed after the change is
    committed, and so are a deleted crew member's ([8](08-crew.md)).
  Each change is in the audit log ([10](10-audit-log.md)): "Profile languages changed" (`en, el`), "Profile nationality changed",
  "Profile bio changed" (its length, not the text), "Profile photo added" (`384x512 JPEG`), "Profile photo
  deleted".
- **My crew** (`/ship/crew`): the ship as the eyebrow, "Crew on board · 6", then one card per department (Deck,
  Engine, Galley, then "Rank not set") with its count: each person is one full-width link (56 px) with their photo
  or initials, name and rank, in rank order then by name; the signed-in person's own row says "· you". Only
  enabled users with a crew role on that ship now: never invited people (they are invitations, not users), the
  deactivated, other ships or the office. Alone on board: "You're the only one on board in Nereonet so far."
- **A crewmate** (`/ship/crew/{id}`): name, "rank · department", the 512 px photo and what they shared; with
  nothing: "Nikos hasn't added anything to their profile yet." **Back to My crew** at the end.
- Never on these pages: email addresses, join or sign-off dates, certificates. Anyone not on board the person's
  ship now (another ship, deactivated, an office user, no such id) is a 404, not a 403, and so is their photo
  (`/ship/crew/{id}/photo/{key}/{96|512}`, and only with the person's current key). Photos are sent with
  `Cache-Control: max-age=31536000, private, immutable`: a new photo has a new key, so a cached one is never
  stale, and shared caches never keep them.
- **The office** sees the same four fields read only in a **Profile** card on the crew member's page ([8](08-crew.md)), with
  the photo (`/office/crew/{id}/photo/...`), languages and country names in the page language.
- My crew is one query for the people and their profiles (`mycrew/CrewmateQueries`, a left join), so the page
  makes the same number of queries for 3 people or 30 (`MyCrewFlowTest` checks it). Rows load the 96 px photos
  lazily (about 2 KB each). The My crew pages load no script of their own; `js/profile.js` is only on the
  Languages and Photo pages (deferred), and every form works without it.
- Dev data: when they are missing, the first ship also gets `engineer.demo@example.com` (Nikos Pappas, Chief
  Engineer, a full profile), `ab.demo@example.com` (Maria Santos, Able Seaman) and `steward.demo@example.com`
  (Arjun Mehta, Steward, nothing filled in), password `crew123`; the demo Master and Chief cook get their ranks if
  they have none.
- To try it: as `cook.demo@example.com` / `crew123`, **More** (phone) or **My profile** (sidebar): add two
  languages and a photo; then **My crew** shows the cook's photo with "· you", and Nikos Pappas's page shows his
  profile. As `office@example.com`, Crew → Demo Cook shows the Profile card.
