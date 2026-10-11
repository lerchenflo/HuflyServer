# TODO

Open work only; finished items live in git history. Client-side product TODOs live in the Hufly app repo (`../Hufly/TODO.md`). Requirements: `../Hufly/REQUIREMENTS.md`. Data model: `docs/domain-model.html`.

## Push notifications
- [ ] Not built (user chose instant + answers + digest): due reminders for tasks and horse care (needs a scheduler and a sent-once record), per-type muting.
- [ ] Anyone who knows another install's push token could register it on their own session (the token moves). Tokens are not public, so accepted for now.

## Open decisions
- [ ] Payment model (BIZ-6): the app is free for now (decided 2026-10-09); in-app purchases may come later. The subscription fields stay unused (TRIAL); then add prices to the website.

## Deferred features
- [ ] Leaving a stable yourself (several stables per account exist since 2026-10-11; today only the admin removes a member, or the account is deleted). Decide with the client before building it.
- [ ] Email verification for self-signup (with email sending, USR-3); until then anyone can register any address and `EMAIL_IN_USE` tells whether an address has a login (10 registrations per IP and hour).
- [ ] LOW (security check 2026-10-04, series): `isOccurrence` scans an open-ended series up to the requested date (at most ~360 000 dates for DAILY up to year 3000), and every valid date of an open series can get its own `EventOccurrence`/`TaskOccurrence` row, so restamping grows with them. Bound the key to e.g. 10 years after the first date and cap rows per series if abuse shows up.
- [ ] General API rate limiting per user (only password logins, registrations, invite codes and picture uploads (4 per account and minute) are limited so far). Move limiter and version-counter state to a shared store (Redis) before running more than one server instance.
- [ ] Emailing generated passwords (USR-3).
