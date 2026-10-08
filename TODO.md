# TODO

Open work only; finished items live in git history. Client-side product TODOs live in the Hufly app repo (`../Hufly/TODO.md`). Requirements: `../Hufly/REQUIREMENTS.md`. Data model: `docs/domain-model.html`.

## Push notifications
- [ ] Not built (user chose instant + answers + digest): due reminders for tasks and horse care (needs a scheduler and a sent-once record), per-type muting.
- [ ] Anyone who knows another install's push token could register it on their own session (the token moves). Tokens are not public, so accepted for now.

## Open decisions
- [ ] Website content before going live: real contact address (`index.html` uses the placeholder `kontakt@hufly.app`), full Impressum (ECG § 5, MedienG § 25) and privacy policy (DSGVO), prices once the payment model is decided.
- [ ] Payment model and subscription expiry (BIZ-6).

## Deferred features
- [ ] LOW (security check 2026-10-04, series): `isOccurrence` scans an open-ended series up to the requested date (at most ~360 000 dates for DAILY up to year 3000), and every valid date of an open series can get its own `EventOccurrence`/`TaskOccurrence` row, so restamping grows with them. Bound the key to e.g. 10 years after the first date and cap rows per series if abuse shows up.
- [ ] LOW: STOMP sessions stay open after the access token expires, the session is logged out or the user is deleted. They only receive collection names (no data). Close them on logout/deletion, or require reconnecting with a fresh token.
- [ ] General API rate limiting per user (only password logins are limited so far). Picture uploads decode up to 40 MP (~160 MB heap each) and need a tight per-user limit. Move limiter and version-counter state to a shared store (Redis) before running more than one server instance.
- [ ] Emailing generated passwords (USR-3).
