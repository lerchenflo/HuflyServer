# Pending server changes (written by client sessions)

Client sessions append required server work here; a server session implements each entry (TDD), then deletes it. Format per entry: endpoint, method, request/response JSON with exact field names, auth requirement, error cases.

## Absences (Abwesenheiten) (client 2026-10-06)

New collection `absences`, synced like `notes` (offline-first client, version pull, change hints with collection `absence`).

- Model: `{ id, stableId, userId, from: "YYYY-MM-DD", until: "YYYY-MM-DD" (both days included), note: String (max 500, may be empty), createdByUserId, updatedAt, updatedBy, version, deleted }`.
- `GET /absences/sync?since=<version>` - version pull like `/notes/sync` (`updatedEntries: [AbsenceDto]`, `deletedEntries: [id]`); every member of the stable sees all absences of the stable.
- `POST /absences` body `{ userId, from, until, note, clientId? }` -> `AbsenceDto` (201). `clientId` makes a retried create return the existing row.
- `PUT /absences/{id}` body `{ userId, from, until, note }` -> `AbsenceDto`.
- `DELETE /absences/{id}` -> 204 (soft delete for the pull).
- Auth: the requester may write an absence when `userId` is themselves, or they have TASK_EDIT, or are the admin (for PUT/DELETE: of the stored and the new `userId`). 403 otherwise.
- Validation: `until >= from` (400 "End before start"), `userId` a user of the stable (400), note length (400).
- A user removed from the stable: their absences are deleted.
- No pushes (warn-only in the app: the task editor and "Heute" show who is away).
