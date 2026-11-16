# Nereonet MVP

Crew and ship management for a single shipping company: vessels, crew and their certificates, the ship library and
news, item requests and vessel orders, the Chief cook's stores and delivery counts, suppliers and requests for
quotes, and vetting (RightShip, SIRE 2.0). Office users work in English or Greek; ship crew in English.
Spring Boot + Thymeleaf + PostgreSQL.

## How to run

Requirements: Java 25, Docker.

1. Start PostgreSQL and Mailpit:

   ```
   docker compose up -d
   ```

2. Start the app with the `dev` profile (uses the defaults in `application-dev.yml`):

   ```
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   On Windows (cmd/PowerShell) use `mvnw.cmd` instead of `./mvnw`.

3. Open http://localhost:8080. Emails sent by the app appear in the Mailpit inbox at http://localhost:8025.

Run the tests (H2 in memory, no Docker needed):

```
./mvnw test
```

## Dev accounts and demo data

On first start the dev profile creates:

- the office user `office@example.com` / `office123`;
- the demo ship `MV Demo` (IMO 9074729) with `master.demo@example.com` and `cook.demo@example.com` (password
  `crew123`), three more crew members, two requests waiting for the Master, some stock and two library items;
- the vetting demo vessels ALDER, LAUREL and ROWAN (RightShip) and the tanker BIRCH (SIRE 2.0), with the synthetic
  sample reports from `samples/` (start the app from the project folder).

The details are in [docs/features/03-sign-in-and-demo-data.md](docs/features/03-sign-in-and-demo-data.md).

## Database notes

The schema is updated by Hibernate, which cannot add a required column to a table that already has
rows (you get "column ... does not exist" errors). Then either add the column by hand or empty the
dev database (with the compose database: `docker compose down -v`, then `docker compose up -d`).
Hibernate also never updates the check constraints it creates for enum columns. If a database was created
before invitations could be cancelled, cancelling fails with `invitation_status_check`; fix it with:

```
ALTER TABLE invitation DROP CONSTRAINT invitation_status_check;
ALTER TABLE invitation ADD CONSTRAINT invitation_status_check CHECK (status IN ('PENDING','ACCEPTED','CANCELLED'));
```

Hibernate never drops a NOT NULL either. If a database was created before users could be deleted, deleting an
office user who sent invitations fails; fix it with:

```
ALTER TABLE invitation ALTER COLUMN invited_by_id DROP NOT NULL;
```

## Where things are

- `src/main/java/com/nereonix/nereonet_mvp/core/*`: the domain: entities, repositories and services that any
  feature uses (`core/order`, `core/stock`, `core/vetting`, ...).
- The other packages under `nereonet_mvp/` are features: controllers, forms and page services (`officeorder`,
  `shiporder`, `stores`, `dashboard`, ...). `navigation` builds the sidebar and the crew's tab bar.
- `src/main/resources/templates/<feature>/`: the pages. Shared fragments are in `layout/parts.html`,
  `crew/parts.html`, `ship/parts.html` and `core/library/parts.html`.
- `src/main/resources/static/`: `css/app.css` (the design tokens and every custom style), `js/`, fonts, flags and
  Bootstrap. See [docs/frontend-assets.md](docs/frontend-assets.md).
- `src/main/resources/messages.properties` (English) and `messages_el.properties` (Greek).
- `samples/`: the demo reports, the sample invoice and a crew import file.
- [docs/configuration.md](docs/configuration.md): the environment variables outside dev.
- [CLAUDE.md](CLAUDE.md): the conventions for working on the code.

## Features

One file per feature, numbered in the order they were built (1 and 2 are the run steps above):

- [3. Signing in and the demo data](docs/features/03-sign-in-and-demo-data.md)
- [4. Overview](docs/features/04-overview.md)
- [5. Ships](docs/features/05-ships.md)
- [6. Invitations](docs/features/06-invitations.md)
- [7. Registration](docs/features/07-registration.md)
- [8. Crew](docs/features/08-crew.md)
- [9. Office users](docs/features/09-office-users.md)
- [10. Audit log](docs/features/10-audit-log.md)
- [11. Galley items](docs/features/11-galley-items.md)
- [12. Certificates](docs/features/12-certificates.md)
- [13. Notifications](docs/features/13-notifications.md)
- [14. Announcements](docs/features/14-announcements.md)
- [15. Approved requests (removed)](docs/features/15-approved-requests.md)
- [16. Ship library](docs/features/16-ship-library.md)
- [17. Forgot password](docs/features/17-forgot-password.md)
- [18. Crew home page](docs/features/18-crew-home.md)
- [19. Crew news](docs/features/19-crew-news.md)
- [20. My profile and My crew](docs/features/20-my-profile-and-my-crew.md)
- [21. Approvals](docs/features/21-approvals.md)
- [22. Approving and declining a request](docs/features/22-approving-requests.md)
- [23. The ship order and Send to office](docs/features/23-ship-order.md)
- [24. Ship orders in the office](docs/features/24-office-orders.md)
- [25. Vetting](docs/features/25-vetting.md)
- [26. Required documents](docs/features/26-required-documents.md)
- [27. Snoozing alerts](docs/features/27-snoozing.md)
- [28. Suppliers and requests for quotes](docs/features/28-suppliers-and-quotes.md)
- [29. Vessel data from VesselAPI](docs/features/29-vessel-data.md)
- [30. Inventory](docs/features/30-stores.md)
- [31. Requests](docs/features/31-requests.md)
- [32. The Chief cook reads the vessel order](docs/features/32-cook-reads-order.md)
- [33. A received delivery goes into the store](docs/features/33-delivery-into-store.md)
- [34. Counting the store by voice](docs/features/34-voice-count.md)
- [35. Sensible limits on every input](docs/features/35-input-limits.md)
- [36. The Chief cook counts the delivery](docs/features/36-delivery-count.md)
- [37. Photographing the delivery invoice](docs/features/37-invoice-photo.md)
- [38. The invoice guides the count](docs/features/38-invoice-guided-count.md)
- [39. The office compares order, delivery and invoice](docs/features/39-invoice-comparison.md)
