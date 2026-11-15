# 17. Forgot password

Forgot password (any user, signed out): **Forgot your password?** under the password on the sign-in page opens
`/password/forgot`, one email field. **Send link** always goes to `/password/forgot/sent`, "If an account exists
for this address, we sent a link to set a new password.": the same page, byte for byte, for a known and an unknown
address (it names no address and has no form). The email (Mailpit in dev; in the language of the page it was asked
from) holds a `/password/reset?token=...` link that works once and for 60 minutes; no password is ever emailed.
It is plain text and HTML, in the invitation email's template ([6](06-invitations.md), P29), with **Set a new password** as its button.
- No email goes to an unknown or a deactivated address, nor to an address that already got 5 links within the
  last hour (the 6th request shows the same page and sends nothing). The email is sent on another thread, so the
  answer takes as long for a known address as for an unknown one.
- The link opens **Set a new password**: the account's email as text, then **New password** and **Repeat new
  password** with the rules, show / hide and checklist of registration (see [7](07-registration.md) and "sign-in and registration
  (P13)"). **Save new password** signs the user in at once and opens their home page with "Your password is
  changed. You are signed in.". It records "Password reset" in the audit log (see [10](10-audit-log.md)) with the user as actor and
  their email as details; never the password or the link.
- Using one link stops every other open link of that user. A link that was used, is older than 60 minutes, or
  belongs to a user who was since deactivated or deleted shows "This reset link is invalid or has expired",
  with **Request a new link** (`/password/forgot`).
- The tokens are signed like invitation tokens (`core/passwordreset/PasswordResetTokenService`, HS256 with
  `INVITATION_JWT_SECRET`), with the audience `password-reset`, so an invitation link is never accepted as a reset
  link. Each link sent is a `password_reset` row (token id, user, email, sent at, expires at, used at), kept for the
  hourly limit.
