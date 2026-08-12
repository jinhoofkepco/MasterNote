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

## REF-ANNOTATION-001

- Capability: Room repository, coroutine, and test separation pattern
- Source: `android/architecture-samples`
- Commit: `ee66e1526b84c026615df032c705842b7d2a521f`
- Path: `app/src/main/java/com/example/android/architecture/blueprints/todoapp/data/`
- License: Apache-2.0
- Adoption: pattern only; no source copied
- MasterNote target: `annotation/storage`
- Validation: upstream structure inspected; local unit and API 36 instrumentation tests pass
- Differences: local-only operation log, immutable stroke assets, and transactional replacement

## REF-ANNOTATION-002

- Capability: transactional local database and exported schema
- Source: `androidx/androidx` Room 2.8.4 release
- Commit: `75ef81cced187631f0dd74666188bd9d4cd3358f`
- Artifact: `androidx.room:room-runtime:2.8.4`, `room-ktx`, `room-testing`, `room-compiler`
- License: Apache-2.0
- Adoption: direct dependency behind `RoomAnnotationStore`
- Validation: schema v1 exported; transaction rollback and corrupt-row tests pass on API 36
- Differences: MasterNote owns all entities, DAO queries, payloads, and repository behavior

## REF-ANNOTATION-003

- Capability: stable stroke-input serialization
- Source: `androidx/androidx` Ink 1.0.0 release
- Commit: `8bf058367eab7e1352e81f61606d663ed7b3cd1d`
- Artifact: `androidx.ink:ink-storage:1.0.0`, `ink-strokes`, `ink-brush`
- License: Apache-2.0
- Adoption: direct dependency behind `InkStrokeCodec`
- Validation: stream codec round-trip test passes on API 36
- Differences: uses the stable `InputStream`/`OutputStream` API; 1.1 alpha byte-array APIs are excluded

## REF-BUILD-001

- Capability: Kotlin Symbol Processing for Room code generation
- Source: `google/ksp`
- Commit/tag: `50bed184c9ac0dccb09f605a427ce502842c8ead` (`2.3.4`)
- License: Apache-2.0
- Adoption: build-time plugin only
- Validation: clean Room compilation and schema export pass
- Differences: none

## REF-LEARNING-001

- Capability: repository as the single data entry point, Flow exposure, dispatcher injection
- Source: `android/architecture-samples`
- Commit: `ee66e1526b84c026615df032c705842b7d2a521f`
- Paths: `DefaultTaskRepository.kt`, `DefaultTaskRepositoryTest.kt`
- License: Apache-2.0
- Adoption: architecture and test pattern only; no source copied
- MasterNote target: learning repository and use-case tests
- Validation: upstream implementation and tests inspected at the pinned revision
- Excluded: network synchronization, Task domain, and fire-and-forget persistence

## REF-LEARNING-002

- Capability: explicit Room versions, schema export, and migration registration
- Source: `android/nowinandroid`
- Commit: `7d45eae4f8720a0c77f507712ba2437ff974b6ed`
- Paths: `core/database/.../NiaDatabase.kt`, `DatabaseMigrations.kt`
- License: Apache-2.0
- Adoption: schema/version organization pattern only; no source copied
- MasterNote target: `AnnotationDatabase` schema v2 and later migrations
- Validation: upstream database and migration declarations inspected at the pinned revision
- Differences: MasterNote uses a declared v1-to-v2 auto-migration because this revision only adds tables

## REF-LEARNING-003

- Capability: atomic submission transaction
- Source: AndroidX Room `@Transaction` / `withTransaction`
- Version: Room 2.8.4, pinned by REF-ANNOTATION-002
- License: Apache-2.0
- Adoption: direct dependency API behind the learning repository
- Validation: failure injection after submission, stroke-reference, and answer phases

## REF-LEARNING-004

- Capability: exported-schema migration verification
- Source: AndroidX Room `MigrationTestHelper`
- Version: Room 2.8.4, pinned by REF-ANNOTATION-002
- License: Apache-2.0
- Adoption: instrumentation-test dependency only
- Validation: v1 database creation, v2 migration, schema validation, and old-row preservation
