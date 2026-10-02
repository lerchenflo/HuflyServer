# Hufly server

Spring Boot backend for the Hufly stable management app. Client: `../hufly` (Kotlin Multiplatform). Requirements: `../hufly/REQUIREMENTS.md` (IDs like HOR-3; cite them in tests and commits). Package root: `com.lerchenflo.hufly.server`. Deferred work: `TODO.md`.

## Non-negotiable rules

- **Test-driven development.** Use the `test-driven-development` skill for every feature and bugfix. No production code without a failing test first. Run `./gradlew test` before calling work done.
- **Follow the SchneaggchatV3server conventions** (`../SchneaggchatV3server`). Use its skills: `schneaggchatv3server` (architecture map), `schneaggchat-add-feature`, `schneaggchat-add-sync-endpoint`, `schneaggchat-add-realtime-push`, `schneaggchat-security-check` (run after every new endpoint), `schneaggchat-flow-stability`. Translate package names to `com.lerchenflo.hufly.server`.
- **Client contract.** DTO field names must match the client's `*Dto` classes in `../hufly`. Change both sides together.
- **Offline first clients.** Every synced entity has `updatedAt`, `updatedBy`, `deleted` (soft delete) so clients can delta-sync. Small collections sync via `IdTimeStamp` lists (`core/sync`); growing ones (events, invitations, horse log, tasks, paddock assignments) via a per-collection `version` counter. See `TODO.md` step 3.
- **Interface first, fakes over mocks.** Repositories extend Spring Data `Repository` (not `MongoRepository`) and declare only the methods in use, so tests use small in-memory fakes from `src/test/.../repository/`.
- **Stables are separate user bases.** Every user belongs to exactly one stable (`User.stableId`). Every query filters by the requester's `stableId`; nobody, stable admins included, can read or change another stable's data. The admin (`Stable.adminUserId`) bypasses role tags; everyone else is gated by the permissions of their `USER_ROLE` tags. An EDIT permission includes its VIEW permission (`Permission.impliedView`).
- **Domain model.** The agreed UML lives in `docs/domain-model.html` (model JSON in its `<script id="model">` block). Open it in a browser to view or edit; "Save to file" writes edits back. Implement from it and keep it in sync.
- Ask when a requirement is unclear. Park deferred items in `TODO.md`. Few comments; only for genuinely non-obvious code.
- **Client/server split.** Client sessions never edit this repo. They write required server changes into `SERVER_CHANGES.md` here (endpoint, method, request/response DTOs with exact field names, auth requirements, error cases). A server session implements entries TDD-style, then removes them from the file. Check `SERVER_CHANGES.md` at the start of every server session. The reverse direction: server sessions never edit the client either; they write required client changes into `../hufly/TODO.md`.

## Layout

| Package | Content |
|---|---|
| `authentication/` | `AuthController` (`/auth/login`, `/auth/refresh`, `/auth/logout`), `AuthService`, `model/RefreshToken` |
| `core/` | `parseObjectId` (400 on malformed ids), `MAX_EPOCH_MILLIS` (bound for epoch-ms request fields), `ClockConfig` (inject `java.time.Clock`, never call `Instant.now()` directly) |
| `core/access/` | `AccessService`: `requester(requireAuth())` (401 for deleted users), `isAdmin`, `effectivePermissions`, `requireAdmin`, `requirePermission` (403) |
| `core/sync/` | Version sync for growing collections: `SyncCollection`, `VersionCounterService.withVersion { }` (wrap every save, soft deletes included) and `safeWatermark`, generic `versionSync(...)`. IdTimeStamp sync for small ones: `IdTimeStamp`, `SyncResponse`, generic `deltaSync(...)`, `requireValidSyncRequest` (page_size max 1000, at most 10 000 client entries) |
| `core/security/` | `SecurityConfig`, `JwtAuthFilter`, `JwtService`, `HashEncoder`, `requireAuth()` |
| `repository/` | All Spring Data Mongo repositories (`@EnableMongoRepositories` base package) |
| `user/` | `UserController` (`GET/PUT /users/me`, `POST /users/me/password`, `POST /users/sync`, admin: `POST /users`, `PUT/DELETE /users/{id}`, `POST /users/{id}/password-reset`), `UserService`, `model/` |
| `horse/` | `HorseController` (`POST /horses/sync` for every member; admin: `POST /horses`, `DELETE /horses/{id}`; HORSE_EDIT: `PUT /horses/{id}`; HORSE_MEDICATION_EDIT: `PUT /horses/{id}/medications`; medications are null in responses without HORSE_MEDICATION_VIEW), `HorseService`, `model/{Horse,Medication,HorseResponse}` |
| `foodplan/` | `FoodPlanController` (FOODPLAN_EDIT: `POST /foodplans`, `PUT/DELETE /foodplans/{id}`, `POST /foodplans/{id}/copy`; `POST /foodplans/sync` for everyone), `FoodPlanService` (also `PUT /horses/{id}/foodplan`), `model/{FoodPlan,FoodPlanEntry,MealSlot,FoodPlanResponse}` |
| `horselog/` | `HorseLogController` (HORSE_LOG_WRITE: `POST /horselog`, `PUT/DELETE /horselog/{id}`; `GET /horselog/sync?since=` version sync for everyone), `HorseLogService`, `model/{HorseLogEntry,HorseLogEntryResponse}` |
| `task/` | `TaskController` (TASK_EDIT: `POST /tasks`, `PUT/DELETE /tasks/{id}`; assignee or TASK_EDIT: `POST /tasks/{id}/done`; `GET /tasks/sync?since=` version sync, all tasks with TASK_VIEW, else own), `TaskService`, `model/{StableTask,TaskResponse}` |
| `stable/` | `StableLookupService`, `model/{Stable,StableResponse}` |
| `tag/` | `TagController` (`POST /tags/sync`, admin: `POST /tags`, `PUT/DELETE /tags/{id}`), `TagService`, `model/{Tag,TagType,Permission,TagResponse}` |

New feature: package `<feature>/` with `Controller`, `Service`, optional `LookupService`, `model/` (entity + `Response` DTO + `toXResponse()`), repository in `repository/`.

## Auth

- Access token: JWT HS256, 15 min. Refresh token: JWT, 30 days, rotated on every `/auth/refresh`; only its SHA-256 hash is stored (`refreshTokens`).
- All routes except `/auth/**` need `Authorization: Bearer <access token>`; missing/invalid answers 401.
- Login by email + password. Emails are globally unique, stored trimmed and lowercase (`normalizeEmail`). Deleted users cannot log in.
- Every handler starts with `accessService.requester(requireAuth())` and filters by `requester.stableId`. Timestamps in responses are epoch milliseconds.
- Passwords: BCrypt via `HashEncoder`.

## Commands

- Tests: `./gradlew test`. Mongo integration tests (`repository/mongo/`, Testcontainers) run when Docker is up and skip otherwise. Version-sync queries need one there: derived queries with two conditions on `version` fail in real Mongo.
- Docker CLI lives in `~/.docker/bin` (not on PATH): `export PATH="$HOME/.docker/bin:$PATH"`.
- Run locally: `cp .env.example .env`, fill in, `docker compose up --build -d`. Server on :8080, mongo-express (no auth, local only) on :8081.
- `BOOTSTRAP_*` in `.env` creates a first stable and admin at startup if the email is unknown (`stable/StableBootstrap`), until the onboarding website exists.
