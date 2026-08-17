# MasterNote V2 redesign

Baseline: `e6764c1`

## Persisted boundaries

- A `Book` owns an app-private PDF copy and belongs to one `Student`.
- Annotation data is partitioned by `(bookId, pageNumber)`.
- `operations.log` is append-only during editing. `checkpoint.json` only accelerates loading.
- A parse or format-version failure quarantines the affected file with a `.corrupt-*` suffix. The editor is disabled for that page and never writes an empty replacement.
- Undo and redo are process-memory state. Remote operations are not inserted into those stacks.
- Submitted attempts remain present but are hidden from students. The next stroke lazily creates the next attempt.
- Deletes in library and grading metadata are represented by hidden timestamps.

## Reader input contract

- Finger input is left to the PDF viewport for pan and zoom.
- Reader actions use `PenTapButton`: stylus down and stylus up must both be inside the component.
- Only previous/next navigation remains permanently visible. Other document actions are in the folded top menu.
- The S Pen side button opens the existing radial tool palette.
- Hover renders the selected pen or eraser footprint at the pen tip.

## LAN boundary

- Student tablet is the NSD-advertised TCP server; teacher is the client.
- Only the page selected by the teacher is subscribed.
- Durable payloads are encoded append-log records and are de-duplicated by operation ID.
- Student operations are flushed after two seconds, at stroke boundaries, with a five-second upper bound; page changes flush immediately.
- Teacher strokes remain local drafts until publication.

## Required physical acceptance still pending

The automated suite cannot substitute for these hardware checks:

- Samsung S Pen hover/button/palm behavior.
- Two-device NSD discovery, disconnect/offline merge, and 2–5 second latency.
- Physical two-device QR scan and remembered-device reuse still need device validation.
- 200-page and 500-stroke frame-time measurements on the target tablet.
