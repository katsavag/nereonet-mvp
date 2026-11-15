# nereonet-mvp

## CONTEXT

Crew and ship management for one shipping company: office users (English and Greek) and ship crew (English only:
Master, Chief cook, crew members). Spring Boot + Thymeleaf + PostgreSQL in production, H2 in the tests. How to run
it is in `README.md`; what each feature does is in `docs/features/NN-name.md`; the environment variables are in
`docs/configuration.md`; static files, the design tokens and page conventions are in `docs/frontend-assets.md`.

Package map (`src/main/java/com/nereonix/nereonet_mvp/`):

- `core/*`: the domain. Entities, repositories and services that any feature may use (`core/order`,
  `core/stock`, `core/vetting`, `core/user`, ...). No controllers here.
- Every other package is a feature: its controllers, forms and page services (`officeorder`, `shiporder`,
  `stores`, `dashboard`, `vetting`, ...).
- `navigation`: the sidebar and the crew's tab bar (`NavSection`, `ShipNav`, `NavigationAdvice`).
- Templates mirror the features: `templates/<feature>/`. Shared fragments are in `layout/parts.html`,
  `crew/parts.html`, `ship/parts.html` and `core/library/parts.html`; each fragment has a usage note above it.

## CONVENTIONS

- Tests: one test class while developing, `./mvnw test -Dtest=DashboardFlowTest` (several:
  `-Dtest='DashboardFlowTest,SnoozeFlowTest'`); all of them with `./mvnw test`. The flow tests run the whole app on H2.
- Messages: every visible text is a key in `messages.properties` (English) and `messages_el.properties` (Greek).
  Office screens need both; crew-only and email keys are English only and are listed in `MessageKeysTest.ENGLISH_ONLY`.
  Every key must be used somewhere (`MessageKeysTest.everyKeyIsUsed`); a key built at run time (`'status.' + x`)
  goes in its allow-list. English says "vessel", never "ship" (except the names in `MessageKeysTest.SHIP_ALLOWED`);
  code, URLs, tables and export columns keep "ship". Greek: write natural Greek for the office, keep proper names
  (RightShip, SIRE 2.0) and file formats as they are.
- Status and badges: `layout/parts :: status(tone, label)` or `statusIcon(tone, label, icon)`, tone one of `ok`,
  `warning`, `danger`, `info`, `primary`, `neutral` (CSS `.status-<tone>`). The words carry the state, the colour
  only repeats it. Badges are never pills.
- Styles: every colour, size and shadow is a `--nn-*` token in the `:root` of `static/css/app.css`. The file is in
  the order of its table of contents (tokens, base, layout and navigation, shared components, features); put a new
  rule in its section. Templates have no inline styles (`DesignTokensTest`; the emails are the exception). Use the
  shared classes (`.list-plain`, `.text-meta`, `.num`, ...) before adding a feature class.
- Scripts: shared ones are in `layout/layout.html` with `defer`; a script used by one page is linked on that page
  with `defer`. Every page works without JavaScript.
- Comments: say what the code or page is now (model attributes, surprises), not how it got there. No task ids
  (P42) in new comments unless they explain old data.

## RULES

- Update the feature file under `docs/features/` (or add the next number), not the README. The README stays short.
- While developing, run only the affected test classes; run the full suite once before the commit.
- Never stage `src/main/resources/application-dev.yml`: it holds local credentials.
- One commit per task, message `PNN: <title>`.

## DELIVER

- The full `./mvnw test` passes (or say which test fails and why).
- Screenshots of the changed pages, desktop and phone width, when the UI changed.
- A short report: what changed, what was left out and why.

## Parallel work

Work comes in phases (P56, P57, ...) that build on each other, so most of it stays sequential. Use parallel agents only in these three cases.

### 1. Review fix batches (always)

When the user gives a list of fixes from a review:

1. Group the fixes by the files they touch. Fixes that share a file go in one group, for example `DevData`, a template, a fragment in `layout/parts`, the messages files or the CSS tokens.
2. Show the user the groups before starting.
3. Start one `review-fixer` agent per group, all in the same message. Each agent gets its fixes word for word, the files or areas involved, and the test classes to run.
4. Review each branch, then merge them into `main` one at a time and resolve any conflicts.
5. Run the full `./mvnw test` once on the merged result, then take screenshots from the main working tree where the UI changed.

If a batch has only one or two fixes, or every fix touches the same files, do the work directly instead.

### 2. Exploration (only when it fits)

Before a change that touches many areas (like P60, which put limits on every form input), start read-only Explore agents, one per area (forms and DTOs, entities, templates, messages), to report what needs changing. Don't do this for changes to a single feature.

### 3. Full suite in the background (only when it fits)

Run the full `./mvnw test` before a commit in the background when there is other work to do meanwhile, such as screenshots or the commit message. When there isn't, just run it.
