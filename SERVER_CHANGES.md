# Pending server changes (written by client sessions)

Client sessions append required server work here; a server session implements each entry (TDD), then deletes it. Format per entry: endpoint, method, request/response JSON with exact field names, auth requirement, error cases.

## Stable admin must choose an own password on first login

- Where: `StableOnboardingService.createStable` (operator website and dev bootstrap).
- Change: save the new admin with `mustChangePassword = true`, same as members created by the admin (`UserService.createUser`). The operator sets the admin's first password, so the admin should replace it like every other member.
- Contract unchanged: `GET /users/me` already returns `mustChangePassword`; the app already shows the "Eigenes Passwort wählen" prompt for any session with it set, admins included. `POST` password change (`UserService.changePassword`) already clears it.
- Dev bootstrap (`StableBootstrap`): may keep `mustChangePassword = false` for the seeded dev admin so local testing is not prompted on every fresh DB - pass a flag if needed.
- Tests: creating a stable yields an admin with `mustChangePassword = true`; `/users/me` for that admin returns `true` until the password is changed.
