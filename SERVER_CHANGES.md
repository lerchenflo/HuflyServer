# Pending server changes (written by client sessions)

Client sessions append required server work here; a server session implements each entry (TDD), then deletes it. Format per entry: endpoint, method, request/response JSON with exact field names, auth requirement, error cases.

## Events: the organiser can take part ("Ich nehme auch teil", client 2026-10-06)

The event editor has a switch "Ich nehme auch teil". It is a real invitation row for the event's creator, so per-date declines, "N zugesagt", conflicts and calendar roles work as for anyone else.

- `POST /events/{eventId}/invitations` `{ userIds, occurrenceStartAt? }`: stop skipping the creator (`EventService.invite` today skips "already invited users and the creator"). When `userIds` contains the creator and `occurrenceStartAt` is null, create their invitation with `status = "ACCEPTED"` and `respondedAt = now` (no PENDING step, no invitation push to themselves). The creator invited to a single date only (`occurrenceStartAt` set) stays refused/skipped - the switch is for the whole event or series.
- `DELETE /eventinvitations/{id}` of the creator's own invitation: allowed for the creator and the admin (it is how the switch is turned off).
- Series: the creator's series invitation counts as accepted on every date that has no own answer; the creator answers single dates like any invitee via the per-date answer endpoint (decline one date, accept it again). `POST /eventinvitations/{id}/answer` on a series invitation stays refused ("Event series: answers per date only"), also for the creator. Server-side counts (if any, e.g. digest) treat the creator's dates without answer as accepted.
- Visibility unchanged (the creator sees the event anyway). Responses/delta pull: the creator's invitation is an ordinary `EventInvitationResponse` row.
- Notifications: no `event_invitation` push for the creator's own invitation; their own per-date answers notify nobody (they are the organiser).

## Permissions: HORSE_EDIT_OWN - owners handle their own horses (client 2026-10-06)

New grantable permission on USER_ROLE tags: `HORSE_EDIT_OWN` (same JSON spelling as the other permissions; the "Einsteller" preset gets it). It grants, **only for horses whose `ownerUserId` is the requester**, what these permissions grant for every horse: `HORSE_EDIT`, `HORSE_MEDICATION_VIEW`, `HORSE_MEDICATION_EDIT`, `HORSE_LOG_WRITE`, `FOODPLAN_EDIT` (see below), `PADDOCK_PLAN` (see below). It never grants anything for horses of others, and never horse create/delete (admin-only as before).

- Horses `PUT /horses/{id}` (and picture upload/delete, medications): allowed with `HORSE_EDIT_OWN` when the stored horse's `ownerUserId` == requester. Such a requester may not change `ownerUserId` (403 "Only HORSE_EDIT may change the owner"). Changing `foodPlanId` of the own horse is allowed (repointing to any existing plan of the stable).
- Reads: medications of own horses are sent to `HORSE_EDIT_OWN` holders (today they need `HORSE_MEDICATION_VIEW`); others' horses unchanged.
- Horse log `POST/PUT/DELETE` of entries: allowed with `HORSE_EDIT_OWN` when the entry's horse (stored and new) is the requester's own.
- Food plans:
  - `POST /foodplans` (create, used to copy a plan): allowed with `HORSE_EDIT_OWN` when the requester owns at least one horse. Store the creator (`createdByUserId`) if not stored yet.
  - `PUT /foodplans/{id}` (name, entries): allowed with `HORSE_EDIT_OWN` when every horse currently on the plan is the requester's own and there is at least one, or the plan has no horses and the requester created it.
  - `DELETE /foodplans/{id}`: unchanged (`FOODPLAN_EDIT`).
- Paddock assignments `POST/PUT/DELETE /paddockassignments` and ending them: allowed with `HORSE_EDIT_OWN` when every horse of the assignment - stored and new - is the requester's own, and it has no `groupIds` (groups mix owners). Horse groups and conflicts stay `PADDOCK_PLAN`.
- Errors: 403 with the usual message when the horse/plan/assignment involves a horse the requester does not own.

## Event series: take a series invitee off one date (client 2026-10-06)

The organiser can now remove a series invitee from a single date (before, they could only decline it themselves). It is part of the date's change, not an answer.

- Occurrence change (`EventOccurrenceResponse` in responses/delta pull, `PUT /events/{eventId}/occurrences/{occurrenceStartAt}` body): new optional field `removedUserIds: List<String>?` - user ids of series invitees not taking part on this date. The client leaves the field out when there are none; as the PUT replaces the date's whole change, a missing field means none.
- Validation: each id must belong to a series invitation (`occurrenceStartAt == null`) of the event (400 "Not invited to the series" otherwise); the creator may be listed (they take part via their own invitation).
- Effects on the server: for that date the listed users are not participants - no invitation/answer pushes for that date, per-date answers of theirs for that date are kept but ignored, `POST /eventoccurrences/.../answer` for that date by a removed user is refused (400 "Not invited to this date"). Taking them off again (field without them) restores them for that date.
- Auth: unchanged (EVENT_EDIT and creator, or admin, as for other date changes). Visibility: a removed series invitee still sees the series and this date (they are invited to the series).
