# Known issues

## Reader/Ink v0.1

- The current `AtomicFile` JSON store rewrites the entire document after every operation.
  It is safe for the current prototype but does not scale to thousands of strokes. PR 1
  replaces it with Room transactions and an operation log.
- Debug StrictMode reports a main-thread disk read in `AtomicAnnotationStore` construction
  because its annotation directory is checked and created from `ReaderViewModel` initialization.
  PR 1 must create/open persistence and import legacy files from `Dispatchers.IO`.
- A finished stroke is represented by canonical sampled points rather than the official
  `ink-storage` payload. PR 1 introduces the stable Ink 1.0.0 stream codec.
- Physical S Pen latency and the 30-minute memory soak are not yet recorded because a tablet
  was not connected during baseline creation.
- PDF rendering depends on AndroidX PDF `1.0.0-alpha19`; it is isolated behind
  `SinglePagePdfView` and `PdfViewportAdapter`, but upgrades require page-lock and tile tests.
- Corrupt legacy JSON currently falls back to an empty document. PR 1 isolates corruption per
  stroke so one bad payload does not hide other pages.
- Undo/redo is in-memory state that is serialized as a whole document. PR 1 preserves active
  state transactionally; durable cross-session undo-stack semantics remain a follow-up.

## Diagnostics

Debug builds enable StrictMode thread and VM logging. It is log-only so framework or library
violations remain visible without changing user behavior. Any new MasterNote main-thread disk
I/O is a release blocker.
