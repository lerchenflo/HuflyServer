# Pending server changes (written by client sessions)

Client sessions append required server work here; a server session implements each entry (TDD), then deletes it. Format per entry: endpoint, method, request/response JSON with exact field names, auth requirement, error cases.

## Recurring tasks: other assignees for a single date (holiday cover)

Client: since 2026-10-04 (schema 17) one date of a task series can name its own assignees instead of the series' ones (e.g. a holiday cover). Builds on the per-date task changes (TSK-5, `TaskOccurrence`). Until the server ships this, the unknown field is ignored on PUT and the date keeps the series' assignees after the next pull. Domain-model doc: add `assigneeUserIds` (nullable list) to TaskOccurrence.

### `PUT /tasks/{taskId}/occurrences/{occurrenceDueAt}`
- Request (`TaskOccurrenceRequest`) gets `"assigneeUserIds": ["<userId>", ...] | null`. `null` (or missing) = the date uses the series' assignees; a list replaces them for that date only. Like the other fields the PUT replaces the date's whole change; the tick stays.
- Same auth as today (TASK_EDIT).
- Validation -> 400 ("Unknown or missing assignee", same check as `requireAssignees`): an empty list; an id that is not a user of the requester's stable; duplicate ids.
- Store as `assigneeUserIds: List<ObjectId>?` on `TaskOccurrence`.

### Response / sync
- `TaskOccurrenceResponse` (PUT answer, `/done` answer, `GET /taskoccurrences/sync` entries) gets `"assigneeUserIds": [...] | null`.

### Effective assignees and visibility (members without TASK_VIEW)
- Effective assignees of a date = its `assigneeUserIds` if set, else the series' `assigneeUserIds`.
- `POST /tasks/{taskId}/occurrences/{occurrenceDueAt}/done`: allowed for the date's effective assignees (or TASK_EDIT). A series assignee replaced on that date gets 403; the stand-in may tick even if not a series assignee.
- `GET /tasks/sync` without TASK_VIEW: a task is visible if the requester is a series assignee OR is named in `assigneeUserIds` of any non-deleted `TaskOccurrence` of it (the client needs the series to show the covered date). The client itself hides the dates the member is not assigned to.
- `GET /taskoccurrences/sync` without TASK_VIEW: dates of every task visible by the rule above (as today: all dates of visible series; a replaced series assignee still gets the date row and hides it client-side).
- When a PUT changes a date's `assigneeUserIds` so that a user newly sees or no longer sees the series (stand-in not otherwise assigned), apply the same handling as the series assignee change in `TaskService` (line ~99: new viewers must pull the task and its older dates, removed ones get them as deleted).

## Meal times per stable (admin setting)

User decision 2026-10-04: the start of each meal slot is a per-stable setting of the admin. Defaults Morgens 06:00, Mittags 12:00, Abends 18:00, Nachts 22:00. The night slot runs over midnight until the morning one starts. Client (since 2026-10-04): stores the times locally (offline first), the admin edits them in Stall -> "Fütterungszeiten", the edit is queued and pushed; "Heute" picks the current feeding from them. Until the server ships this, the client treats 404 on both endpoints as "not supported yet": everyone sees the defaults (or the admin's local edit, which stays queued), no error is shown.

- `Stable` gains an embedded `mealTimes: MealTimes(morning: String, lunch: String, dinner: String, night: String)` (24 h `"HH:mm"`, keys named after the `MealSlot` enum). Kotlin default `MealTimes("06:00", "12:00", "18:00", "22:00")`, so existing stable documents without the field read as the defaults (no migration).
- `GET /stables/me/mealtimes` - any authenticated user, for their own stable. Response `MealTimesResponse`:
  `{"morning":"06:00","lunch":"12:00","dinner":"18:00","night":"22:00","updatedAt":1791800000000,"updatedBy":"65f1c0ffee0000000000aaaa"}` (`updatedAt` epoch millis and `updatedBy` user id hex of the stable document).
- `PUT /stables/me/mealtimes` - body `{"morning":"05:30","lunch":"11:00","dinner":"17:00","night":"21:30"}`, all four required. Admin only (`accessService.requireAdmin`) else 403. Validation -> 400 (`ResponseStatusException`): a value not matching `^([01]\d|2[0-3]):[0-5]\d$`; times not strictly increasing `morning < lunch < dinner < night` (all within one day, so the night slot cannot start after midnight). Last write wins (no `expectedUpdatedAt`); saves the stable with `updatedAt = now`, `updatedBy = requester`. Response: the same `MealTimesResponse`.
- Realtime: saving the `Stable` already emits the `"stable"` change hint (`ChangeListener`); the client's meal-times syncer listens on `"stable"`, so nothing else is needed.
- Optional, not used by the client: `mealTimes` in `StableResponse` (`/users/me`).
- `docs/domain-model.html`: add `mealTimes` to Stable.
- Tests the client relies on: a stable without the field answers the defaults; a member's PUT is 403 and changes nothing; `night` "00:30" or `lunch` equal to `morning` is 400; after the admin's PUT another member's GET returns the new times.
