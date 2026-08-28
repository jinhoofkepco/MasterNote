package com.studyink.app

import com.studyink.annotation.storage.TeacherReviewPublishIntent
import com.studyink.core.model.Attempt
import com.studyink.monitor.core.RemoteReviewEnvelopeType
import com.studyink.monitor.core.RemoteReviewLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePageSyncPolicyTest {
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

    @Test fun reconnectGenerationReusesOnlyTheExactDurableLocalPageDigest() {
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
            appliedRevision = if (reused == SHA_B) 1L else 0L,
            appliedLayerSha = reused,
        ).copy(syncGeneration = 2L)

        assertEquals(SHA_B, reused)
        assertFalse(reconnected.pending)
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

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
