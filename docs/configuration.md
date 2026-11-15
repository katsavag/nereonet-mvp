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
| `ASSEMBLYAI_API_KEY` | optional (P57); the AssemblyAI key for counting the store by voice; with `MISTRAL_API_KEY`, the Count page offers the microphone (see [34](features/34-voice-count.md)). Put it in the environment or the gitignored `.env`, never in a checked-in file |
| `ASSEMBLYAI_BASE_URL` | optional; default `https://api.assemblyai.com` (`https://api.eu.assemblyai.com` for the EU) |
| `ASSEMBLYAI_TIMEOUT` | optional; how long one recording may take, upload to text, default `60s` |
| `ASSEMBLYAI_LANGUAGE_CODE` | optional; default `en` |
| `ASSEMBLYAI_FAKE` | optional; `true` answers with a fixed sentence instead of AssemblyAI (dev screens without a key), default `false` |
| `MISTRAL_API_KEY` | optional (P57); the Mistral key that turns the text into count lines (see [34](features/34-voice-count.md)). Environment or `.env` only |
| `MISTRAL_BASE_URL` | optional; default `https://api.mistral.ai` |
| `MISTRAL_MODEL` | optional; default `mistral-small-2603` (Mistral Small 4) |
| `MISTRAL_TIMEOUT` | optional; default `30s` |
| `MISTRAL_OCR_MODEL` | optional (P62); default `mistral-ocr-4-1` (OCR 4.1), for reading the delivery invoice (see [37](features/37-invoice-photo.md)) |
| `MISTRAL_OCR_TIMEOUT` | optional; default `60s` per page |
| `INVOICE_READER` | optional (P67); who reads the photographed delivery invoice: `local` (default, the OCR on the server, see below) or `mistral` (Mistral OCR, needs `MISTRAL_API_KEY`) (see [37](features/37-invoice-photo.md)) |
| `OCR_MODEL_DIR` | optional; default `models/ocr` (from the working directory), the folder with the local OCR models; without them invoices are not read |
| `OCR_MAX_SIDE` | optional; default `1600`, the page is scaled to this many pixels on its long side to find the text |
| `OCR_THREADS` | optional; default `2`, CPU threads one reading uses |
| `INVOICE_MIN_CONFIDENCE` | optional; default `0.85`, below it an invoice line is flagged "check" |
| `INVOICE_READING_STALLED_AFTER` | optional; default `15m`, a reading not finished by then is given up |
| `INVOICE_SAMPLE_DIR` | optional; default `samples/invoice`, the sample invoice the fake reader reads |
| `MISTRAL_FAKE` | optional; `true` matches item names with simple rules instead of Mistral, default `false` |
| `QUANTITY_MAX_KG`, `QUANTITY_MAX_LT` | optional (P60); the most one request, order or receipt line may hold in kg / lt, default `2000` (see [35](features/35-input-limits.md)) |
| `QUANTITY_MAX_G`, `QUANTITY_MAX_ML` | optional; the same in g / ml, default `50000` |
| `QUANTITY_MAX_COUNTABLE` | optional; the same for pcs, box and every other countable or unknown unit, default `5000` |
| `QUANTITY_STORE_FACTOR` | optional; a store count may hold this many times the line maximum, default `10` |
| `STOCK_LOW_THRESHOLD` | optional (P52); a provisions item above 0 and below this quantity is Low on the Chief cook's Stores page, default `10` (see [30](features/30-stores.md)) |
| `BOOTSTRAP_OFFICE_EMAIL` | first office user; required only while no office user exists |
| `BOOTSTRAP_OFFICE_PASSWORD` | first office user; required only while no office user exists |
| `SUPPORT_CONTACT` | optional free text shown as "Office contact" on the "link is invalid or has expired" page and as "Questions? Contact: …" in the footer of the invitation and password reset emails, e.g. `Crewing department · crewing@example.com · +30 210 000 0000`; not shown when empty |

## Local OCR models

The invoice reader (`INVOICE_READER=local`) needs three files in `OCR_MODEL_DIR` (default `models/ocr`, gitignored).
They are PaddleOCR's official ONNX exports (Apache 2.0), about 13 MB in all:

| File | Download |
|---|---|
| `det.onnx` | `https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx` |
| `rec.onnx` | `https://huggingface.co/PaddlePaddle/el_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx` |
| `rec.yml` | `https://huggingface.co/PaddlePaddle/el_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.yml` |

```sh
mkdir -p models/ocr && cd models/ocr
curl -L -o det.onnx https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx
curl -L -o rec.onnx https://huggingface.co/PaddlePaddle/el_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx
curl -L -o rec.yml https://huggingface.co/PaddlePaddle/el_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.yml
```

`rec.yml` holds the recognition model's characters; a `dict.txt` (one character per line) does instead. Another
recognition model works when its characters come with it. The server needs no GPU: about 4 s a page on 2 to 4 cores
and some 300 MB of memory. ONNX Runtime is a native library in the jar (Linux, Windows, macOS; x64 and arm64); on
Windows it needs the Microsoft Visual C++ runtime. Java prints a "restricted method" warning when it loads; start
the app with `--enable-native-access=ALL-UNNAMED` to silence it.

Outside dev, SMTP login and STARTTLS are required (no plain-text fallback). Keep secrets out of
checked-in files (`application*.yml`, `docker-compose.yml`) and do not enable `mail.debug` or DEBUG
logging for `jakarta.mail` / `org.eclipse.angus.mail`: that output contains the SMTP login.
