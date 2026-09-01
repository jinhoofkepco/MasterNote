package com.studyink.app

import com.studyink.annotation.storage.AnnotationPointEncoding
import com.studyink.annotation.storage.AppliedTeacherReviewReceipt
import com.studyink.annotation.storage.TeacherReviewPublicationOrderDisposition
import com.studyink.annotation.storage.TeacherReviewPublishIntent
import com.studyink.annotation.storage.teacherReviewPublicationOrderDisposition
import com.studyink.core.model.Attempt
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.TeacherReviewStateEvidence
import com.studyink.monitor.core.PageAnnotationKind
import com.studyink.monitor.core.RemoteReviewEnvelopeType
import com.studyink.monitor.core.RemoteReviewLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePageSyncPolicyTest {
    @Test
    fun `compact page encoding requires an exact generation witness`() {
        assertTrue(acceptsCompactPagePayloadForPeer(peerCapabilityGeneration = 7L, pageGeneration = 7L))
        assertFalse(acceptsCompactPagePayloadForPeer(peerCapabilityGeneration = 0L, pageGeneration = 7L))
        assertFalse(acceptsCompactPagePayloadForPeer(peerCapabilityGeneration = 6L, pageGeneration = 7L))
        assertFalse(acceptsCompactPagePayloadForPeer(peerCapabilityGeneration = 0L, pageGeneration = 0L))
        assertEquals(
            AnnotationPointEncoding.COMPACT_Q16_DELTA,
            pointEncodingForRemotePageRequest(acceptsCompactPagePayload = true),
        )
        assertEquals(
            AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
            pointEncodingForRemotePageRequest(acceptsCompactPagePayload = false),
        )
    }

    @Test
    fun `compact capability probe waits briefly then witnesses or falls back`() {
        val generation = 7L
        val probeDeadline = 20_000L

        assertEquals(
            CompactPageRequestMode.WAIT,
            compactPageRequestMode(generation, generation, 0L, 10_000L, probeDeadline),
        )
        assertEquals(
            CompactPageRequestMode.COMPACT,
            compactPageRequestMode(generation, generation, generation, 10_001L, probeDeadline),
        )
        assertEquals(
            CompactPageRequestMode.LEGACY,
            compactPageRequestMode(generation, generation, 0L, probeDeadline, probeDeadline),
        )
        assertEquals(
            CompactPageRequestMode.LEGACY,
            compactPageRequestMode(generation + 1L, generation, generation, 10_000L, probeDeadline),
        )
    }

    @Test fun legacyRenderedPageMessagesAreRetiredWithoutBlockingPageSyncOrChat() {
        assertTrue(isRetiredLegacyRemoteReviewPayloadType(RemoteReviewEnvelopeType.PAGE_SNAPSHOT.name))
        assertTrue(isRetiredLegacyRemoteReviewPayloadType(RemoteReviewEnvelopeType.TEACHER_FEEDBACK.name))
        assertTrue(isRetiredLegacyRemoteReviewPayloadType(RemoteReviewEnvelopeType.REMOTE_GRADE.name))

        assertFalse(isRetiredLegacyRemoteReviewPayloadType(RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST.name))
        assertFalse(isRetiredLegacyRemoteReviewPayloadType(RemoteReviewEnvelopeType.PAGE_SYNC_REQUEST.name))
        assertFalse(isRetiredLegacyRemoteReviewPayloadType(RemoteReviewEnvelopeType.PAGE_ANNOTATION.name))
        assertFalse(isRetiredLegacyRemoteReviewPayloadType(RemoteReviewEnvelopeType.PAGE_SYNC_ACK.name))
        assertFalse(isRetiredLegacyRemoteReviewPayloadType(RemoteReviewEnvelopeType.CHAT_MESSAGE.name))
    }

    @Test fun recoveredReviewUsesOnlyItsDurablePairOwnership() {
        val exact = TeacherReviewPublishIntent(
            bookId = "book-a",
            pageNumber = 81,
            attemptNo = 2,
            updatedAtEpochMillis = 1L,
            remotePairId = "pair-a",
            remoteWorkbookToken = "workbook-a",
            remoteManifestGeneration = 7L,
            remoteManifestSequence = 9L,
        )
        assertEquals(
            RecoveredTeacherReviewOwnership("workbook-a", false, 0L, 0L),
            recoveredTeacherReviewOwnership(exact, "pair-a"),
        )
        assertEquals(
            RecoveredTeacherReviewOwnership(null, false, 0L, 0L),
            recoveredTeacherReviewOwnership(exact, "pair-b"),
        )
        assertEquals(
            RecoveredTeacherReviewOwnership(null, true, 7L, 9L),
            recoveredTeacherReviewOwnership(exact.copy(remoteWorkbookToken = null), "pair-a"),
        )
        assertEquals(
            RecoveredTeacherReviewOwnership(null, false, 0L, 0L),
            recoveredTeacherReviewOwnership(
                exact.copy(
                    remotePairId = null,
                    remoteWorkbookToken = null,
                    remoteManifestGeneration = 0L,
                    remoteManifestSequence = 0L,
                ),
                "pair-a",
            ),
        )
    }

    @Test fun manifestPaginationCoversOrdinaryAndLargeInventoriesWithBoundedWindows() {
        assertEquals(1, requiredManifestBatchCount(0))
        assertEquals(2, requiredManifestBatchCount(48))
        assertEquals(2, requiredManifestBatchCount(49))
        assertEquals(11, requiredManifestBatchCount(512))
        assertEquals(64, requiredManifestBatchCount(3_000))

        val tokens = (0 until 95).map { "page-${it.toString().padStart(3, '0')}" }
        val covered = (0 until requiredManifestBatchCount(tokens.size)).flatMap { ordinal ->
            // Cursor movement does not alter the stable 47-row inventory window.
            selectManifestPageTokens(tokens, tokens[(94 + ordinal) % tokens.size], ordinal.toLong())
        }.toSet()
        assertEquals(tokens.toSet(), covered)
        assertNotEquals(
            selectManifestPageTokens(tokens, tokens[0], 0L).takeLast(47),
            selectManifestPageTokens(tokens, tokens[0], 1L).takeLast(47),
        )
        assertTrue(selectManifestPageTokens(tokens, tokens.last(), 0L).size <= 48)

        val large = (0 until 3_000).map { "large-page-$it" }
        val largeCovered = (0 until requiredManifestBatchCount(large.size)).flatMap { ordinal ->
            selectManifestPageTokens(large, null, ordinal.toLong()).also { window ->
                assertTrue(window.size <= 47)
            }
        }.toSet()
        assertEquals(large.toSet(), largeCovered)
    }

    @Test fun slowManifestAckCannotEraseInventoryChangesDiscoveredWhileItWasInFlight() {
        val schedule = resolveStudentManifestAckSchedule(
            changedAfterReservation = true,
            batchesRemaining = 1,
            requiredBatchCount = 3,
            scheduledDueAtElapsedMs = 60_000L,
            nowElapsedMs = 90_000L,
            intervalMs = 60_000L,
        )

        assertEquals(3, schedule.batchesRemaining)
        assertEquals(60_000L, schedule.dueAtElapsedMs)
    }

    @Test fun interactiveManifestUsesFiveSecondsWithoutAcceleratingInventoryWindows() {
        val interactive = resolveManifestRateBoundaryDueAt(
            scheduledDueAtElapsedMs = Long.MAX_VALUE,
            lastManifestSentAtElapsedMs = 10_000L,
            nowElapsedMs = 11_000L,
            intervalMs = INTERACTIVE_PAGE_SYNC_INTERVAL_MS,
        )
        val inventory = resolveManifestRateBoundaryDueAt(
            scheduledDueAtElapsedMs = Long.MAX_VALUE,
            lastManifestSentAtElapsedMs = 10_000L,
            nowElapsedMs = 11_000L,
            intervalMs = INVENTORY_MANIFEST_INTERVAL_MS,
        )
        val inventoryChangeWhileManifestIsInFlight = resolveStudentManifestAckSchedule(
            changedAfterReservation = true,
            batchesRemaining = 1,
            requiredBatchCount = 3,
            scheduledDueAtElapsedMs = inventory,
            nowElapsedMs = 12_000L,
            intervalMs = INVENTORY_MANIFEST_INTERVAL_MS,
        )

        assertEquals(15_000L, interactive)
        assertEquals(70_000L, inventory)
        assertEquals(70_000L, inventoryChangeWhileManifestIsInFlight.dueAtElapsedMs)
        assertEquals(3, inventoryChangeWhileManifestIsInFlight.batchesRemaining)
    }

    @Test fun interactiveManifestIsBoundedToCurrentAndTwoRecentPages() {
        val latestFirst = (1..80).map { "page-$it" }

        assertEquals(
            listOf("page-80", "page-1", "page-2"),
            selectInteractiveManifestPageTokens(latestFirst, currentPageToken = "page-80"),
        )
        assertEquals(
            listOf("page-1", "page-2", "page-3"),
            selectInteractiveManifestPageTokens(latestFirst, currentPageToken = null),
        )
    }

    @Test fun newPenEventsCannotBypassThirtySecondManifestFailureBackoff() {
        assertFalse(
            manifestLaneReady(
                dueAtElapsedMs = 5_000L,
                retryNotBeforeElapsedMs = 30_000L,
                nowElapsedMs = 29_999L,
            ),
        )
        assertTrue(
            manifestLaneReady(
                dueAtElapsedMs = 5_000L,
                retryNotBeforeElapsedMs = 30_000L,
                nowElapsedMs = 30_000L,
            ),
        )
        assertFalse(
            manifestLaneReady(
                dueAtElapsedMs = 60_000L,
                retryNotBeforeElapsedMs = 0L,
                nowElapsedMs = 30_000L,
            ),
        )
    }

    @Test fun dueInventoryManifestCannotErasePendingRecentPageAdvertisement() {
        assertEquals(
            65_000L,
            preserveInteractiveManifestDueAfterInventorySend(
                pendingDueAtElapsedMs = 55_000L,
                inventorySentAtElapsedMs = 60_000L,
            ),
        )
        assertEquals(
            80_000L,
            preserveInteractiveManifestDueAfterInventorySend(
                pendingDueAtElapsedMs = 80_000L,
                inventorySentAtElapsedMs = 60_000L,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            preserveInteractiveManifestDueAfterInventorySend(
                pendingDueAtElapsedMs = Long.MAX_VALUE,
                inventorySentAtElapsedMs = 60_000L,
            ),
        )
    }

    @Test fun onlyAutomaticDeltaUsesFiveSecondSuccessCooldown() {
        assertEquals(
            5_000L,
            successfulPageSyncCooldownMs(
                kind = PageAnnotationKind.DELTA,
                automatic = true,
                checkpointIntervalSeconds = 60,
            ),
        )
        assertEquals(
            30_000L,
            successfulPageSyncCooldownMs(
                kind = PageAnnotationKind.CHECKPOINT,
                automatic = true,
                checkpointIntervalSeconds = 30,
            ),
        )
        assertEquals(
            60_000L,
            successfulPageSyncCooldownMs(
                kind = PageAnnotationKind.CHECKPOINT,
                automatic = true,
                checkpointIntervalSeconds = 60,
            ),
        )
        assertEquals(
            30_000L,
            successfulPageSyncCooldownMs(
                kind = PageAnnotationKind.DELTA,
                automatic = false,
                checkpointIntervalSeconds = 30,
            ),
        )
        assertEquals(
            60_000L,
            successfulPageSyncCooldownMs(
                kind = PageAnnotationKind.DELTA,
                automatic = false,
                checkpointIntervalSeconds = 60,
            ),
        )
    }

    @Test fun redundantGenerationHighWaterNeverReopensAnOlderJournalGeneration() {
        assertTrue(shouldDiscardRecoveredStudentGeneration(8L, 7L, 7L))
        assertTrue(shouldDiscardRecoveredStudentGeneration(8L, 8L, 7L))
        assertFalse(shouldDiscardRecoveredStudentGeneration(8L, 8L, 8L))
        assertFalse(shouldDiscardRecoveredStudentGeneration(8L, 7L, 0L))
    }

    @Test fun pageRevisionIsIndependentAndMonotonicAcrossAtoBtoA() {
        val a = pageStateFingerprint(SHA_A, listOf(1), listOf(1))
        val b = pageStateFingerprint(SHA_B, listOf(1), listOf(1))

        val first = nextPageSyncRevision(null, 0L, a)
        val unchanged = nextPageSyncRevision(a, first, a)
        val changed = nextPageSyncRevision(a, unchanged, b)
        val returned = nextPageSyncRevision(b, changed, a)

        assertEquals(1L, first)
        assertEquals(first, unchanged)
        assertEquals(2L, changed)
        assertEquals(3L, returned)
    }

    @Test fun fingerprintIncludesExactAttemptAndSubmissionState() {
        val base = pageStateFingerprint(SHA_A, listOf(1, 2), listOf(1))
        assertNotEquals(base, pageStateFingerprint(SHA_A, listOf(1, 2), listOf(2)))
        assertNotEquals(base, pageStateFingerprint(SHA_A, listOf(1, 2, 3), listOf(1)))
        assertNotEquals(base, pageStateFingerprint(SHA_B, listOf(1, 2), listOf(1)))
    }

    @Test fun manifestOrderingRejectsOldGenerationAndSameGenerationReplay() {
        assertTrue(isTeacherManifestStale(4, 8, 3, 99))
        assertTrue(isTeacherManifestStale(4, 8, 4, 8))
        assertTrue(isTeacherManifestStale(4, 8, 4, 7))
        assertFalse(isTeacherManifestStale(4, 8, 4, 9))
        assertFalse(isTeacherManifestStale(4, 8, 5, 1))
    }

    @Test fun duplicateAndStaleManifestsAreClassifiedBeforeAnyPagePreparation() {
        assertEquals(
            TeacherManifestInstallResult.DUPLICATE,
            teacherManifestPreflightResult(4L, 8L, 4L, 8L),
        )
        assertEquals(
            TeacherManifestInstallResult.STALE,
            teacherManifestPreflightResult(4L, 8L, 3L, 99L),
        )
        assertEquals(
            TeacherManifestInstallResult.STALE,
            teacherManifestPreflightResult(4L, 8L, 4L, 7L),
        )
        assertEquals(null, teacherManifestPreflightResult(4L, 8L, 4L, 9L))
        assertEquals(null, teacherManifestPreflightResult(4L, 8L, 5L, 1L))
    }

    @Test fun inventoryTotalWaitsForQueuedOrFailedPagesAndEveryDiscoveredBook() {
        fun complete(
            queuedBooks: Set<String> = emptySet(),
            failedBooks: Set<String> = emptySet(),
            queuedPages: Set<String> = emptySet(),
            failedPages: Set<String> = emptySet(),
            discovered: Set<String> = setOf("book"),
            seeded: Set<String> = setOf("book"),
        ) = isStudentInventoryCatalogComplete(
            queuedBooks,
            failedBooks,
            queuedPages,
            failedPages,
            discovered,
            seeded,
        )

        assertTrue(complete())
        assertFalse(complete(queuedBooks = setOf("book")))
        assertFalse(complete(failedBooks = setOf("book")))
        assertFalse(complete(queuedPages = setOf("book:73")))
        assertFalse(complete(failedPages = setOf("book:73")))
        assertFalse(complete(seeded = emptySet()))
    }

    @Test fun manifestInventoryIncludesDurablePagesMissingFromTheAttemptCatalog() {
        // A previously opened page may have a durable empty/ink row without an Attempt record.
        // The envelope must never advertise a total smaller than the rows it carries.
        assertEquals(37, effectiveStudentInventoryPageCount(36, 37))
        assertEquals(37, effectiveStudentInventoryPageCount(37, 36))
    }

    @Test fun reconnectGenerationTreatsTheExactDurableDigestAsVerificationCandidateOnly() {
        val previouslyApplied = teacherPage(
            token = "old-generation-token",
            sourceRevision = 7L,
            layerSha = SHA_B,
            appliedRevision = 7L,
            appliedLayerSha = SHA_B,
        )

        val reused = reusableTeacherStudentLayerSha256(
            previouslyInstalledPages = listOf(previouslyApplied),
            workbookToken = previouslyApplied.workbookToken,
            contentSha256 = previouslyApplied.contentSha256,
            localBookId = previouslyApplied.localBookId,
            pageNumber = previouslyApplied.pageNumber,
        )
        val reconnected = teacherPage(
            token = "new-generation-token",
            sourceRevision = 1L,
            layerSha = SHA_B,
            appliedRevision = 0L,
            appliedLayerSha = reused,
        ).copy(syncGeneration = 2L, verificationPending = reused == SHA_B)

        assertEquals(SHA_B, reused)
        assertFalse(reconnected.pending)
        assertTrue(reconnected.verificationPending)
        assertEquals(0L, reconnected.appliedRevision)
    }

    @Test fun everyNewMappedManifestRowIsLocallyVerifiedBeforeRequesting() {
        assertTrue(
            shouldVerifyTeacherManifestPage(
                hasMappedLocalPage = true,
                generationChanged = true,
                previousVerificationPending = false,
                previousDigest = null,
                evidenceDigest = SHA_A,
            ),
        )
        // LAN may already have applied B even though the only parked Telegram evidence is old A.
        assertTrue(
            shouldVerifyTeacherManifestPage(
                hasMappedLocalPage = true,
                generationChanged = false,
                previousVerificationPending = false,
                previousDigest = null,
                evidenceDigest = SHA_A,
            ),
        )
        assertFalse(
            shouldVerifyTeacherManifestPage(
                hasMappedLocalPage = true,
                generationChanged = false,
                previousVerificationPending = false,
                previousDigest = SHA_B,
                evidenceDigest = SHA_B,
            ),
        )
        assertTrue(
            shouldVerifyTeacherManifestPage(
                hasMappedLocalPage = true,
                generationChanged = false,
                previousVerificationPending = false,
                previousDigest = SHA_B,
                evidenceDigest = SHA_A,
            ),
        )
        assertFalse(
            shouldVerifyTeacherManifestPage(
                hasMappedLocalPage = false,
                generationChanged = true,
                previousVerificationPending = false,
                previousDigest = null,
                evidenceDigest = null,
            ),
        )
    }

    @Test fun compactAuditRefreshesOnlyForLogGrowthOrNewAttemptMetadata() {
        fun changed(
            logBytes: Long = 100L,
            attempts: List<Int> = listOf(1, 2),
            submitted: List<Int> = listOf(1),
        ) = studentPageNeedsRefreshAfterCompactAudit(
            observedLogBytes = logBytes,
            capturedApproximateBytes = 100L,
            observedCatalogAttemptNos = attempts,
            capturedAttemptNos = listOf(1, 2),
            observedSubmittedAttemptNos = submitted,
            capturedSubmittedAttemptNos = listOf(1),
        )

        assertFalse(changed())
        assertTrue(changed(logBytes = 101L))
        assertTrue(changed(attempts = listOf(1, 2, 3)))
        assertTrue(changed(submitted = listOf(1, 2)))
        // A captured stroke may preserve an attempt number whose catalog row is absent. It is not
        // evidence of a new mutation and must not cause a permanent audit loop.
        assertFalse(changed(attempts = listOf(1)))
    }

    @Test fun unknownOrMismatchedLocalPageStaysPendingWithoutGuessingADigest() {
        val previouslyApplied = teacherPage(
            token = "old-token",
            sourceRevision = 3L,
            layerSha = SHA_A,
            appliedRevision = 3L,
            appliedLayerSha = SHA_A,
        )
        val unknown = reusableTeacherStudentLayerSha256(
            previouslyInstalledPages = listOf(previouslyApplied),
            workbookToken = previouslyApplied.workbookToken,
            contentSha256 = previouslyApplied.contentSha256,
            localBookId = "different-local-import",
            pageNumber = previouslyApplied.pageNumber,
        )
        val incoming = teacherPage(
            token = "new-token",
            sourceRevision = 4L,
            layerSha = SHA_B,
            appliedRevision = 0L,
            appliedLayerSha = unknown,
        ).copy(syncGeneration = 2L)

        assertEquals(null, unknown)
        assertTrue(incoming.pending)
        assertEquals(
            null,
            reusableTeacherStudentLayerSha256(
                previouslyInstalledPages = emptyList(),
                workbookToken = previouslyApplied.workbookToken,
                contentSha256 = previouslyApplied.contentSha256,
                localBookId = previouslyApplied.localBookId,
                pageNumber = previouslyApplied.pageNumber,
            ),
        )
        assertEquals(
            null,
            reusableTeacherStudentLayerSha256(
                previouslyInstalledPages = listOf(
                    previouslyApplied,
                    previouslyApplied.copy(pageToken = "conflict", appliedStudentLayerSha256 = SHA_C),
                ),
                workbookToken = previouslyApplied.workbookToken,
                contentSha256 = previouslyApplied.contentSha256,
                localBookId = previouslyApplied.localBookId,
                pageNumber = previouslyApplied.pageNumber,
            ),
        )
    }

    @Test fun sameRevisionCannotChangeLayerDigest() {
        assertTrue(isTeacherPageRegression(5, SHA_A, 4, SHA_A))
        assertTrue(isTeacherPageRegression(5, SHA_A, 5, SHA_B))
        assertFalse(isTeacherPageRegression(5, SHA_A, 5, SHA_A))
        assertFalse(isTeacherPageRegression(5, SHA_A, 6, SHA_B))
    }

    @Test fun identicalPdfImportsHaveDifferentPendingReviewKeys() {
        val first = pendingReview("book-a")
        val second = pendingReview("book-b")
        assertNotEquals(first.key, second.key)
    }

    @Test fun delayedManifestBehindAnAppliedResponseAdvancesWithoutRollingBack() {
        val responseAhead = teacherPage(
            token = "page-a",
            sourceRevision = 3L,
            layerSha = SHA_C,
            manifestRevision = 1L,
            manifestLayerSha = SHA_A,
            appliedRevision = 3L,
            appliedLayerSha = SHA_C,
        )
        val delayedManifest = teacherPage(
            token = "page-a",
            sourceRevision = 2L,
            layerSha = SHA_B,
            manifestRevision = 2L,
            manifestLayerSha = SHA_B,
        )

        assertFalse(
            isTeacherPageRegression(
                responseAhead.manifestRevision,
                responseAhead.manifestStudentLayerSha256,
                delayedManifest.manifestRevision,
                delayedManifest.manifestStudentLayerSha256,
            ),
        )
        val merged = mergeTeacherPageFromManifest(responseAhead, delayedManifest)
        assertEquals(3L, merged.sourceRevision)
        assertEquals(SHA_C, merged.studentLayerSha256)
        assertEquals(2L, merged.manifestRevision)
        assertEquals(SHA_B, merged.manifestStudentLayerSha256)
        assertEquals(3L, merged.appliedRevision)
    }

    @Test fun failedCurrentPageDoesNotStarveAnotherAutomaticPage() {
        val current = teacherPage("current", lastChangedAt = 30L)
        val recent = teacherPage("recent", lastChangedAt = 20L)

        assertEquals(
            recent,
            selectNextTeacherPage(
                pending = listOf(current, recent),
                automaticTokens = listOf(current.pageToken, recent.pageToken),
                manualRunning = false,
                failedPageTokens = setOf(current.pageToken),
            ),
        )
    }

    @Test fun automaticAndManualQueuesRotateWithoutStarvingRecentPages() {
        val current = teacherPage("current", lastChangedAt = 30L)
        val recent = teacherPage("recent", lastChangedAt = 20L)
        val older = teacherPage("older", lastChangedAt = 10L)
        val manual = teacherPage("manual", lastChangedAt = 1L)
        val pending = listOf(current, recent, older, manual)
        val automatic = listOf(current.pageToken, recent.pageToken, older.pageToken)

        assertEquals(
            current,
            selectNextTeacherPage(pending, automatic, true, emptySet(), current.pageToken, false),
        )
        assertEquals(
            manual,
            selectNextTeacherPage(pending - current, automatic, true, emptySet(), recent.pageToken, true),
        )
        assertEquals(
            older,
            selectNextTeacherPage(pending - current, automatic, true, emptySet(), recent.pageToken, false),
        )
    }

    @Test fun incompleteInventoryAutomaticallyRequestsOnlyTheCurrentPage() {
        val latest = (1..60).map { "page-$it" }

        assertEquals(
            listOf("page-52"),
            selectAutomaticPageTokens(latest, "page-52", inventoryComplete = false),
        )
        assertTrue(selectAutomaticPageTokens(latest, null, inventoryComplete = false).isEmpty())
    }

    @Test fun completeLargeInventoryHasExactlyCurrentPlusTwoLatestWithoutAccumulation() {
        val firstWindow = (1..48).map { "page-$it" }
        val secondWindow = (49..96).map { "page-$it" } + firstWindow

        assertEquals(
            listOf("page-82", "page-49", "page-50"),
            selectAutomaticPageTokens(secondWindow, "page-82", inventoryComplete = true),
        )
        assertEquals(3, selectAutomaticPageTokens(firstWindow, "page-40", true).size)
        assertEquals(3, selectAutomaticPageTokens(secondWindow, "page-82", true).size)
    }

    @Test fun checkpointSplitIsBoundedAndReassemblesWithoutByteChanges() {
        val limit = RemoteReviewLimits.MAX_PAGE_ANNOTATION_CHECKPOINT_BYTES
        val payload = ByteArray(limit + 501) { index -> (index % 251).toByte() }
        val chunks = splitPageCheckpointPayload(payload, limit)

        assertEquals(listOf(limit, 501), chunks.map(ByteArray::size))
        assertTrue(chunks.all { it.size <= limit })
        assertTrue(payload.contentEquals(chunks.reduce { left, right -> left + right }))
    }

    @Test fun unavailableNewestReviewDoesNotStarveAnExactOpenAttemptReview() {
        val unavailable = pendingReview("book-a").copy(queuedAtEpochMs = 20L)
        val sendable = pendingReview("book-b").copy(queuedAtEpochMs = 10L)
        val selected = selectTransmittableTeacherReview(
            pendingReviews = listOf(unavailable, sendable),
            pages = listOf(teacherPage("page-b", bookId = "book-b")),
        )

        assertEquals(sendable.key, selected?.pending?.key)
    }

    @Test fun exactOpenAttemptMayReceivePublishedReviewWithoutBeingSubmitted() {
        val open = Attempt(
            bookId = "book-a",
            pageNumber = 81,
            attemptNo = 4,
            locked = false,
        )

        assertTrue(isKnownStudentAttemptForReview(listOf(open), 4))
        assertTrue(isKnownStudentAttemptForReview(listOf(open.copy(locked = true)), 4))
        assertFalse(isKnownStudentAttemptForReview(listOf(open), 3))
    }

    @Test fun pendingReviewNeverMovesToAnotherRemoteWorkbookWithTheSamePdfIdentity() {
        val pending = pendingReview("book-a")
        val wrongRemoteWorkbook = teacherPage(
            "page-wrong",
            bookId = "book-a",
        ).copy(workbookToken = "workbook-reimported")
        assertEquals(
            null,
            selectTransmittableTeacherReview(listOf(pending), listOf(wrongRemoteWorkbook)),
        )

        // A reconnect generation may change, but the immutable remote workbook identity may not.
        val sameRemoteWorkbook = teacherPage(
            "page-reconnected",
            bookId = "book-a",
        ).copy(syncGeneration = 9L)
        assertEquals(
            sameRemoteWorkbook.pageToken,
            selectTransmittableTeacherReview(listOf(pending), listOf(sameRemoteWorkbook))?.page?.pageToken,
        )
    }

    @Test fun publicationProvenanceNeedsOneExactBidirectionalWorkbookMappingButNotFullInventory() {
        val exact = teacherPage("page-exact", bookId = "book-a")

        assertEquals(
            exact.workbookToken,
            resolveExactPublishedReviewWorkbookToken(
                localBookId = "book-a",
                contentSha256 = SHA_A,
                pageNumber = 81,
                attemptNo = 2,
                mappedWorkbookToken = exact.workbookToken,
                mappedLocalBookId = "book-a",
                pages = listOf(exact),
            ),
        )
        assertEquals(
            null,
            resolveExactPublishedReviewWorkbookToken(
                "book-a",
                SHA_A,
                81,
                2,
                exact.workbookToken,
                "book-b",
                listOf(exact),
            ),
        )
        assertEquals(
            null,
            resolveExactPublishedReviewWorkbookToken(
                "book-a",
                SHA_A,
                81,
                3,
                exact.workbookToken,
                "book-a",
                listOf(exact),
            ),
        )
    }

    @Test fun onlyExplicitNewUnresolvedReviewMayBindFromOneExactNewManifestRow() {
        val heldLegacy = pendingReview("book-a").copy(workbookToken = null)
        val deferred = heldLegacy.copy(deferredWorkbookBinding = true)
        val exact = reviewEvidence("workbook-book-a", "book-a", listOf(2), generation = 2L, sequence = 1L)

        assertEquals(null, resolveDeferredReviewWorkbookToken(heldLegacy, listOf(exact)))
        assertEquals(
            exact.workbookToken,
            resolveDeferredReviewWorkbookToken(deferred, listOf(exact)),
        )
        assertEquals(
            null,
            resolveDeferredReviewWorkbookToken(
                deferred.copy(
                    deferredAfterManifestGeneration = 2L,
                    deferredAfterManifestSequence = 1L,
                ),
                listOf(exact),
            ),
        )
        assertEquals(
            null,
            resolveDeferredReviewWorkbookToken(
                deferred,
                listOf(exact, exact.copy(workbookToken = "workbook-other")),
            ),
        )
        val separatelyMappedDuplicate = exact.copy(
            workbookToken = "workbook-book-b",
            localBookId = "book-b",
        )
        assertEquals(
            exact.workbookToken,
            resolveDeferredReviewWorkbookToken(
                deferred,
                listOf(exact, separatelyMappedDuplicate),
            ),
        )
        assertEquals(
            null,
            resolveDeferredReviewWorkbookToken(
                deferred,
                listOf(exact.copy(attemptNos = listOf(1))),
            ),
        )
        assertEquals(
            null,
            resolveDeferredReviewWorkbookToken(
                deferred,
                listOf(exact.copy(pageNumber = 82)),
            ),
        )
        assertEquals(
            exact.workbookToken,
            resolveDeferredReviewWorkbookToken(
                deferred.copy(deferredAfterManifestGeneration = 2L, deferredAfterManifestSequence = 99L),
                listOf(exact.copy(manifestGeneration = 3L, manifestSequence = 1L)),
            ),
        )
    }

    @Test fun retainedAuthorityWithoutAppPendingBindsOnlyFromExactNewerManifestEvidence() {
        val authority = teacherAuthority(
            remoteManifestGeneration = 2L,
            remoteManifestSequence = 4L,
        )
        val exact = reviewEvidence(
            "workbook-book-a",
            "book-a",
            listOf(2),
            generation = 2L,
            sequence = 5L,
        )

        assertEquals(
            exact.workbookToken,
            resolveDeferredAuthorityWorkbookToken(authority, "pair-a", listOf(exact)),
        )
        assertNull(resolveDeferredAuthorityWorkbookToken(authority, "pair-b", listOf(exact)))
        assertNull(resolveDeferredAuthorityWorkbookToken(
            authority.copy(remoteWorkbookToken = "workbook-other"),
            "pair-a",
            listOf(exact),
        ))
        assertNull(resolveDeferredAuthorityWorkbookToken(
            authority,
            "pair-a",
            listOf(exact.copy(manifestSequence = 4L)),
        ))
        assertNull(resolveDeferredAuthorityWorkbookToken(
            authority,
            "pair-a",
            listOf(exact.copy(attemptNos = listOf(1))),
        ))
        assertNull(resolveDeferredAuthorityWorkbookToken(
            authority,
            "pair-a",
            listOf(exact, exact.copy(workbookToken = "workbook-other")),
        ))
    }

    @Test fun explicitMappingBindsOnlyExactPairPageAttemptAndNeverRebindsAnotherWorkbook() {
        val authority = teacherAuthority()
        val exactPage = teacherPage("page-exact", bookId = "book-a")

        assertTrue(canBindDeferredAuthorityForExplicitMapping(
            authority,
            "pair-a",
            exactPage.workbookToken,
            "book-a",
            listOf(exactPage),
        ))
        assertTrue(canBindDeferredAuthorityForExplicitMapping(
            authority.copy(remoteWorkbookToken = exactPage.workbookToken),
            "pair-a",
            exactPage.workbookToken,
            "book-a",
            listOf(exactPage),
        ))
        assertFalse(canBindDeferredAuthorityForExplicitMapping(
            authority.copy(remoteWorkbookToken = "workbook-other"),
            "pair-a",
            exactPage.workbookToken,
            "book-a",
            listOf(exactPage),
        ))
        assertFalse(canBindDeferredAuthorityForExplicitMapping(
            authority,
            "pair-b",
            exactPage.workbookToken,
            "book-a",
            listOf(exactPage),
        ))
        assertFalse(canBindDeferredAuthorityForExplicitMapping(
            authority,
            "pair-a",
            exactPage.workbookToken,
            "book-a",
            listOf(exactPage.copy(attemptNos = listOf(1))),
        ))
    }

    @Test fun equalRevisionDuplicateRequiresInstalledMetadataAtLeastAsNewAsPayload() {
        val incoming = MarkGroup(
            id = "grade-a",
            bookId = "remote-book",
            pageNumber = 81,
            anchor = PagePoint(20f, 30f),
            marks = listOf(Mark(2, MarkColor.BLUE, 10L)),
            createdAtEpochMillis = 1L,
            hiddenAtEpochMillis = 20L,
            syncRevision = 2L,
            lastModifiedByDeviceId = "teacher",
        )
        val same = incoming.copy(bookId = "local-book")
        val newer = same.copy(
            anchor = PagePoint(40f, 50f),
            hiddenAtEpochMillis = null,
            syncRevision = 3L,
            lastModifiedByDeviceId = "student",
        )
        val rolledBack = same.copy(
            anchor = PagePoint(1f, 2f),
            hiddenAtEpochMillis = null,
            syncRevision = 1L,
        )

        assertTrue(teacherReviewMetadataCoversIncoming(listOf(same), listOf(incoming)))
        assertTrue(teacherReviewMetadataCoversIncoming(listOf(newer), listOf(incoming)))
        assertFalse(teacherReviewMetadataCoversIncoming(listOf(rolledBack), listOf(incoming)))
        assertFalse(teacherReviewMetadataCoversIncoming(emptyList(), listOf(incoming)))
    }

    @Test fun sharedPublicationTimeOrdersLanAndTelegramWithoutTrustingRouteRevision() {
        val receipt = AppliedTeacherReviewReceipt(
            bookId = "book-a",
            pageNumber = 81,
            attemptNo = 2,
            publicationId = SHA_A,
            resultLayerSha256 = SHA_B,
            markGroupsSha256 = SHA_C,
            appliedAtEpochMillis = 20L,
            publishedAtEpochMillis = 100L,
            remotePairId = "pair-a",
            remoteWorkbookToken = "old-workbook-token",
        )
        fun order(publicationId: String = SHA_B, publishedAt: Long = 99L) =
            teacherReviewPublicationOrderDisposition(receipt, publicationId, publishedAt)

        assertEquals(TeacherReviewPublicationOrderDisposition.STALE, order())
        assertEquals(
            TeacherReviewPublicationOrderDisposition.STALE,
            order(publishedAt = 0L),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.DUPLICATE_VERIFY,
            order(publicationId = SHA_A, publishedAt = 100L),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.CONFLICT,
            order(publicationId = SHA_B, publishedAt = 100L),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.APPLY,
            order(publicationId = SHA_B, publishedAt = 101L),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.APPLY,
            teacherReviewPublicationOrderDisposition(
                receipt.copy(publishedAtEpochMillis = 0L),
                SHA_B,
                1L,
            ),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.APPLY,
            teacherReviewPublicationOrderDisposition(null, SHA_B, 0L),
        )
    }

    @Test fun failedReviewYieldsToAnotherSendableReview() {
        val first = pendingReview("book-a").copy(queuedAtEpochMs = 20L)
        val second = pendingReview("book-b").copy(queuedAtEpochMs = 10L)
        val pages = listOf(
            teacherPage("page-a", bookId = "book-a"),
            teacherPage("page-b", bookId = "book-b"),
        )

        assertEquals(
            second.key,
            selectTransmittableTeacherReview(listOf(first, second), pages, setOf(first.key))?.pending?.key,
        )
    }

    @Test fun oneLocalWorkbookCannotBeClaimedByTwoRemoteWorkbookTokens() {
        val claims = mapOf("local-book" to "remote-a")
        assertTrue(canAssignLocalWorkbook("remote-a", "local-book", claims))
        assertFalse(canAssignLocalWorkbook("remote-b", "local-book", claims))
    }

    @Test fun initialSeedKeepsHistoricalOrderingAndOnlyRealMutationUsesNow() {
        assertEquals(120L, resolveStudentPageChangedAt(null, 0L, "state-a", 120L, 9_999L))
        assertEquals(120L, resolveStudentPageChangedAt("state-a", 120L, "state-a", 80L, 9_999L))
        assertEquals(9_999L, resolveStudentPageChangedAt("state-a", 120L, "state-b", 80L, 9_999L))
    }

    @Test fun teacherReviewPublicationIdentityWaitsForObservedPeerCapability() {
        assertNull(teacherReviewPublicationIdForPeer(SHA_A, 0L, 7L))
        assertNull(teacherReviewPublicationIdForPeer(SHA_A, 6L, 7L))
        assertEquals(SHA_A, teacherReviewPublicationIdForPeer(SHA_A, 7L, 7L))
    }

    @Test fun teacherReviewManifestUnknownMatchesOrReopensWithoutGuessing() {
        assertEquals(
            TeacherReviewManifestDisposition.UNKNOWN,
            teacherReviewManifestDisposition(null, SHA_A),
        )
        assertEquals(
            TeacherReviewManifestDisposition.MATCH,
            teacherReviewManifestDisposition(SHA_A, SHA_A),
        )
        assertEquals(
            TeacherReviewManifestDisposition.MISMATCH,
            teacherReviewManifestDisposition(SHA_B, SHA_A),
        )
    }

    @Test fun optionalTeacherReviewDigestFailureDoesNotBlockManifest() {
        val evidence = TeacherReviewStateEvidence(2, SHA_A, SHA_B, SHA_C)
        assertEquals(
            com.studyink.monitor.core.teacherReviewStateSha256(emptyList()),
            teacherReviewStateDigestOrNull { emptyList() },
        )
        assertEquals(
            com.studyink.monitor.core.teacherReviewStateSha256(listOf(evidence)),
            teacherReviewStateDigestOrNull { listOf(evidence) },
        )
        assertNull(teacherReviewStateDigestOrNull { error("receipt storage unavailable") })
    }

    @Test fun teacherReviewAuthorityRequiresExactPairWorkbookPageAndAttempt() {
        val intent = TeacherReviewPublishIntent(
            bookId = "book-a",
            pageNumber = 81,
            attemptNo = 2,
            updatedAtEpochMillis = 1L,
            publicationId = SHA_A,
            checkpointSha256 = SHA_B,
            resultLayerSha256 = SHA_C,
            checkpointSizeBytes = 1,
            markGroupsSha256 = SHA_B,
            markGroupsSizeBytes = 1,
            remotePairId = "pair-a",
            remoteWorkbookToken = "workbook-a",
        )
        fun eligible(
            pairId: String = "pair-a",
            workbookToken: String = "workbook-a",
            bookId: String = "book-a",
            pageNumber: Int = 81,
            attempts: List<Int> = listOf(2),
        ) = isTeacherReviewAuthorityForManifest(
            intent, pairId, workbookToken, bookId, pageNumber, attempts,
        )

        assertTrue(eligible())
        assertFalse(eligible(pairId = "pair-b"))
        assertFalse(eligible(workbookToken = "workbook-b"))
        assertFalse(eligible(bookId = "book-b"))
        assertFalse(eligible(pageNumber = 82))
        assertFalse(eligible(attempts = listOf(1, 3)))
        assertFalse(isTeacherReviewAuthorityForManifest(
            intent.copy(remoteWorkbookToken = null),
            "pair-a",
            "workbook-a",
            "book-a",
            81,
            listOf(2),
        ))
    }

    private fun pendingReview(bookId: String) = PendingTeacherReviewRecord(
        intentId = "intent-1",
        bookId = bookId,
        contentSha256 = SHA_A,
        workbookToken = "workbook-$bookId",
        pageNumber = 81,
        attemptNo = 2,
        queuedAtEpochMs = 1L,
    )

    private fun teacherPage(
        token: String,
        bookId: String = "book-a",
        sourceRevision: Long = 1L,
        layerSha: String = SHA_A,
        manifestRevision: Long = sourceRevision,
        manifestLayerSha: String = layerSha,
        appliedRevision: Long = 0L,
        appliedLayerSha: String? = null,
        submitted: List<Int> = emptyList(),
        lastChangedAt: Long = 1L,
    ) = TeacherPageSyncRecord(
        syncGeneration = 1L,
        pageToken = token,
        workbookToken = "workbook-$bookId",
        contentSha256 = SHA_A,
        studentLayerSha256 = layerSha,
        workbookLabel = bookId,
        localBookId = bookId,
        pageNumber = 81,
        attemptNos = listOf(2),
        submittedAttemptNos = submitted,
        sourceRevision = sourceRevision,
        manifestRevision = manifestRevision,
        manifestStudentLayerSha256 = manifestLayerSha,
        appliedRevision = appliedRevision,
        appliedStudentLayerSha256 = appliedLayerSha,
        lastChangedAtEpochMs = lastChangedAt,
        approximateBytes = 100L,
    )

    private fun reviewEvidence(
        workbookToken: String,
        localBookId: String,
        attempts: List<Int>,
        generation: Long,
        sequence: Long,
    ) = TeacherReviewManifestEvidence(
        workbookToken = workbookToken,
        contentSha256 = SHA_A,
        localBookId = localBookId,
        pageNumber = 81,
        attemptNos = attempts,
        manifestGeneration = generation,
        manifestSequence = sequence,
    )

    private fun teacherAuthority(
        remoteManifestGeneration: Long = 1L,
        remoteManifestSequence: Long = 1L,
    ) = TeacherReviewPublishIntent(
        bookId = "book-a",
        pageNumber = 81,
        attemptNo = 2,
        updatedAtEpochMillis = 1L,
        publicationId = SHA_A,
        checkpointSha256 = SHA_B,
        resultLayerSha256 = SHA_C,
        checkpointSizeBytes = 1,
        markGroupsSha256 = SHA_B,
        markGroupsSizeBytes = 1,
        remotePairId = "pair-a",
        remoteManifestGeneration = remoteManifestGeneration,
        remoteManifestSequence = remoteManifestSequence,
    )

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
