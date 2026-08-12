# Annotation persistence test matrix

| Requirement | Automated coverage | Physical tablet gate |
| --- | --- | --- |
| Five pages restore independently | `RoomAnnotationStoreTest.strokesOnFivePagesRestoreWithIndependentPageRevisions` | write on five pages, force-stop, reopen |
| Stable Ink 1.0 codec | `inkCodecRoundTripsInputsUsingStableStreamApi` | reopen pressure-sensitive pen strokes |
| Room v1 → v2 migration | `AnnotationDatabaseSchemaTest` preserves annotation rows and validates new tables | Database Inspector schema spot-check |
| Learning fixture and empty progress | `LearningDaoTest` | fixed three-page book smoke test |
| Attempt create/reuse/profile/abandon | `RoomLearningRepositoryTest` | open the same activity repeatedly |
| Attempt working-layer isolation | `RoomLearningRepositoryTest.workingLayersAreIsolatedBetweenAttempts` | submit then start a new attempt |
| Resume page plus working ink | `ReaderAttemptSessionTest` | close on page 3 and reopen |
| Duplicate submission is idempotent | `RoomLearningRepositoryTest.duplicateSubmitReturnsOneImmutableSnapshotWithoutCopyingStrokeBlobs` | rapidly tap submit twice |
| Submission transaction rollback | `RoomLearningRepositoryTest.everyInjectedSubmissionFailureRollsBackTheWholeTransaction` | interrupt submission during a diagnostic fault run |
| Flush failure blocks submission | `RoomLearningRepositoryTest.flushFailurePreventsSubmissionTransactionFromStarting` | simulate unavailable storage before submit |
| Immutable read-only submission | repository and `ReaderAttemptSessionTest.submittedAttemptReopensAsAnImmutableReadOnlySnapshot` | submit, reopen snapshot, attempt to write |
| Progress projection 0/□/■/■□/■■■ | `RoomLearningRepositoryTest.progressIsProjectedFromAttemptsAndSubmissionsWithoutMutableCounters` | submit three attempts and compare markers |
| Vertical activity → Reader → submit flow | `ProgressFlowTest.activityCreatesAttemptSubmitsAndUpdatesProgressProjection` | open Unit 1, submit, return to list |
| Partial erase is atomic | success and injected-failure tests in `RoomAnnotationStoreTest` | erase repeatedly, force-stop immediately |
| Undo restores original asset | `AnnotationDocumentTest.undoAfterPartialEraseRestoresTheImmutableOriginalStroke` | erase, undo, force-stop, reopen |
| One corrupt payload is isolated | `corruptStrokeBlobIsSkippedWithoutBlockingOtherStrokes` | import diagnostic corrupt fixture |
| PDF-specific isolation | `ReaderInteractionTest.annotationsAreIsolatedAndRestoredPerPdf` | switch among three named PDFs |
| UI thread avoids database I/O | debug StrictMode plus reader instrumentation log inspection | 30-minute writing soak |
| 20 pages / 2,000 student strokes / 500 feedback strokes | `RoomTeacherRepositoryTest.largeReviewSnapshotsReferenceAssetsWithoutBlobDuplication` | repeat review/publish while profiling frame time and memory |

## Merge gate

PR 1 can merge only after JVM tests, storage instrumentation tests, the full reader
instrumentation suite, lint, and a physical S Pen force-stop/reopen pass. Physical latency,
frame pacing, and 30-minute memory measurements remain explicit device gates rather than
being inferred from an emulator.
