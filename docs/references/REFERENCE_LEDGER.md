# Reference ledger

Only references that influence shipped code or architecture are listed. Each adapted source
must be pinned, licensed, isolated behind a MasterNote-owned API, and validated locally.

## REF-UI-001

- Capability: radial S Pen tool placement and spring animation
- Source: `skydoves/compose-animations`
- Commit: pending historical pin audit (existing code predates this ledger)
- Path: `AnimationExample14.kt`
- License: Apache-2.0
- Adoption: adapted placement/animation pattern
- MasterNote target: `feature/reader/ReaderChrome.kt`
- Validation: compile and device interaction tests pass
- Differences: application actions, state, colors, controls, and layout are MasterNote-specific

## REF-PDF-001

- Capability: AndroidX single-page PDF loading and bitmap sources
- Source: AndroidX PDF artifacts
- Version: `1.0.0-alpha19`
- Commit: release source pin to be recorded before dependency upgrade
- License: Apache-2.0
- Adoption: direct dependency behind `SinglePagePdfView` and `PdfViewportAdapter`
- Validation: tile unit tests and API 36 page-lock instrumentation test pass

PR 1 adds the Room and Ink storage records with exact upstream revisions and validation notes.
