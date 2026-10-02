# TODO

Client-side product TODOs live in the Hufly app repo (`../hufly/TODO.md`). Requirements: `../hufly/REQUIREMENTS.md`. Data model: `docs/domain-model.html`.

## Next (in order)

Status 2026-10-02: auth and step 1 are done (User/Stable/Tag documents, login by email, extended `GET /users/me`, `POST /users/sync`, `POST /tags/sync`, `AccessService`), 64 green tests. Nothing is committed yet.

1. ~~App-start flow~~ done, security follow-ups fixed (refresh rejects deleted users, dummy BCrypt check for unknown emails, sync body capped at 10 000 entries).
2. ~~Admin endpoints~~ done: users, tags, horses (create/delete admin-only, edit with HORSE_EDIT, sync). Horse `foodPlanId` is not settable yet; the food plan feature assigns it.
3. Horses, food plans, horse log, paddocks, events with invitations, tasks, user settings, each with a sync endpoint.
   Sync shape (decided 2026-10-02):
   - **Version sync** (like SchneaggchatV3server messages) for growing collections: Event, EventInvitation, HorseLogEntry, StableTask, PaddockAssignment. One counter document per collection in `counters` (atomic `$inc`). Every write, soft delete included, stamps `version`. `GET /x/sync?since=&page_size=` answers rows with `stableId == requester's AND since < version <= safeWatermark`, ascending, plus `newVersion` and `moreEntries`. Index `{stableId: 1, version: 1}`.
   - Built in `core/sync` (2026-10-02): counting and registering a version share one lock with `safeWatermark`, which closes the Schneaggchat gap (proven by a concurrency test). In-flight tracking is per process, so the server must stay single-instance until it moves to a shared store.
   - Rows the requester may not see are sent as deleted ids (`versionSync(visible = ...)`), so lost access clears the client.
   - Losing a whole permission (e.g. EVENT_VIEW) only affects rows that change later; the client also drops local data based on `/users/me` permissions.
   - **IdTimeStamp sync** (already built in `core/sync`) for small collections: users, tags, horses, food plans, paddocks, horse groups, horse conflicts. Model: `docs/domain-model.html` (complete as of 2026-10-02).
4. Run the `schneaggchat-security-check` skill after each new endpoint group.

## Open decisions
- [ ] Refresh token replay grace (SchneaggchatV3server keeps `previousHashedToken` so a client that lost the refresh response can retry). Currently a lost response forces a re-login.
- [ ] Payment model and subscription expiry (BIZ-6).

## Deferred features
- [ ] Stable onboarding website: an operator page, guarded by credentials from the env file, where the first admin and their stable get created. Until then there is no way to create a stable outside tests.
- [ ] Mongo-backed integration tests (Testcontainers) once Docker is available. Spring tests currently replace repositories with in-memory fakes.
- [ ] Rate limiting on `/auth/login` (bucket4j + Redis like SchneaggchatV3server).
- [ ] Verify the refresh-token TTL index against a real Mongo (`expiresAt` is `java.time.Instant`, so it should be stored as a BSON Date).
- [ ] Device name/type on sessions, logout on all devices, password change (USR-5).
- [ ] Emailing generated passwords (USR-3), push notifications (EVT-9), recurring events and tasks (EVT-10, TSK-5).
