# TODO

Client-side product TODOs live in the Hufly app repo (`../hufly/TODO.md`). Requirements: `../hufly/REQUIREMENTS.md`. Data model: `docs/domain-model.html`.

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
- [ ] Website: use the client's app icon (`../hufly`) as logo and favicon, and take the site colours from the app icon's theme colours (light and dark).
- [ ] LOW: STOMP sessions stay open after the access token expires, the session is logged out or the user is deleted. They only receive collection names (no data). Close them on logout/deletion, or require reconnecting with a fresh token.
- [ ] Security check 2026-10-02 (food plans, horse log, paddocks, settings, events): no HIGH or MEDIUM. LOW fixed: at most 500 live invitations per event. NOTE: rows a user may not see come as ids in `deletedEntries` (ids only, no content).
- [ ] General API rate limiting per user (only password logins are limited so far). Picture uploads decode up to 40 MP (~160 MB heap each) and need a tight per-user limit. Move limiter and version-counter state to a shared store (Redis) before running more than one server instance.
- [ ] Emailing generated passwords (USR-3), push notifications to closed apps via FCM/APNs (EVT-9; open apps already get STOMP hints), recurring events and tasks (EVT-10, TSK-5).
