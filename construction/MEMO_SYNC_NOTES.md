# Memo construction integration

## User-visible contract

- One optional construction board belongs to each memo UUID. A board may contain many points, segments, circles, constraints and measurements.
- The expanded memo fills the usable screen. Construction and handwriting are transparent layers on the SAME paper, with one shared zoom/pan transform; writing on a circle stays on it during camera changes.
- Select handwriting/eraser or a construction tool to choose the active editing layer. Editing, deletion and undo never fall through to the other layer. Fingers pan in handwriting mode; two fingers pan/pinch in either mode. A stylus-owned gesture ignores palm pointers. Paper/view boundary exits finish once and consume the remainder.
- Handwriting retains its original normalized coordinates and 2.2 paper aspect. Canonical `(0,0)..(1000,2200)` maps uniformly to mathematical `(-3,24)..(27,-42)` cm, identically on every device. Neither ink nor geometry is rewritten on camera change. This is a finite paper, not an infinite-ink format migration. Older geometry outside the paper remains visible/editable on the surrounding gray space; handwriting stays bounded to its original paper.
- Content fit uses both ink and geometry without moving document points. Remote changes and subsequent geometry reloads do not reset the camera. Measurement guides and values use subdued blue, separate from main entity colors.
- Native wet ink is screen-coordinate data until handoff: pending render handoffs, persistence and pending UI dry-snapshot application fence zoom/resize/reparent. Mode switches cancel the active input; completed strokes are retired only after the durable dry snapshot is visible.
- Students automatically send committed construction edits, not transient solver previews. Teachers edit a local draft and must press Publish to send geometry to the student.
- Publish queries the student's latest version first. Divergence from the common base produces the two explicit choices: use the teacher drawing on the student, or use the student drawing on the teacher. Either choice performs a new comparison; stale choices never force an overwrite.
- Student storage uses generation + revision + digest compare-and-swap. Publication is complete only after the matching durable application RESULT, not a socket write or Telegram receipt. Editing a new teacher draft during an outstanding publication remains possible.

## Persistence and compatibility

`construction-replicas-v1` is inside the existing backup root. Each atomic record contains the scene, attachment flag, student shadow, common base, pending publication, previous-scene recovery and durable publication receipts. Existing same-target `construction-scenes-v1` data is imported once without deleting its original file. Existing memo/ink files are not migrated or rewritten by geometry operations.

The restore generation is stored outside the replaceable data root. Ordinary application restarts preserve pending requests and receipts. A restored student data root invalidates old publication expectations. A deleted memo is a parent tombstone and late geometry must not recreate it.

## Transport

- LAN: negotiated `MEMO_CONSTRUCTION_V1`, authenticated book/device/role/session, bounded 128 KiB chunks, a maximum 4 MiB packet and connection-generation fenced sends.
- Telegram: separate `MEMO_CONSTRUCTION` payload, exact current pairing and existing page-token/workbook-token mapping. Bounded 512 KiB chunks fit inside the existing encrypted-document limits. The existing remote-review decoder does not consume these documents.
- Application request IDs survive retries. A durable outer Telegram delivery attempt is resumed while any chunk is pending or unacknowledged; only a finished/terminal attempt gets a new delivery ID. This avoids replacing the unsent suffix on slow connections and repairs a request whose transport ACK arrived but application RESULT was lost. Student-side receipts make repeated application requests idempotent. Transport fragments older than one day are acknowledged and discarded; retained replica requests/scenes can retry them, without changing saved geometry.
- An unresolved page/memo mapping is deferred, never guessed from matching PDF hashes. A publication is pinned to the authenticated peer/path used for its comparison; independently paired LAN and Telegram identities are not silently treated as the same student. If that path disconnects mid-publication, its result remains pending until that peer/path is available again.
- General-page construction (attempt 0) remains local-only. This integration synchronizes memo-attached boards with real attempt IDs.

## Verification boundary

Host tests cover replica conflict/restore/idempotence, embedded editor behavior, shared-canvas alignment and gesture isolation, bounded packet assembly and peer-role checks. Native graphics previews exercise the real geometry editor with a canonical ink fixture; Android Ink's native authoring runtime is not available to ordinary desktop JVM tests. Full memo/native-ink instrumentation is kept separately for an isolated device run, never run destructively on a user's tutoring device. End-to-end two-device LAN/Telegram publication must additionally be exercised on both updated devices.

No dependency or solver was replaced: existing Apache Commons Math 3.6.1 construction code and its provenance remain unchanged. This update is integration/transport code, not a new mathematical solver.
