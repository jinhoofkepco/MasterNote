# Known issues

## Reader/Ink v0.1

- Physical S Pen latency and the 30-minute memory soak are not yet recorded because a tablet
  was not connected during baseline creation.
- PDF rendering depends on AndroidX PDF `1.0.0-alpha19`; it is isolated behind
  `SinglePagePdfView` and `PdfViewportAdapter`, but upgrades require page-lock and tile tests.
- Legacy `AtomicFile` JSON is retained only for one-time Room import. A corrupt legacy JSON file
  still imports as an empty document; new Room payload corruption is isolated per stroke.
- Undo/redo changes are persisted as normal operations, but the navigation stacks themselves
  are not reconstructed after process death. Durable cross-session undo-stack semantics remain
  a follow-up.
- There is no migration path yet because Room schema v1 is the initial schema. Every future
  schema version requires an explicit migration and device migration test.

## Diagnostics

Debug builds enable StrictMode thread and VM logging. It is log-only so framework or library
violations remain visible without changing user behavior. Any new MasterNote main-thread disk
I/O is a release blocker.
