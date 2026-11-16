# Configuration outside dev

Set these environment variables:

| Variable | Example |
|---|---|
| `DB_URL` | `jdbc:postgresql://host:5432/nereonet` |
| `DB_USERNAME` | |
| `DB_PASSWORD` | |
| `MAIL_HOST` | e.g. `smtp.gmail.com` |
| `MAIL_PORT` | e.g. `587` (STARTTLS) |
| `MAIL_USERNAME` | SMTP login, e.g. the Gmail address |
| `MAIL_PASSWORD` | SMTP password; for Gmail an App Password (needs 2-step verification), not the account password |
| `MAIL_FROM` | system sender address; for Gmail the account address or one of its aliases |
| `MAIL_FROM_NAME` | optional; the sender name the inbox shows, default `Nereonix` |
| `ORGANISATION_NAME` | optional; the company's name at the bottom of the sign-in panel, e.g. `Nereonix Bulk Carriers`; nothing is shown when empty |
| `ORGANISATION_CODE` | optional; a short code under the name, e.g. `NEREONIX` |
| `APP_BASE_URL` | public URL used in email links, no trailing slash, e.g. `https://nereonet.example.com` |
| `CERTIFICATE_REMINDER_CRON` | optional; when the certificate reminder job runs (UTC), default `0 0 6 * * *` |
| `LIBRARY_STORAGE_DIR` | folder for library files, created if missing, e.g. `/var/lib/nereonet/library`; back it up with the database |
| `INVITATION_JWT_SECRET` | random secret, at least 32 characters, for signing invitation and password reset links |
| `RIGHTSHIP_BASE_URL` | optional (P40); the address of the RightShip API (from RightShip); the sync is off while it, the client id or the secret is empty (see [25](features/25-vetting.md)) |
| `RIGHTSHIP_CLIENT_ID` | optional; the RightShip API client id |
| `RIGHTSHIP_CLIENT_SECRET` | optional; the RightShip API client secret |
| `RIGHTSHIP_TOKEN_URL` | optional; where the OAuth token comes from, default `<RIGHTSHIP_BASE_URL>/oauth/token` |
| `RIGHTSHIP_SYNC_CRON` | optional; when the nightly RightShip sync runs (UTC), default `0 30 2 * * *` |
| `VESSELAPI_API_KEY` | optional (P45); the VesselAPI key for **Look up by IMO** and **Refresh from VesselAPI**; the buttons are hidden while it is empty (see [29](features/29-vessel-data.md)). Put it in the environment or the gitignored `.env`, never in a checked-in file |
| `VESSELAPI_BASE_URL` | optional; default `https://api.vesselapi.com/v1` |
| `VESSELAPI_FAKE` | optional; `true` answers with built-in sample data instead of VesselAPI (dev screens without a key), default `false` |
| `SIRE_REPORT_CURRENT_MONTHS` | optional (P41); company policy: months a SIRE 2.0 report is treated as current after the inspection, default `12` (see [25](features/25-vetting.md)) |
| `SIRE_COMMENT_DAYS` | optional (P41); company policy: days for the operator comments on a draft SIRE 2.0 report, default `14` |
| `ASSEMBLYAI_API_KEY` | optional (P57); the AssemblyAI key for counting the store by voice; with `ANTHROPIC_API_KEY`, the Count page offers the microphone (see [34](features/34-voice-count.md)). Put it in the environment or the gitignored `.env`, never in a checked-in file |
| `ASSEMBLYAI_BASE_URL` | optional; default `https://api.assemblyai.com` (`https://api.eu.assemblyai.com` for the EU) |
| `ASSEMBLYAI_TIMEOUT` | optional; how long one recording may take, upload to text, default `60s` |
| `ASSEMBLYAI_LANGUAGE_CODE` | optional; default `en` |
| `ASSEMBLYAI_FAKE` | optional; `true` answers with a fixed sentence instead of AssemblyAI (dev screens without a key), default `false` |
| `ANTHROPIC_API_KEY` | optional (P57, P69, P70); the Anthropic key with which Claude turns the text into count lines (see [34](features/34-voice-count.md)) and reads the photographed delivery invoice (see [37](features/37-invoice-photo.md)); without it invoices are not read. Environment or `.env` only |
| `ANTHROPIC_BASE_URL` | optional; default `https://api.anthropic.com` |
| `CLAUDE_MODEL` | optional; the model that counts by voice, default `claude-haiku-5-5` (Claude Haiku 5.5) |
| `CLAUDE_TIMEOUT` | optional; how long one count answer may take, default `30s` |
| `CLAUDE_INVOICE_MODEL` | optional (P70); the model that reads an invoice page, default `claude-opus-5-5` (Claude Opus 5.5) |
| `CLAUDE_INVOICE_TIMEOUT` | optional; how long reading one invoice page may take, default `120s` |
| `CLAUDE_MAX_RETRIES` | optional; tries again after a 429, a 5xx, no connection or a timeout, default `1` |
| `INVOICE_MIN_CONFIDENCE` | optional; default `0.85`, below it an invoice line is flagged "check" |
| `INVOICE_READING_STALLED_AFTER` | optional; default `15m`, a reading not finished by then is given up |
| `INVOICE_FAKE` | optional (P69); `true` "reads" the sample invoice instead of Claude (dev screens without a key), default `false` |
| `INVOICE_SAMPLE_DIR` | optional; default `samples/invoice`, the sample invoice the fake reader reads |
| `CLAUDE_FAKE` | optional; `true` matches item names with simple rules instead of Claude, default `false` |
| `QUANTITY_MAX_KG`, `QUANTITY_MAX_LT` | optional (P60); the most one request, order or receipt line may hold in kg / lt, default `2000` (see [35](features/35-input-limits.md)) |
| `QUANTITY_MAX_G`, `QUANTITY_MAX_ML` | optional; the same in g / ml, default `50000` |
| `QUANTITY_MAX_COUNTABLE` | optional; the same for pcs, box and every other countable or unknown unit, default `5000` |
| `QUANTITY_STORE_FACTOR` | optional; a store count may hold this many times the line maximum, default `10` |
| `STOCK_LOW_THRESHOLD` | optional (P52); a provisions item above 0 and below this quantity is Low on the Chief cook's Stores page, default `10` (see [30](features/30-stores.md)) |
| `BOOTSTRAP_OFFICE_EMAIL` | first office user; required only while no office user exists |
| `BOOTSTRAP_OFFICE_PASSWORD` | first office user; required only while no office user exists |
| `SUPPORT_CONTACT` | optional free text shown as "Office contact" on the "link is invalid or has expired" page and as "Questions? Contact: …" in the footer of the invitation and password reset emails, e.g. `Crewing department · crewing@example.com · +30 210 000 0000`; not shown when empty |

Outside dev, SMTP login and STARTTLS are required (no plain-text fallback). Keep secrets out of
checked-in files (`application*.yml`, `docker-compose.yml`) and do not enable `mail.debug` or DEBUG
logging for `jakarta.mail` / `org.eclipse.angus.mail`: that output contains the SMTP login.
