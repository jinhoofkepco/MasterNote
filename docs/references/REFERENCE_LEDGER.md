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

## REF-TEACHER-001

- Capability: immutable ViewModel state composed from saved selection and repository Flow
- Source: `android/nowinandroid`
- Commit: `7d45eae4f8720a0c77f507712ba2437ff974b6ed`
- Paths: `feature/interests/impl/.../InterestsViewModel.kt`, `InterestsViewModelTest.kt`
- License: Apache-2.0
- Adoption: state ownership and test pattern only; no source copied
- MasterNote target: teacher review ViewModel and supporting-pane state
- Validation: upstream implementation and test inspected at the pinned revision
- Excluded: Hilt, Navigation 3 routes, topic domain, and repository implementation

## REF-TEACHER-002

- Capability: main/supporting pane separation and back navigation
- Source: `android/compose-samples`
- Commit: `84788c81186acd5bf0d280100992c8a9c04120ad`
- Path: `Jetcaster/mobile/src/main/java/com/example/jetcaster/ui/home/Home.kt`
- License: Apache-2.0
- Adoption: layout/state separation pattern only; no source copied
- MasterNote target: teacher review tablet layout
- Validation: upstream implementation and root license inspected at the pinned revision
- Differences: initial implementation uses a stable `Row` with a fixed supporting pane because the project has no Material3 Adaptive dependency; no Navigation migration is introduced

## REF-TEACHER-003

- Capability: atomic review creation and publication
- Source: AndroidX Room `@Transaction` / `withTransaction`
- Version: Room 2.8.4, pinned by REF-ANNOTATION-002
- License: Apache-2.0
- Adoption: direct dependency API behind `RoomTeacherRepository`
- Validation: duplicate creation/publication and phase-specific rollback tests

## REF-TEACHER-004

- Capability: system teacher-mode authentication and device-credential fallback
- Source: AndroidX BiometricPrompt official API and Android identity guide
- Version: stable `androidx.biometric:biometric:1.1.0` (official stable table checked 2026-08-12)
- License: Apache-2.0
- Adoption: direct dependency behind `TeacherAccessAuthenticator`; no custom PIN
- Validation: success/cancel coordinator tests, process-memory expiry tests, API 36 route-guard smoke test
- Allowed authenticators: `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`

## REF-REMOTE-001

- Capability: Nearby endpoint lifecycle and callback separation
- Source: `android/connectivity-samples`
- Commit: `ba2371c9e05da06fa398efc1444d254474a2708a`
- Paths: `NearbyConnectionsWalkieTalkie/.../ConnectionsActivity.java`, `MainActivity.java`
- License: Apache-2.0
- Adoption: lifecycle pattern only; no source copied
- Validation: pinned implementation and root license inspected locally
- Excluded: Activity ownership, automatic acceptance, archived permissions, and dependency versions

## REF-REMOTE-002

- Capability: versioned binary wire format with unknown-field tolerance
- Source: `protocolbuffers/protobuf` Maven releases
- Versions: Gradle plugin `0.10.0`, `protoc` and `protobuf-javalite` `4.35.1`
- License: BSD-3-Clause
- Adoption: generated lite messages isolated inside `remote:protocol`
- Validation: all payload round trips, unknown future field, corruption, and size limits tested

## REF-REMOTE-003

- Capability: current Nearby Connections API and connection authentication
- Source: Google Nearby Connections official setup, strategies, manage-connections, and exchange-data guides
- Dependency: `com.google.android.gms:play-services-nearby:19.3.0`
- License: Android SDK terms; documentation samples Apache-2.0
- Adoption: direct dependency only in `remote:transport-nearby`
- Validation: current official setup table and authentication warning checked 2026-08-12
- Configuration: `P2P_POINT_TO_POINT`, `ConnectionType.NON_DISRUPTIVE`, BYTES payloads

## REF-REMOTE-004

- Capability: long-running connected-device foreground service
- Source: Android foreground-service type and launch documentation
- Target: API 36
- License: documentation CC BY 4.0; code samples Apache-2.0
- Adoption: manifest/service lifecycle pattern
- Validation: required `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` declarations checked 2026-08-12

## REF-ASSET-001

