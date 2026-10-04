# TODO

Client-side product TODOs live in the Hufly app repo (`../hufly/TODO.md`). Requirements: `../hufly/REQUIREMENTS.md`. Data model: `docs/domain-model.html`.

- [x] TODO: Get the App icon from the client and use it for the website. also use the app icons colors. (2026-10-03: logo, favicon and apple-touch-icon from the client's `app_icon.xml`; colours from `HuflyColors.kt`, which is generated from the icon)
- [x] TODO: Implement smooth scrolling for the website (2026-10-03: CSS `scroll-behavior: smooth`, off with reduced motion; anchors clear the sticky header)

## Next (in order)

Status 2026-10-02: every domain-model entity has endpoints and sync, 256 green tests, committed on `feature/auth-and-app-start`. The server runs locally with `docker compose up --build -d`.

1. ~~App-start flow~~ done, security follow-ups fixed (refresh rejects deleted users, dummy BCrypt check for unknown emails, sync body capped at 10 000 entries).
2. ~~Admin endpoints~~ done: users, tags, horses (create/delete admin-only, edit with HORSE_EDIT, sync). Horse `foodPlanId` is not settable yet; the food plan feature assigns it.
3. Done: horses, tasks, food plans, horse log, paddocks, user settings, events with invitations. Every domain-model entity now has endpoints and sync.
   Sync shape (decided 2026-10-02):
   - **Version sync** (like SchneaggchatV3server messages) for growing collections: Event, EventInvitation, HorseLogEntry, StableTask, PaddockAssignment. One counter document per collection in `counters` (atomic `$inc`). Every write, soft delete included, stamps `version`. `GET /x/sync?since=&page_size=` answers rows with `stableId == requester's AND since < version <= safeWatermark`, ascending, plus `newVersion` and `moreEntries`. Index `{stableId: 1, version: 1}`.
   - Built in `core/sync` (2026-10-02): counting and registering a version share one lock with `safeWatermark`, which closes the Schneaggchat gap (proven by a concurrency test). In-flight tracking is per process, so the server must stay single-instance until it moves to a shared store.
   - Rows the requester may not see are sent as deleted ids (`versionSync(visible = ...)`), so lost access clears the client.
   - Losing a whole permission (e.g. EVENT_VIEW) only affects rows that change later; the client also drops local data based on `/users/me` permissions.
   - **IdTimeStamp sync** (already built in `core/sync`) for small collections: users, tags, horses, food plans, paddocks, horse groups, horse conflicts. Model: `docs/domain-model.html` (complete as of 2026-10-02).
4. Run the `schneaggchat-security-check` skill after each new endpoint group. Every version-sync repository gets a Testcontainers test in `repository/mongo/`.

## Open decisions
- [ ] Website content before going live: real contact address (`index.html` uses the placeholder `kontakt@hufly.app`), full Impressum (ECG § 5, MedienG § 25) and privacy policy (DSGVO), prices once the payment model is decided.
- [ ] Payment model and subscription expiry (BIZ-6).

## Deferred features
- [ ] LOW (security check 2026-10-04, series): `isOccurrence` scans an open-ended series up to the requested date (at most ~360 000 dates for DAILY up to year 3000), and every valid date of an open series can get its own `EventOccurrence`/`TaskOccurrence` row, so restamping grows with them. Bound the key to e.g. 10 years after the first date and cap rows per series if abuse shows up.
- [ ] LOW: STOMP sessions stay open after the access token expires, the session is logged out or the user is deleted. They only receive collection names (no data). Close them on logout/deletion, or require reconnecting with a fresh token.
- [ ] Security check 2026-10-02 (food plans, horse log, paddocks, settings, events): no HIGH or MEDIUM. LOW fixed: at most 500 live invitations per event. NOTE: rows a user may not see come as ids in `deletedEntries` (ids only, no content).
- [ ] General API rate limiting per user (only password logins are limited so far). Picture uploads decode up to 40 MP (~160 MB heap each) and need a tight per-user limit. Move limiter and version-counter state to a shared store (Redis) before running more than one server instance.
- [ ] Emailing generated passwords (USR-3), push notifications to closed apps via FCM/APNs (EVT-9; open apps already get STOMP hints).
