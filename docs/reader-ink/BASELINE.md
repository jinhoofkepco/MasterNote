# Reader/Ink v0.1 baseline

This document freezes the behavior at Git tag `reader-ink-v0.1`. PR 0 adds diagnostics,
tests, and documentation only; it does not intentionally change reader or ink behavior.

## Frozen architecture

| Area | Module | Pinned implementation |
| --- | --- | --- |
| PDF | `document:pdf-androidx` | AndroidX PDF `1.0.0-alpha19`, one active page, 800 px visible tiles |
| Wet ink | `feature:reader` | Jetpack Ink authoring/brush/strokes `1.0.0` |
| Dry ink | `feature:reader` | Canonical page width 1000, Canvas rendering |
| Erasing | `annotation:engine` | Blue preview, whole-stroke remove, partial split on pen-up |
| Persistence | `annotation:storage` | Per-document `AtomicFile` JSON; replacement is superseded in PR 1 |
| UI | `feature:reader` | Compose BOM `2026.06.00`, Material 3 radial S Pen menu |

Build toolchain: AGP `8.13.2`, Kotlin `2.3.21`, Gradle `8.13`, compile/target SDK `36`,
minimum SDK `31`, Java `17`.

## Device record

| Device | Android | Build | Result |
| --- | --- | --- | --- |
| Android emulator | 16 / API 36 | debug instrumentation | Reader interaction suite passes |
| Physical study tablet | **Pending capture** | — | Record model, Android build, RAM, and S Pen firmware before PR 1 sign-off |

The physical model is deliberately not inferred from screenshots. Fill the row from
`Settings > About tablet` when the device is available.

## Fixed PDF fixtures

`ReaderPdfFixtures` deterministically generates three versioned test inputs in the
instrumentation cache so large binary PDFs do not inflate the APK or repository:

1. `baseline-text-v1.pdf`: three vector-text pages.
2. `baseline-scan-v1.pdf`: one 1600 × 2400 raster scan page.
3. `baseline-long-120p-v1.pdf`: 120 vector pages.

`ReaderPdfFixtureTest` opens each with the platform renderer and verifies the page count.
Changing a generator requires a fixture version change and a baseline review.

## Performance baseline protocol

Measure a release-like build signed with the fixed test key. Run three cold iterations and
report the median; use the same PDF fixture, zoom, page, and stroke count before and after a
change.

| Metric | Start | End | Current baseline |
| --- | --- | --- | --- |
| First page display | process launch | first page bitmap visible | API 36 emulator: pending dedicated trace |
| Page navigation | page command | target page bitmap visible | API 36 emulator: pending dedicated trace |
| Ink input latency | stylus sample | wet ink frame | Physical S Pen tablet required |
| Whole-stroke erase | pen-up | dry snapshot updated | Physical S Pen tablet required |
| Partial erase commit | pen-up | split snapshot saved | Physical S Pen tablet required |
| 30-minute memory | post-open PSS | PSS after scripted use | Physical tablet soak required |

PR 0 intentionally does not invent numbers from an unavailable physical tablet. The
instrumentation regression suite and debug StrictMode policy provide the reproducible
measurement boundary; PR 1 cannot be signed off until the pending physical rows are filled.

## Callback contract

- One completed pen/highlighter gesture invokes `InkInputView.onStroke` once.
- One completed partial/whole eraser gesture invokes `InkInputView.onErase` once.
- Move and preview events never invoke a completion callback.
- Cancellation invokes neither completion callback.

`InkCallbackContractTest` locks the completed pen and partial-erase counts to one.

## StrictMode baseline

The API 36 debug run exposes one MasterNote-owned violation: `AtomicAnnotationStore` checks and
creates its directory during `ReaderViewModel` construction on the main thread. It is recorded
in `KNOWN_ISSUES.md`; PR 1 must remove it by opening persistence on the IO dispatcher. The policy
remains log-only in PR 0 so the baseline behavior is unchanged.

## Commands

```bash
./gradlew testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :document:pdf-androidx:lintDebug :feature:reader:lintDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```
