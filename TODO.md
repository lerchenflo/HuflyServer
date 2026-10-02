# TODO

Client-side product TODOs live in the Hufly app repo (`../hufly/TODO.md`). Requirements: `../hufly/REQUIREMENTS.md`. Data model: `docs/domain-model.html`.

## Next (in order)

Status 2026-10-02: auth and step 1 are done (User/Stable/Tag documents, login by email, extended `GET /users/me`, `POST /users/sync`, `POST /tags/sync`, `AccessService`), 64 green tests. Nothing is committed yet.

1. ~~App-start flow~~ done, security follow-ups fixed (refresh rejects deleted users, dummy BCrypt check for unknown emails, sync body capped at 10 000 entries).
2. Admin endpoints: create users with generated passwords (USR-1, USR-2), manage tags and permissions (TAG-2), add and remove horses (HOR-3, TAG-6).
3. Horses, food plans, horse log, paddocks, events with invitations, tasks, user settings, each with a sync endpoint. Model: `docs/domain-model.html` (complete as of 2026-10-02).
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
