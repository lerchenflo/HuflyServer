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
