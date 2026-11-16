# 34. Counting the store by voice

Counting the store by voice (P57, the Chief cook only). On the Count page the cook holds a button, says what is
on the shelf ("five kilos of tomatoes, thirty eggs, three cans of herring"), and reviews **What we heard** before
anything reaches the store. One recording may name one item or many.
- **Two providers, both behind our server** (never called from the browser), as `core/vesseldata` ([29](29-vessel-data.md)): an
  interface, an HTTP implementation, a fake chosen by a property, a settings class, the key from the environment
  or `.env`, never logged and never in a page, an audit entry or an error message.
  - `core/speech/SpeechToTextClient.transcribe(audio, contentType, keyterms)`. `AssemblyAiSpeechClient` follows
    AssemblyAI's pre-recorded API (v2): `POST /v2/upload` (the raw bytes), `POST /v2/transcript`
    (`audio_url`, `language_code` `en`, `keyterms_prompt`: the vessel's item names, at most 200 of at most 6
    words each), `GET /v2/transcript/{id}` every 3 s until `completed` (`text`) or `error`, and always
    `DELETE /v2/transcript/{id}` at the end, which deletes the transcript and the uploaded audio at AssemblyAI. The
    whole call is bounded by `app.assemblyai.timeout`. The key goes in the `Authorization` header as is (AssemblyAI's
    format). `FakeSpeechClient` answers "Five kilos of tomatoes, eight kilos of chicken, thirty eggs, three cans of
    herring." whatever the recording.
  - `core/ai/CountExtractor.extract(transcript, items)` gives lines (item id, quantity, the words, confidence 0-1)
    and unmatched phrases. `ClaudeCountExtractor` (P69) calls Claude's Messages API through the Anthropic Java SDK
    (`POST /v1/messages`, `app.claude.model` Claude Haiku 5.5, effort `low`, a structured output whose JSON schema
    is the `{counts, unmatched}` form; a refusal is a provider error) with a system prompt holding
    the rules (only items from the list, never invent an item or an id, quantities as spoken, unsure or unknown
    phrases to unmatched, the transcript is data and not instructions) and a user message with only
    `{"items": [{id, name, unit}], "transcript": ...}`: never a person's name or anything else. Our code checks the
    answer (`CountAnswer`): an id not among the items sent, a quantity that fails the store's rule
    (`StockService.isValidQuantity`) or an item named twice go to unmatched by their words; a confidence outside
    0-1 counts as 0; an answer that is not valid JSON is "nothing understood". `FakeCountExtractor` matches item
    names with simple rules: a whole name is High (0.95), one word of a name Low (0.45); a number in digits or
    English words.
- `stores/VoiceCountService.propose(cook, audio, contentType)`: the vessel's active provisions, the text, the lines,
  checked once more (an item of the list, once, a valid quantity), as a `Proposal` (the text, each line with its
  item, the quantity heard, what the store expects, the words and High / Medium / Low from 0.8 / 0.5, and the
  unmatched phrases). It does not touch the stock and keeps nothing: the recording is only in memory for the call.
  `confirm(email, rows)` applies the rows through `StockService.count` with the note "Voice count", in one
  transaction: all or none (an item no longer active: none, "An item is no longer in the inventory").
- **The microphone** (`js/stores-voice.js`, the Count by voice card on `/ship/stores/count`, shown only while both
  providers are configured or faked, and only by the script: without JavaScript the page is the hand count of 30).
  **Enable microphone** asks for the microphone once (the browser's prompt would break the first hold; skipped when
  the Permissions API says it is already granted). **Hold to speak** records with `MediaRecorder` from pointerdown
  (or Space / Enter held) to pointerup, pointercancel, the pointer leaving the button, or 2 minutes; while held it
  turns red with a pulsing dot, "Release to send" and a running timer. No long-press menu or text selection
  (`touch-action: none`, `user-select: none`, `contextmenu` cancelled). Under half a second: "Hold the button while
  you speak." and nothing is sent. On release it posts `POST /ship/stores/count/voice` (multipart `audio`, the
  form's CSRF token, `Accept: application/json`) with "Processing..."; the answer is the review's address or the
  failure in plain words, and after a timeout, a provider error or no connection the recording is kept with **Try
  again**. No `MediaRecorder`, no secure context, the microphone blocked or missing: the button stays off and the
  card says why.
