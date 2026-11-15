# 9. Office users

Office users (signed in as an office user): **Office users** in the sidebar (`/office/users`) lists the office users
and the pending office invitations together, with **Invite office user** (see [6](06-invitations.md)). Search (name or email), a
**Status** filter, sorting by name, email and status and pages as on Crew (paged in the database the same way).
Any user, crew or office, can be deactivated (they can no longer sign in) and activated again, from the **⋯** menu
at the end of their row (here and on **Crew**). **Deactivate** asks first ("Deactivate Jane Doe? Jane Doe will not be
able to sign in until activated again."; a session that is already open is not ended). A deactivated user can also
be deleted (**Delete** in the same menu, after the confirmation "Delete Jane Doe? Jane Doe (jane@example.com) will be
deleted for good and cannot be restored."): the user and every invitation for their email are removed,
so they can be invited again. Their audit log entries stay and show "Deleted user"; invitations they sent stay.
They are removed from the recipient lists of announcements; announcements they sent stay and show "Deleted user".
You cannot deactivate or delete yourself.
