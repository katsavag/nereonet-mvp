# 6. Invitations

Invitations (signed in as an office user). The email with the `/register?token=...` link
appears in Mailpit.
- Office users: **Office users** in the sidebar (`/office/users`, see [9](09-office-users.md)), then **Invite office user** (opens a pop-up
  form with full name and email; the email is asked once, and the success message names the address the invitation
  went to, e.g. "Invitation sent to jane@example.com."). There is no separate invitations page any more:
  `/office/invitations` redirects to `/office/users`. Pending invitations are listed there with the users, as
  "Invited", or "Invitation expired" when past their expiry date; accepted and cancelled invitations are not listed
  (an accepted one is a user). Each has, in the row's **⋯** menu, **Resend** (new link and expiry date, new email; the old link stops
  working) and **Cancel invitation** (the link then shows the "invalid or expired" page). Cancel asks
  first: "Cancel the invitation to Jane Doe? The link sent to jane@example.com stops working.", with **Keep
  invitation** and **Cancel invitation**.
- Crew members: **Add crew member** on the **Crew** page (see [8](08-crew.md)), or on a ship page's **Crew** tab (see [5](05-ships.md)), the same
  pop-up form with the ship preselected; `/office/ships/{shipId}/invitations/new` (the demo ship is usually id `1`)
  redirects to that tab with the form open. Crew invitations have the same **Resend** and **Cancel invitation** on
  the Crew list and the Crew tab (P17), with the same endpoints, `POST /office/invitations/{id}/resend` and
  `/cancel`; each returns to the list it was started from (the forms send `tab=crew&shipId=…`, or `returnTo=crew` with
  the Crew list's `shipId` and `status` filters, or `returnTo=overview`; anything else goes to Office users, never
  to a URL from the request). Both are in the audit log with the ship. The form asks for
  full name, email (once; the success message names the address), rank and ship. There is no role to pick
  (P18): the rank sets it, Master → Master, Chief Cook → Chief cook, every other rank (Chief Engineer included) →
  crew member. The rank is required and is copied to the user on registration. Under the rank picker (grouped by
  department: Deck, Engine, Catering) a read-only line says what the rank gives, e.g. "Access: Master · can publish
  library items and see who has read them", and follows the picker as it changes (`js/forms.js`; without the
  script it shows the saved rank's access). A ship can have only one active Master and one active Chief cook;
  inviting a second one shows an error on the rank field.
- The email (P29) is plain text and HTML in one message (`core/mail/MailService.send(to, subject, text, html)`,
  `multipart/related` holding `multipart/alternative` text + HTML, then the logo). The HTML is
  `templates/email/action.html`, filled from a `core/mail/ActionEmail` by `core/mail/HtmlEmails`: a 600 px table
  layout with inline styles only (`DesignTokensTest` exempts `templates/email/`), Inter first in the font stack
  with system fallbacks; a navy `#10223a` header with the logo (`img/brand/nereonix-logo-on-dark-600.png` attached
  inline as `cid:nereonix-logo`, never a remote image) and "Nereonix" as text; a white card on the cream `#f4f1ea`
  page with "Hello Jane Doe,", who invites to what ("Maria Papadopoulou invited you to join MV Aegean Star as
  Second Officer.", or "… to join the office."), one navy **Accept invitation** button (44 px), the link as text
  under it, the expiry in UTC; then a footer: who sent it, why the person got it and, when `SUPPORT_CONTACT` is
  set, "Questions? Contact: …". Every text has its own colour on its own background and
  `color-scheme: light`, so dark-mode clients and blocked images leave it readable. The texts are `email.*`
  message keys (`core/invitation/InvitationEmails`). Crew emails are in English; office emails are in the language
  of the office user who sends (or resends) them. The password reset email ([17](17-forgot-password.md)) uses the same template.
  In dev, `/dev/email/invitation` (office: `?office`, Greek: `&lang=el`) and `/dev/email/password-reset` show
  the HTML with sample data (`dev/DevEmailPreviewController`, dev profile only, signed in).
- Landing in the inbox, not in spam (P30). The app does its part: the sender has a name, "Nereonix
  <noreply@…>" (`MAIL_FROM_NAME`, default "Nereonix"; a name written in `MAIL_FROM` itself wins); the Message-ID
  ends in the sender's domain, not the computer's name (`spring.mail.properties.mail.from`); the inbox preview line
  is hidden with `display:none` only, never as text in the background colour or 1 px high; there is a plain-text
  part and no remote image. Most of the decision is not in the email, though:
  - a new Gmail address that suddenly sends invitation emails with a link to `http://localhost:8080` looks like
    phishing to Gmail. In development, mark the first one **Not spam** and add the sender to the recipient's
    contacts; Gmail learns from that;
  - for real use send from the company's own domain (for example `noreply@crew.example.com`) through Google
    Workspace or a transactional mail service, with SPF, DKIM and DMARC set up in its DNS, and use an `https`
    `APP_BASE_URL` on the same domain, so the links match the sender.
