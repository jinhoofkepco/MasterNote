# Teacher review test matrix

| Requirement | Automated coverage | Physical tablet gate |
| --- | --- | --- |
| One draft under repeated and concurrent opens | `sameSubmissionReusesOneDraftAndCreatesAllReviewPages`, `concurrentDraftRequestsStillCreateOneReview` | rapidly open the same queue item twice |
| Student submission cannot be erased or undone by teacher tools | `ReaderTeacherSceneTest.submissionReviewEditsOnlyFeedbackAndPublishedSceneIsReadOnly` | erase and undo over student ink |
| Publish is atomic at every phase | `everyInjectedPublishFailureRollsBackRefsLockAndStatus` | interrupt a diagnostic publish |
| Published feedback and metadata are immutable | `publishingSnapshotsFeedbackLocksLayerAndIsIdempotent` | reopen and attempt pen, erase, page-check, and decision changes |
| Draft feedback survives Activity recreation | `ReaderTeacherSceneTest.submissionReviewEditsOnlyFeedbackAndPublishedSceneIsReadOnly` | rotate during a draft review |
| Review decision drives projected progress | `reviewQueueProgressAndRetryAttemptAreDerivedFromReviewHistory`, `acceptedPublishedReviewCompletesProgress` | publish accepted/retry and inspect both modes |
| Retry attempt retains its source review | `reviewQueueProgressAndRetryAttemptAreDerivedFromReviewHistory` | start retry from student progress |
| Teacher route requires a live process session | `TeacherModeAccessTest` | cancel authentication, direct-route attempt, background timeout |
| Authentication success, cancel, and expiry policy | `TeacherSessionControllerTest` | BiometricPrompt success/cancel/device-credential fallback |
| 20 pages / 2,000 student strokes / 500 feedback strokes | `largeReviewSnapshotsReferenceAssetsWithoutBlobDuplication` | profile publish latency, frames, and memory |
| Fixed signing identity | CI certificate verification in `build-test-apk.yml` | update an installed prior build without uninstalling |

## Current device boundary

The automated Android tests run on the Android 16 ARM64 emulator. A physical S Pen tablet is
still required for BiometricPrompt/device-credential UI, pen latency, rotation, update install,
and a 30-minute memory soak. Emulator success must not be reported as that physical gate.
