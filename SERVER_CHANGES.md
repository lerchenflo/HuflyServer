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