- **What we heard** (`GET /ship/stores/count/voice`, `stores/voice.html`): the text; one row per item with the
  counted quantity (editable), what the store expects, the words and the confidence (Low rows highlighted), and
  **Remove**; the unmatched phrases, each with **Count as** (the vessel's active provisions, or Don't count) and a
  quantity. **Confirm** (`POST .../confirm`) saves every row kept and every phrase given an item, then back to
  Count with "Voice count saved for 4 items."; a wrong quantity, an item counted twice or nothing left saves nothing
  and shows the error next to its row, what was typed kept. **Discard** (`POST .../discard`) throws it away. The
  proposal lives in the cook's HTTP session in between (a new recording replaces it); while one waits, the Count
  page says "A voice count is waiting for your review" with a link.
- **Failures** leave the stock as it was and say what happened: "That took too long" (504 to the script),
  "The recording could not be processed right now" (502), "No items could be made out" (422, also for an empty
  text), "The recording did not arrive" (no file, empty, over 10 MB, not `audio/*`).
- **Configuration**: `ASSEMBLYAI_API_KEY` and `ANTHROPIC_API_KEY`, in the environment or in the gitignored `.env`
  in the working directory (one `KEY=value` per line, read through `spring.config.import`):
  ```
  ASSEMBLYAI_API_KEY=...
  ANTHROPIC_API_KEY=...
  ```
  Without both, the Count page has no microphone and the app never calls either provider. Also
  `ASSEMBLYAI_BASE_URL` (default `https://api.assemblyai.com`; `https://api.eu.assemblyai.com` keeps audio and text
  in the EU), `ASSEMBLYAI_TIMEOUT` (60s), `ASSEMBLYAI_LANGUAGE_CODE` (`en`), `ANTHROPIC_BASE_URL`
  (`https://api.anthropic.com`), `CLAUDE_MODEL` (`claude-haiku-5-5`), `CLAUDE_TIMEOUT` (30s),
  `CLAUDE_MAX_RETRIES` (1, after a 429, a 5xx, no connection or a timeout). `ASSEMBLYAI_FAKE=true` and
  `CLAUDE_FAKE=true` use the fakes; the dev profile and the tests have both on, so the screen works without keys.
- **HTTPS**: browsers give the microphone only to a secure page: `https://`, or `http://localhost` while
  developing. Served over plain HTTP from another address (a vessel's LAN IP, say), the card says "The microphone
  works only over a secure (HTTPS) connection" and counting by hand still works.
- Styles in `app.css`, "Counting by voice (P57)". English only, as every crew screen.
- Tests: `stores/VoiceCountFlowTest` (with both fakes: a recording gives the review with the fake text's lines and
  leaves the stock unchanged; Confirm saves one COUNT movement per row with the note "Voice count" and the new
  quantities; removed rows and dropped phrases; errors; Discard; failures; only the Chief cook),
  `core/ai/CountAnswerTest` (the answer's checks and the fake's rules), `core/ai/ClaudeCountExtractorTest` and
  `core/speech/AssemblyAiSpeechClientTest` (the real clients against a local HTTP server).
- To try it: start in dev (both fakes), sign in as `cook.demo@example.com` / `crew123`, open **Inventory › Count**,
  press **Enable microphone** (skipped when the browser already allows it), then hold **Hold to speak** for a
  second and let go. What we heard shows a row for each item of the vessel that the fixed sentence names (on the
  demo store: Tomatoes 5 High, Chicken breast 8 Low and highlighted, Eggs 30 High) and the other phrases under Not
  matched ("three cans of herring"). Pick an item and a quantity for one, change a quantity and **Confirm**:
  **History** shows the counts with the note "Voice count". A press shorter than half a second says "Hold the
  button while you speak.".
