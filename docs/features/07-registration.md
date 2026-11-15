# 7. Registration

Registration: open the `/register?token=...` link from the invitation email (signed out, e.g. in a
private window) and set a password. Opened while someone is still signed in (for example the office user who
has just sent the invitation, P30), the page shows on its own, never inside the sidebar and top bar, with "You are
signed in as John Doe. Creating this account signs you out and signs you in to the new account." The page says what the person is joining: "You are joining MV Demo as Second
Officer." (the invitation's rank, or its role without a rank: "as Master", "as a crew member") or "You are joining
Nereonet as an office user.". **Create account** signs the new user in at once and opens their home page
(`/office` or `/ship`) with "Your account is ready. You are signed in."; there is no trip through the sign-in
form. The English / Ελληνικά links keep the token, so switching language stays on the same invitation.
Each link works once; after that, or after it expires (or is cancelled), it shows the "This link is invalid or has
expired" page, which names the two causes and what to do about each (used already: sign in; expired: ask the office
for a new invitation, "A link works for 7 days", from `app.invitation.valid-days`). When `app.support-contact` is
set (`SUPPORT_CONTACT`, free text, see "Configuration outside dev"), the page also shows it as "Office contact".

Sign-in messages: a wrong email or password, for any account, gives "Wrong email or password.". A deactivated
account (see [9](09-office-users.md)) gives "This account is deactivated. Contact your office." only when the password was right
(`/login?deactivated`; `SecurityConfig` checks "enabled" after the password, not before as Spring Security does by
default), so the message never tells a stranger that an email has an account.
