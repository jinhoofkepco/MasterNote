# Memo construction integration

## User-visible contract

- One optional construction board belongs to each memo UUID. A board may contain many points, segments, circles, constraints and measurements.
- The board and handwriting occupy separate clipped panes. A physical pointer gesture cannot transfer ownership to the other pane. Crossing the border ends the active stroke/drag at the border; the rest of that gesture is consumed.
- Wide screens use left/right panes. Narrow portrait memo panels (under 560 dp) use top/bottom panes so neither editing surface is reduced to a narrow strip.
- Handwriting retains its original normalized coordinates and 2.2 paper aspect. Construction retains mathematical world coordinates. Their erase, selection, undo and persistence paths are independent.
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

Host tests cover replica conflict/restore/idempotence, embedded editor behavior, pane pointer boundaries, bounded packet assembly and peer-role checks. Android Ink's native rendering is not available to ordinary desktop JVM tests; full memo/native-ink instrumentation is kept separately for a device run. End-to-end two-device LAN/Telegram publication must additionally be exercised on both updated devices.

No dependency or solver was replaced: existing Apache Commons Math 3.6.1 construction code and its provenance remain unchanged. This update is integration/transport code, not a new mathematical solver.
