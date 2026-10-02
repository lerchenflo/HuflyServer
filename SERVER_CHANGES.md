# Pending server changes (written by client sessions)

Client sessions append required server work here; a server session implements each entry (TDD), then deletes it. Format per entry: endpoint, method, request/response JSON with exact field names, auth requirement, error cases.

## Switch login from username to email (USR-4, domain model q1)

Decided in `docs/domain-model.html`: email is globally unique and is the login. The client switches its `LoginRequestDto` to `email` on 2026-10-02; until the server follows, real-server login is broken (demo mode is unaffected).

- `POST /auth/login`
  - Request: `{"email": "...", "password": "..."}` — field `username` replaced by `email`.
  - Response unchanged: `TokenPair {"accessToken": "...", "refreshToken": "..."}`.
  - Auth: none.
  - Errors: 401 on unknown email or wrong password (same behaviour as today).
- `User` document: rename `username` to `email` (globally unique, indexed). `UserResponse.username` becomes `email`.
- `POST /auth/refresh`, `POST /auth/logout`: unchanged.

## Food plans (FOD-1..3, domain model `foodplan` package)

Shared documents: several horses point at the same plan via `Horse.foodPlanId`. Viewing needs no permission; creating and changing needs FOODPLAN_EDIT (admin bypasses). Small collection: IdTimeStamp sync. The client ships its side on 2026-10-02 and tolerates 404s until these exist.

- `POST /foodplans/sync` — same IdTimeStamp contract as `/horses/sync` (query `page`, `page_size`; body `[{ id, timeStamp }]`; response `{ updatedEntries: [FoodPlanDto], deletedEntries: [id], moreEntries }`). Auth: any member of the stable.
- `FoodPlanDto`: `{ id, stableId, name, entries: [{ slot: "MORNING"|"LUNCH"|"DINNER"|"NIGHT", foodTagId, amountComment }], updatedAt: Long (epoch millis), updatedBy }`.
- `POST /foodplans` — body `{ name, entries: [...] }` (same entry shape, no plan id). Auth: FOODPLAN_EDIT or admin (403 otherwise). Answers `FoodPlanDto`. Errors: 400 when `name` is blank or a `foodTagId` is not a FOOD tag of the stable.
- `PUT /foodplans/{id}` — same body and auth, answers `FoodPlanDto`. 404 for an unknown or foreign id.
- `DELETE /foodplans/{id}` — auth: FOODPLAN_EDIT or admin. Soft-deletes the plan and clears `foodPlanId` on all horses of the stable that point at it, bumping those horses' `updatedAt` so clients pull the unlink. Answers empty 200.
- `POST /horses` and `PUT /horses/{id}`: add optional `foodPlanId` (nullable; null unlinks) to the request body. 400 when it is not a food plan of the stable. Auth unchanged (HORSE_EDIT or admin).