- Capability: system document/image selection and IO-dispatched temporary files
- Source: `android/platform-samples`
- Commit: `f751f682aa96a061a39ed4399c697ba513ac93d6`
- Path: `samples/privacy/permissions/src/main/java/com/example/platform/privacy/permissions/Permissionless.kt`
- License: Apache-2.0
- Adoption: Activity Result and FileProvider pattern only; no source copied
- MasterNote target: managed asset import UI and shareable asset handles
- Validation: pinned source and root license inspected; local import instrumentation tests pass
- Excluded: URI-only state, demo Toasts, Coil, and direct UI-layer resolver work

## REF-ASSET-002

- Capability: all-or-nothing managed file publication
- Source: AndroidX `AtomicFile` API contract and Java NIO atomic move
- License: Apache-2.0 / standard library
- Adoption: behavior pattern; large assets use streamed staging, fd sync, and atomic rename guarded by a hash mutex
- Validation: duplicate import, injected post-commit DB failure, and orphan cleanup tests
- Difference: large PDFs and images are never buffered into an `AtomicFile` byte array

## REF-ASSISTANT-001

- Capability: Custom Tabs service warm-up, session, launch, and lifecycle
- Source: `GoogleChrome/android-browser-helper`
- Commit: `a3638f23537189f82165ff96fb2a60431b03f34c`
- Path: `demos/custom-tabs-example-app/src/main/java/org/chromium/customtabsdemos/CustomTabActivityHelper.java`
- License: Apache-2.0
- Adoption: lifecycle pattern only; no source copied
- Excluded: automatic WebView fallback and Activity-owned business state
- Local dependency: `androidx.browser:browser:1.10.0`, pinned after checking the AndroidX stable release table
- Local validation: compile, lint, process-restorable AssistantJob flow, and external-launch contract tests pass

## REF-ASSISTANT-002

- Capability: WebView scheme/host validation and file-access hardening
- Source: Android WebView security guidance
- License: documentation CC BY 4.0; samples Apache-2.0
- Adoption: isolated lab policy only
- Validation: allowlist, HTTPS, file access, universal access, and mixed-content unit/instrumentation checks

## REF-ASSISTANT-003

- Capability: OpenAI API credential boundary
- Source: OpenAI API Key Safety official help article
- Adoption: policy only
- Validation: official guidance checked 2026-08-12
- Rule: no OpenAI API key in APK, resources, BuildConfig, mobile storage, or repository; future direct automation requires a backend adapter

## REF-RESOURCE-001

- Capability: canonical PDF crop and bounded image presentation
- Source: Android `PdfRenderer` and bitmap decoding APIs
- License: Android SDK terms; documentation CC BY 4.0
- Adoption: source-page crop in normalized page coordinates and power-of-two sampled preview decode
- Validation: API 36 instrumented test renders a synthetic two-color PDF and confirms the selected half, output dimensions, and viewport independence

## REF-RESOURCE-002

- Capability: remote teaching-resource presentation without contaminating annotation state
- Source: Maternote remote chunk/checkpoint protocol introduced in PR 4D
- Adoption: hash-addressed session cache and ephemeral offer/present/dismiss messages
- Validation: codec round trip, missing-chunk rejection, complete-hash verification, and cache reuse tests
- Boundary: no TeachingResource, answer, or annotation rows are written on the student device

## REF-PACKAGE-001

- Capability: module responsibility and minimal public API boundaries
- Source: `android/nowinandroid`
- Commit: `7d45eae4f8720a0c77f507712ba2437ff974b6ed`
- Path: `docs/ModularizationLearningJourney.md`
- License: Apache-2.0
- Adoption: pattern only; `package-format` and `studio` are independent Gradle builds

## REF-PACKAGE-002

- Capability: versioned JSON codec
- Source: `Kotlin/kotlinx.serialization`
- Commit: `6956af2e6073347c7832c3c5b374fa3b5a345956`
- Runtime: `kotlinx-serialization-json:1.11.0`
- License: Apache-2.0
- Validation: Kotlin 2.3.21/JVM 17 compile and reader/strict round-trip tests pass

## REF-PACKAGE-003

- Capability: safe ZIP inventory and path traversal rejection
- Source: Android ZIP path traversal security guidance
- License: documentation CC BY 4.0; samples Apache-2.0
- Adoption: inventory-before-extract, absolute/`..`/duplicate/nested archive rejection, bounded sizes and ratio
