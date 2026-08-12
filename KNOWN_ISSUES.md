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
- Room migrations v1 → v2 → v3 are explicit and covered with exported-schema migration tests.
  Every future schema version still requires a non-destructive migration and device test.

## Teacher review v0.3

- Physical BiometricPrompt success/cancel/device-credential fallback and S Pen review have not
  been run because only an Android 16 ARM64 emulator was connected.
- A physical tablet 30-minute review soak and update-install certificate check remain release gates.
- The first tablet layout uses a stable fixed-width/bottom supporting pane. Material3 Adaptive is
  intentionally deferred so this change does not force a navigation migration.

## Diagnostics

Debug builds enable StrictMode thread and VM logging. It is log-only so framework or library
violations remain visible without changing user behavior. Any new MasterNote main-thread disk
I/O is a release blocker.
