# Pending server changes (written by client sessions)

Client sessions append required server work here; a server session implements each entry (TDD), then deletes it. Format per entry: endpoint, method, request/response JSON with exact field names, auth requirement, error cases.

## Recurring events and recurring tasks (series, per-date changes, per-date answers and ticks)

Client: since 2026-10-04 the app creates series offline and expands their dates itself. Until the server ships this, a series reaches the server as its first date only (the unknown `recurrence` field is ignored) and every per-date write is rejected (404) and dropped by the client, so nothing breaks, but series do not sync. Domain-model doc: please add the `recurrence` object to Event and StableTask and the three new entities below (EventOccurrence, EventOccurrenceAnswer, TaskOccurrence) to `docs/domain-model.html` and replace the "Recurring ... deferred" notes. Requirement: events and tasks of a stable can repeat (weekly lessons, daily chores).

### 1. The `recurrence` object (events and tasks)

```json
"recurrence": {
  "frequency": "WEEKLY",
  "interval": 1,
  "weekdays": ["TUESDAY", "THURSDAY"],
  "until": "2026-12-31",
  "count": null,
  "timeZone": "Europe/Vienna"
}
```
- `frequency`: `"DAILY"` | `"WEEKLY"`. `interval`: every N days/weeks, 1..99 (default 1). `weekdays`: `java.time.DayOfWeek` names, distinct, WEEKLY only (DAILY: must be empty); empty = weekday of the first date (default `[]`). `until`: optional ISO local date, inclusive, in `timeZone`. `count`: optional 1..999, number of dates including the first. At most one of `until`/`count`; neither = open-ended. `timeZone`: IANA zone id the series is expanded in (the creating device's zone; kept on later edits).
- Store on `Event` and `StableTask` as an embedded nullable document `recurrence` (null = single event/task). Add `recurrence` (nullable) to `EventResponse` and `TaskResponse` and accept it in `EventController.EventRequest` and `TaskController.TaskRequest` (`recurrence: RecurrenceRequest? = null`). On PUT a missing/null `recurrence` makes it a single event/task (the client always sends the full state and omits the field when it is null).
- The series' `startAt`/`endAt` (events) or `dueAt` (tasks) are those of its first date (the "anchor"); every date lasts `endAt - startAt`.
- Validation -> 400 (`ResponseStatusException` like the other checks): unknown `frequency`; `interval` outside 1..99; unknown or duplicate weekday names; weekdays with DAILY; both `until` and `count`; `count` outside 1..999; `until` unparsable or before the anchor's local date in `timeZone`; `timeZone` not a valid `ZoneId`.

### 2. Date keys and expansion (must match the client exactly)

A date of a series is identified by its ORIGINAL start in epoch millis: `occurrenceStartAt` (events) / `occurrenceDueAt` (tasks). It stays the key when that date is moved. The server validates keys with the client's expansion:
- `zone = ZoneId.of(timeZone)`, `anchor = LocalDateTime.ofInstant(startAt, zone)` (tasks: `dueAt`).
- DAILY: candidate dates `anchor.date + k*interval` days, k = 0, 1, 2, ...
- WEEKLY: `monday = anchor.date.with(previousOrSame(MONDAY))`; for week offsets w = 0, interval, 2*interval, ...: each chosen weekday (empty = the anchor's weekday) of the week starting `monday + w*7` days, ascending; candidates before `anchor.date` are skipped (the first date can be later than the anchor when its weekday is not chosen).
- A candidate's start = `ZonedDateTime.of(LocalDateTime.of(date, anchor.toLocalTime()), zone).toInstant()` (java.time moves a time inside a DST gap forward by the gap and takes the earlier offset in an overlap; the client does the same).
- `until`: no candidate after that local date. `count`: only the first `count` candidates exist (counted from the first generated date).
- `isOccurrence(key)` = the key equals one of these starts (scan until a start exceeds the key). A key the current rule no longer produces (e.g. the series time changed later) is ignored by clients; the server keeps such rows and need not clean them up.

### 3. Event dates: `EventOccurrence` (Mongo `eventOccurrences`)

Document: `id, stableId, eventId, occurrenceStartAt: Instant, cancelled: Boolean, title: String?, description: String?, startAt: Instant?, endAt: Instant?, horseIds: List<ObjectId>?, updatedAt, updatedBy, deleted, version`. Unique index `{eventId: 1, occurrenceStartAt: 1}` plus the usual `{stableId: 1, version: 1}`. Null override fields keep the series' value.

- `PUT /events/{eventId}/occurrences/{occurrenceStartAt}` (path value: epoch millis) - upsert by (eventId, occurrenceStartAt), replaces the whole change; idempotent, so no `clientId`.
  Body (all keys always sent, null = keep the series' value; all null with `cancelled=false` restores the date): `{"cancelled":false,"title":"Springstunde","description":null,"startAt":1791800000000,"endAt":1791803600000,"horseIds":["65f1c0ffee0000000000aaaa"]}`
  Response `EventOccurrenceResponse`: `{"id","stableId","eventId","occurrenceStartAt","cancelled","title","description","startAt","endAt","horseIds","updatedAt","updatedBy","version"}` (times epoch millis, nulls included).
  Auth: like editing the event (EVENT_EDIT and creator, or admin) else 403. Errors: event unknown/deleted/other stable 404; event without recurrence 400; key not an occurrence of the current rule 400; `title` set but blank or > 200; `description` > 5000; only one of `startAt`/`endAt` set; `endAt <= startAt`; times outside 0..MAX_EPOCH_MILLIS; `horseIds` set but empty, > 50 or not horses of the stable -> 400.
- `GET /eventoccurrences/sync?since=&page_size=` - version sync (`VersionSyncResponse<EventOccurrenceResponse>`), visibility exactly like `/events/sync` (EVENT_VIEW, creator, or invited to the event including invitations to single dates, see 5).
- Deleting an event soft-deletes its occurrences (tombstones in `deletedEntries`).

### 4. Per-date answers: `EventOccurrenceAnswer` (Mongo `eventOccurrenceAnswers`)

Document: `id, stableId, eventId, invitationId, userId, occurrenceStartAt: Instant, status: ACCEPTED|DECLINED, respondedAt, updatedAt, updatedBy, deleted, version`. Unique index `{invitationId: 1, occurrenceStartAt: 1}`. A per-date answer wins over the series invitation's own status for that date; the series invitation's status (set with the existing `POST /eventinvitations/{id}/answer`, the app's "Allen Terminen der Serie zusagen") is the default for every date not answered on its own. Answering the series does NOT touch per-date answers.

- `PUT /eventinvitations/{invitationId}/occurrences/{occurrenceStartAt}/answer`, body `{"accepted":true}` - upsert by (invitationId, occurrenceStartAt). Response `EventOccurrenceAnswerResponse`: `{"id","stableId","eventId","invitationId","userId","occurrenceStartAt","status","respondedAt","updatedAt","updatedBy","version"}`.
  Auth: only the invitee (`invitation.userId == requester`) else 403. Errors: invitation unknown/deleted 404; invitation is itself for a single date (`occurrenceStartAt != null`; answered with the normal answer endpoint) 400; event without recurrence 400; key not an occurrence 400; date cancelled 409.
- `GET /eventoccurrenceanswers/sync` - version sync, visibility exactly like `/eventinvitations/sync`.
- Removing an invitation or deleting the event soft-deletes its answers.

### 5. Invitations to a single date (stand-in for a decliner)

- `EventInvitation` gains `occurrenceStartAt: Instant?` (null = the event / whole series); `EventInvitationResponse` gains `"occurrenceStartAt": 1791800000000` or `null`.
- `POST /events/{eventId}/invitations` body gains optional `"occurrenceStartAt"` (the client omits it when null): invites those users to that date only. Skip the creator, users with a live series invitation and users already invited to that date. Errors: set on an event without recurrence 400; key not an occurrence 400. The cap counts all live invitations of the event. Live-invitation uniqueness becomes (eventId, userId, occurrenceStartAt). Without the field the endpoint behaves as today (a user invited to single dates may additionally be invited to the whole series).
- A date invitation is answered with the existing `POST /eventinvitations/{id}/answer` and withdrawn with `DELETE /eventinvitations/{id}`. Its invitee sees the whole event through the sync like any invitee.
- Restamp: whenever an invitation makes the event newly visible to someone, re-version the event's occurrences and occurrence answers too (like `restamp(event)` does for the event and its invitations); otherwise the new invitee never pulls the existing date changes.

### 6. Task dates: `TaskOccurrence` (Mongo `taskOccurrences`)

Document: `id, stableId, taskId, occurrenceDueAt: Instant, cancelled, title: String?, comment: String?, dueAt: Instant?, horseIds: List<ObjectId>?, doneByUserId: ObjectId?, doneAt: Instant?, updatedAt, updatedBy, deleted, version`. Unique index `{taskId: 1, occurrenceDueAt: 1}`. One row carries the change and the tick of a date; each endpoint writes only its own fields.

- `PUT /tasks/{taskId}/occurrences/{occurrenceDueAt}`, body `{"cancelled":false,"title":null,"comment":"Mehr Heu","dueAt":1791790000000,"horseIds":null}` - upsert, replaces the change fields, keeps `doneByUserId`/`doneAt`. Auth TASK_EDIT else 403. Errors: task unknown/deleted 404; task without recurrence 400; key not an occurrence 400; `title` set but blank or > 200; `comment` > 5000; `dueAt` outside 0..MAX_EPOCH_MILLIS; `horseIds` > 50 or not of the stable -> 400 (an empty list is allowed: no horses on this date).
- `POST /tasks/{taskId}/occurrences/{occurrenceDueAt}/done`, body `{"done":true}` - upsert; `done=true` sets `doneByUserId = requester`, `doneAt = now`, `false` clears both; keeps the change fields. Auth like `POST /tasks/{id}/done` (an assignee of the series, or TASK_EDIT) else 403. Errors: 404/400 as above; date cancelled 409.
- Response of both: `TaskOccurrenceResponse` `{"id","stableId","taskId","occurrenceDueAt","cancelled","title","comment","dueAt","horseIds","doneByUserId","doneAt","updatedAt","updatedBy","version"}`.
- `GET /taskoccurrences/sync` - version sync, visibility exactly like `/tasks/sync` (TASK_VIEW, or an assignee of the task).
- Deleting a task soft-deletes its occurrences. A task update that adds assignees restamps the task's occurrences so the new assignees pull them. `POST /tasks/{id}/done` on a task with recurrence: 400 (dates are ticked one by one).

### 7. Sync, realtime, permissions

- New `SyncCollection` entries `EVENT_OCCURRENCES("eventOccurrences")`, `EVENT_OCCURRENCE_ANSWERS("eventOccurrenceAnswers")`, `TASK_OCCURRENCES("taskOccurrences")`; every write including soft deletes stamps the next version.
- Realtime `ChangeListener`: `EventOccurrence` -> `notifyStable(stableId, "eventoccurrences")`, `EventOccurrenceAnswer` -> `"eventoccurrenceanswers"`, `TaskOccurrence` -> `"taskoccurrences"` (the client's syncer names).
- Permissions unchanged: EVENT_EDIT (+ creator or admin) creates and edits series and their dates; TASK_EDIT for task series and their date changes; assignees tick dates; VIEW rules as today.
- Tests the client relies on: the same PUT twice keeps one row; another invitee's per-date answer reaches the organiser via sync; a date invitation makes the series visible to its invitee via `/events/sync`; deleting the series delivers tombstones for its dates and answers.

