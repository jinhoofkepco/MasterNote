# Annotation persistence test matrix

| Requirement | Automated coverage | Physical tablet gate |
| --- | --- | --- |
| Five pages restore independently | `RoomAnnotationStoreTest.strokesOnFivePagesRestoreWithIndependentPageRevisions` | write on five pages, force-stop, reopen |
| Stable Ink 1.0 codec | `inkCodecRoundTripsInputsUsingStableStreamApi` | reopen pressure-sensitive pen strokes |
| Room v1 → v2 migration | `AnnotationDatabaseSchemaTest` preserves annotation rows and validates new tables | Database Inspector schema spot-check |
| Learning fixture and empty progress | `LearningDaoTest` | fixed three-page book smoke test |
| Partial erase is atomic | success and injected-failure tests in `RoomAnnotationStoreTest` | erase repeatedly, force-stop immediately |
| Undo restores original asset | `AnnotationDocumentTest.undoAfterPartialEraseRestoresTheImmutableOriginalStroke` | erase, undo, force-stop, reopen |
| One corrupt payload is isolated | `corruptStrokeBlobIsSkippedWithoutBlockingOtherStrokes` | import diagnostic corrupt fixture |
| PDF-specific isolation | `ReaderInteractionTest.annotationsAreIsolatedAndRestoredPerPdf` | switch among three named PDFs |
| UI thread avoids database I/O | debug StrictMode plus reader instrumentation log inspection | 30-minute writing soak |
| 2,000-stroke page | deferred to PR 3 benchmark | required before performance release |

## Merge gate

PR 1 can merge only after JVM tests, storage instrumentation tests, the full reader
instrumentation suite, lint, and a physical S Pen force-stop/reopen pass. Physical latency,
frame pacing, and 30-minute memory measurements remain explicit device gates rather than
being inferred from an emulator.
