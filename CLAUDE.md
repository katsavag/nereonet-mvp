# nereonet-mvp

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

## Always

- While developing, run only the affected test classes; run the full suite once before the commit.
- Never stage `src/main/resources/application-dev.yml`: it holds local credentials.
