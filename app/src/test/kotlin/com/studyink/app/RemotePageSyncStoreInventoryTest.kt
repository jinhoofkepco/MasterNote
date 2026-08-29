package com.studyink.app

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePageSyncStoreInventoryTest {
    @Test
    fun automaticRequestLaneSurvivesCursorChangesAndProcessRestart() =
        withStoreFile { file ->
            val page = teacherPages(syncGeneration = 4L, count = 1).single()
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(4L, 1L, listOf(page), null, 1),
                )
                val reserved = requireNotNull(
                    reserveTeacherRequest(
                        pageToken = page.pageToken,
                        transferId = "page_request_automatic_0001",
                        createdAtEpochMs = 1L,
                        requestedSourceRevision = page.sourceRevision,
                        requesterRevision = 0L,
                        requestWasAutomatic = true,
                    ),
                )
                assertTrue(reserved.requestWasAutomatic)
            }

            RemotePageSyncStore(file).apply {
                val recovered = requireNotNull(teacherPage(page.pageToken))
                assertTrue(recovered.requestWasAutomatic)
                assertTrue(clearTeacherRequest(page.pageToken, recovered.requestTransferId))
                assertFalse(requireNotNull(teacherPage(page.pageToken)).requestWasAutomatic)
            }
        }

    @Test
    fun interactiveManifestAckSurvivesRestartWithoutAdvancingInventoryWindow() =
        withStoreFile { file ->
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                beginStudentGeneration()
                val firstInventory = reserveStudentManifest(
                    transferId = "manifest_inventory_0001",
                    createdAtEpochMs = 1L,
                    advancesInventoryWindow = true,
                )
                assertEquals(0L, firstInventory.windowOrdinal)
                assertTrue(acknowledgeOutstandingStudentManifest(firstInventory.transferId))

                val interactive = reserveStudentManifest(
                    transferId = "manifest_interactive_0001",
                    createdAtEpochMs = 2L,
                    advancesInventoryWindow = false,
                )
                assertEquals(1L, interactive.windowOrdinal)
                assertFalse(interactive.advancesInventoryWindow)
            }

            RemotePageSyncStore(file).apply {
                val recovered = requireNotNull(outstandingStudentManifest())
                assertFalse(recovered.advancesInventoryWindow)
                assertTrue(acknowledgeOutstandingStudentManifest(recovered.transferId))

                val nextInventory = reserveStudentManifest(
                    transferId = "manifest_inventory_0002",
                    createdAtEpochMs = 3L,
                    advancesInventoryWindow = true,
                )
                assertEquals(1L, nextInventory.windowOrdinal)
            }
        }

    @Test
    fun recoveredOpenGenerationIsDurablyFencedBeforeTheNewRuntimeUsesIt() =
        withStoreFile { file ->
            val oldGeneration = RemotePageSyncStore(file).run {
                bindPair(PAIR_ID)
                beginStudentGeneration().also { generation ->
                    reserveStudentManifest(
                        transferId = "manifest_before_process_death",
                        createdAtEpochMs = 1L,
                        advancesInventoryWindow = true,
                    )
                    assertTrue(
                        recordTeacherReviewApplied(
                            pageToken = "old_page_token",
                            attemptNo = 1,
                            sourceRevision = generation,
                            payloadSha256 = "a".repeat(64),
                            resultLayerSha256 = "b".repeat(64),
                        ),
                    )
                }
            }

            RemotePageSyncStore(file).apply {
                assertEquals(oldGeneration, studentGeneration())
                assertTrue(fenceRecoveredStudentGeneration())
                assertEquals(0L, studentGeneration())
                assertNull(outstandingStudentManifest())
                assertEquals(0L, appliedTeacherReviewRevision("old_page_token", 1))
                assertFalse(fenceRecoveredStudentGeneration())
                assertTrue(beginStudentGeneration() > oldGeneration)
            }
        }

    @Test
    fun deletedWorkbookMappingStaysExplicitAcrossRestartUntilUserRebinds() =
        withStoreFile { file ->
            val token = "workbook_token_a"
            val digest = "a".repeat(64)
            val page = teacherPages(syncGeneration = 1L, count = 1).single().copy(
                workbookToken = token,
                contentSha256 = digest,
                localBookId = "deleted_local_book",
            )
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(1L, 1L, listOf(page), null, 1),
                )
                rememberWorkbookMapping(token, "deleted_local_book", digest)
                assertTrue(unbindMissingTeacherWorkbook(token, digest))
                assertTrue(requiresExplicitWorkbookMapping(token, digest))
                assertEquals(null, mappedLocalBookId(token, digest))
                assertEquals(null, teacherPage(page.pageToken)?.localBookId)
            }

            RemotePageSyncStore(file).apply {
                assertTrue(requiresExplicitWorkbookMapping(token, digest))
                // Automatic manifest inference must never clear the durable latch.
                rememberWorkbookMapping(token, "automatic_candidate", digest)
                assertTrue(requiresExplicitWorkbookMapping(token, digest))
                assertEquals(null, mappedLocalBookId(token, digest))
                assertTrue(runCatching {
                    rebindTeacherWorkbook(
                        workbookToken = token,
                        localBookId = "confirmed_local_book",
                        contentSha256 = digest,
                        workbookLabel = "Confirmed",
                        localStudentLayerSha256ByPageToken = emptyMap(),
                    )
                }.isFailure)
                assertTrue(requiresExplicitWorkbookMapping(token, digest))
                assertEquals(null, mappedLocalBookId(token, digest))
                assertEquals(null, teacherPage(page.pageToken)?.localBookId)
                rebindTeacherWorkbook(
                    workbookToken = token,
                    localBookId = "confirmed_local_book",
                    contentSha256 = digest,
                    workbookLabel = "Confirmed",
                    localStudentLayerSha256ByPageToken = mapOf(
                        page.pageToken to page.studentLayerSha256,
                    ),
                )
                assertFalse(requiresExplicitWorkbookMapping(token, digest))
                assertEquals("confirmed_local_book", mappedLocalBookId(token, digest))
            }

            RemotePageSyncStore(file).apply {
                assertFalse(requiresExplicitWorkbookMapping(token, digest))
                assertEquals("confirmed_local_book", mappedLocalBookId(token, digest))
                bindPair("pair_inventory_0002")
                assertFalse(requiresExplicitWorkbookMapping(token, digest))
                assertEquals(null, mappedLocalBookId(token, digest))
            }
            RemotePageSyncStore(file).apply {
                assertEquals("pair_inventory_0002", currentPairId())
                assertFalse(requiresExplicitWorkbookMapping(token, digest))
                assertEquals(null, mappedLocalBookId(token, digest))
            }
        }

    @Test
    fun pairScopedLiveEventCanUpgradeButNeverDowngradeSameHeldIntent() =
        withStoreFile { file ->
            val held = PendingTeacherReviewRecord(
                intentId = "publication_1",
                bookId = "local_book",
                contentSha256 = "a".repeat(64),
                workbookToken = null,
                deferredWorkbookBinding = false,
                pageNumber = 3,
                attemptNo = 2,
                queuedAtEpochMs = 10L,
            )
            val live = held.copy(
                deferredWorkbookBinding = true,
                deferredAfterManifestGeneration = 4L,
                deferredAfterManifestSequence = 7L,
            )
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                queueTeacherReview(held)
                queueTeacherReview(live)
                assertTrue(pendingTeacherReviews().single().deferredWorkbookBinding)

                // The next periodic journal scan carries the held form again. It must not erase
                // the live pair provenance captured above.
                queueTeacherReview(held)
                val retained = pendingTeacherReviews().single()
                assertTrue(retained.deferredWorkbookBinding)
                assertEquals(4L, retained.deferredAfterManifestGeneration)
                assertEquals(7L, retained.deferredAfterManifestSequence)
            }
        }

    @Test
    fun multiWindowInventoryOnlyCompletesAfterEveryUniquePageAndSurvivesRestart() =
        withStoreFile { file ->
            val allPages = teacherPages(syncGeneration = 7L, count = 96)
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)

                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(
                        syncGeneration = 7L,
                        sequence = 1L,
                        pages = allPages.take(47),
                        cursor = null,
                        inventoryPageCount = allPages.size,
                    ),
                )
                assertEquals(96, teacherExpectedInventoryPageCount())
                assertEquals(47, teacherDiscoveredInventoryPageCount())
                assertFalse(teacherInventoryComplete())
            }

            RemotePageSyncStore(file).apply {
                assertEquals(7L, teacherManifestGeneration())
                assertEquals(1L, teacherManifestSequence())
                assertEquals(96, teacherExpectedInventoryPageCount())
                assertEquals(47, teacherDiscoveredInventoryPageCount())
                assertFalse(teacherInventoryComplete())

                // The third window can arrive before the second. It advances the manifest
                // high-water, but 49 distinct rows are still nowhere near a complete inventory.
                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(
                        syncGeneration = 7L,
                        sequence = 3L,
                        pages = allPages.drop(94),
                        cursor = null,
                        inventoryPageCount = allPages.size,
                    ),
                )
                assertEquals(49, teacherDiscoveredInventoryPageCount())
                assertFalse(teacherInventoryComplete())

                // Reusing the accepted sequence with unseen rows is not allowed to smuggle a
                // second window into the durable inventory.
                assertEquals(
                    TeacherManifestInstallResult.DUPLICATE,
                    replaceTeacherManifest(
                        syncGeneration = 7L,
                        sequence = 3L,
                        pages = allPages.drop(47).take(47),
                        cursor = null,
                        inventoryPageCount = allPages.size,
                    ),
                )
                assertEquals(49, teacherDiscoveredInventoryPageCount())
                assertFalse(teacherInventoryComplete())

                // The genuinely older second window is stale after the third one arrived. A later
                // manifest cycle must resend it with a new sequence before completion is claimed.
                assertEquals(
                    TeacherManifestInstallResult.STALE,
                    replaceTeacherManifest(
                        syncGeneration = 7L,
                        sequence = 2L,
                        pages = allPages.drop(47).take(47),
                        cursor = null,
                        inventoryPageCount = allPages.size,
                    ),
                )
                assertEquals(49, teacherDiscoveredInventoryPageCount())
                assertFalse(teacherInventoryComplete())

                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(
                        syncGeneration = 7L,
                        sequence = 4L,
                        pages = allPages.drop(47).take(47).reversed(),
                        cursor = null,
                        inventoryPageCount = allPages.size,
                    ),
                )
                assertEquals(96, teacherDiscoveredInventoryPageCount())
                assertTrue(teacherInventoryComplete())
            }

            RemotePageSyncStore(file).apply {
                assertEquals(7L, teacherManifestGeneration())
                assertEquals(4L, teacherManifestSequence())
                assertEquals(96, teacherExpectedInventoryPageCount())
                assertEquals(96, teacherDiscoveredInventoryPageCount())
                assertTrue(teacherInventoryComplete())
            }
        }

    @Test
    fun previousGenerationRowsCannotSatisfyNewGenerationExpectedCount() =
        withStoreFile { file ->
            val oldPages = teacherPages(syncGeneration = 10L, count = 49)
            val newPages = teacherPages(syncGeneration = 11L, count = 49)
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(10L, 1L, oldPages, null, oldPages.size),
                )
                assertEquals(49, teacherDiscoveredInventoryPageCount())
                assertTrue(teacherInventoryComplete())

                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(11L, 1L, newPages.take(47), null, newPages.size),
                )
                assertEquals(11L, teacherManifestGeneration())
                assertEquals(49, teacherExpectedInventoryPageCount())
                assertEquals(47, teacherDiscoveredInventoryPageCount())
                assertFalse(teacherInventoryComplete())
                assertTrue(teacherPages().all { it.syncGeneration == 11L })
            }

            RemotePageSyncStore(file).apply {
                assertEquals(11L, teacherManifestGeneration())
                assertEquals(49, teacherExpectedInventoryPageCount())
                assertEquals(47, teacherDiscoveredInventoryPageCount())
                assertFalse(teacherInventoryComplete())
                assertTrue(teacherPages().all { it.syncGeneration == 11L })

                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(11L, 2L, newPages.drop(47), null, newPages.size),
                )
                assertEquals(49, teacherDiscoveredInventoryPageCount())
                assertTrue(teacherInventoryComplete())
            }
        }

    @Test
    fun lanParkKeepsOnlyExactStudentLayerEvidenceAcrossGenerationAndRestart() =
        withStoreFile { file ->
            val old = teacherPages(syncGeneration = 7L, count = 2).map { page ->
                page.copy(
                    sourceRevision = 99L,
                    appliedRevision = 99L,
                    appliedStudentLayerSha256 = page.studentLayerSha256,
                )
            }
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(7L, 1L, old, null, old.size),
                )
                clearTeacherManifestPagesForLan()
                assertTrue(teacherPages().isEmpty())
                assertEquals(7L, teacherManifestGeneration())
                assertEquals(
                    old.first().studentLayerSha256,
                    teacherStudentLayerEvidence(
                        old.first().workbookToken,
                        old.first().contentSha256,
                        old.first().localBookId,
                        old.first().pageNumber,
                    )?.studentLayerSha256,
                )
                assertEquals(
                    null,
                    teacherStudentLayerEvidence(
                        old.first().workbookToken,
                        old.first().contentSha256,
                        old.first().localBookId,
                        old.first().pageNumber + 100,
                    ),
                )
            }

            RemotePageSyncStore(file).apply {
                val evidence = requireNotNull(
                    teacherStudentLayerEvidence(
                        old.first().workbookToken,
                        old.first().contentSha256,
                        old.first().localBookId,
                        old.first().pageNumber,
                    ),
                )
                val next = old.first().copy(
                    syncGeneration = 8L,
                    pageToken = "generation_8_page_00000",
                    sourceRevision = 1L,
                    manifestRevision = 1L,
                    appliedRevision = 0L,
                    appliedStudentLayerSha256 = evidence.studentLayerSha256,
                    verificationPending = true,
                )
                assertEquals(
                    TeacherManifestInstallResult.APPLIED,
                    replaceTeacherManifest(8L, 1L, listOf(next), null, 1),
                )
                assertTrue(pendingTeacherPages().isEmpty())
                assertEquals(
                    null,
                    reserveTeacherRequest(next.pageToken, "request_blocked_by_verify", 1L, 1L, 0L, true)
                        ?.requestTransferId,
                )
                assertTrue(
                    verifyTeacherPage(
                        pageToken = next.pageToken,
                        expectedSyncGeneration = 8L,
                        expectedSourceRevision = 1L,
                        expectedStudentLayerSha256 = next.studentLayerSha256,
                        observedLocalStudentLayerSha256 = next.studentLayerSha256,
                    ),
                )
                val verified = requireNotNull(teacherPage(next.pageToken))
                assertFalse(verified.verificationPending)
                assertEquals(1L, verified.appliedRevision)
                assertFalse(verified.pending)
            }
        }

    @Test
    fun boundedVerificationMismatchExposesOnlyTheChangedPageAsPending() =
        withStoreFile { file ->
            val pages = teacherPages(syncGeneration = 3L, count = 2).map { page ->
                page.copy(
                    appliedRevision = page.sourceRevision,
                    appliedStudentLayerSha256 = page.studentLayerSha256,
                )
            }
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                replaceTeacherManifest(3L, 1L, pages, null, pages.size)
                val marked = markTeacherPagesForVerification()
                assertEquals(2, marked.size)
                assertTrue(pendingTeacherPages().isEmpty())

                assertTrue(
                    verifyTeacherPage(
                        pageToken = pages.first().pageToken,
                        expectedSyncGeneration = 3L,
                        expectedSourceRevision = pages.first().sourceRevision,
                        expectedStudentLayerSha256 = pages.first().studentLayerSha256,
                        observedLocalStudentLayerSha256 = "locally_changed_layer",
                    ),
                )
                assertEquals(listOf(pages.first().pageToken), pendingTeacherPages().map { it.pageToken })
                assertTrue(requireNotNull(teacherPage(pages.last().pageToken)).verificationPending)
            }
        }

    @Test
    fun unreadableVerificationBecomesActionableAndDoesNotStarveTheNextPage() =
        withStoreFile { file ->
            val pages = teacherPages(syncGeneration = 6L, count = 2).map { page ->
                page.copy(
                    appliedRevision = page.sourceRevision,
                    appliedStudentLayerSha256 = page.studentLayerSha256,
                )
            }
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                replaceTeacherManifest(6L, 1L, pages, null, pages.size)
                markTeacherPagesForVerification()

                assertTrue(
                    verifyTeacherPage(
                        pageToken = pages.first().pageToken,
                        expectedSyncGeneration = 6L,
                        expectedSourceRevision = pages.first().sourceRevision,
                        expectedStudentLayerSha256 = pages.first().studentLayerSha256,
                        observedLocalStudentLayerSha256 = null,
                    ),
                )
                val failed = requireNotNull(teacherPage(pages.first().pageToken))
                assertFalse(failed.verificationPending)
                assertTrue(failed.pending)
                assertTrue(failed.forceCheckpoint)

                assertTrue(
                    verifyTeacherPage(
                        pageToken = pages.last().pageToken,
                        expectedSyncGeneration = 6L,
                        expectedSourceRevision = pages.last().sourceRevision,
                        expectedStudentLayerSha256 = pages.last().studentLayerSha256,
                        observedLocalStudentLayerSha256 = pages.last().studentLayerSha256,
                    ),
                )
                assertFalse(requireNotNull(teacherPage(pages.last().pageToken)).pending)
                assertTrue(teacherPages().none(TeacherPageSyncRecord::verificationPending))
            }
        }

    @Test
    fun pairChangeClearsParkedStudentLayerEvidence() =
        withStoreFile { file ->
            val page = teacherPages(syncGeneration = 2L, count = 1).single().let { value ->
                value.copy(
                    appliedRevision = value.sourceRevision,
                    appliedStudentLayerSha256 = value.studentLayerSha256,
                )
            }
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                replaceTeacherManifest(2L, 1L, listOf(page), null, 1)
                clearTeacherManifestPagesForLan()
                bindPair("pair_inventory_0002")
                assertEquals(
                    null,
                    teacherStudentLayerEvidence(
                        page.workbookToken,
                        page.contentSha256,
                        page.localBookId,
                        page.pageNumber,
                    ),
                )
            }
        }

    @Test
    fun versionFourJournalMigratesWithEvidenceUnknownUntilBoundedCapture() =
        withStoreFile { file ->
            val page = teacherPages(syncGeneration = 4L, count = 1).single().let { value ->
                value.copy(
                    appliedRevision = value.sourceRevision,
                    appliedStudentLayerSha256 = value.studentLayerSha256,
                )
            }
            RemotePageSyncStore(file).apply {
                bindPair(PAIR_ID)
                assertEquals(1L, beginStudentGeneration())
                rememberWorkbookMapping(
                    page.workbookToken,
                    requireNotNull(page.localBookId),
                    page.contentSha256,
                )
                replaceTeacherManifest(4L, 1L, listOf(page), null, 1)
            }
            val v5 = file.readText(Charsets.UTF_8)
            file.writeText(v5.replaceFirst("\"version\":5", "\"version\":4"), Charsets.UTF_8)

            RemotePageSyncStore(file).apply {
                assertEquals(PAIR_ID, currentPairId())
                assertEquals(1L, studentGeneration())
                assertEquals(
                    page.localBookId,
                    mappedLocalBookId(page.workbookToken, page.contentSha256),
                )
                val migrated = requireNotNull(teacherPage(page.pageToken))
                assertFalse(migrated.verificationPending)
                assertEquals(page.appliedRevision, migrated.appliedRevision)
                assertEquals(
                    null,
                    teacherStudentLayerEvidence(
                        page.workbookToken,
                        page.contentSha256,
                        page.localBookId,
                        page.pageNumber,
                    ),
                )
                assertEquals(1, markTeacherPagesForVerification().size)
                assertTrue(
                    verifyTeacherPage(
                        pageToken = page.pageToken,
                        expectedSyncGeneration = page.syncGeneration,
                        expectedSourceRevision = page.sourceRevision,
                        expectedStudentLayerSha256 = page.studentLayerSha256,
                        observedLocalStudentLayerSha256 = page.studentLayerSha256,
                    ),
                )
            }
            assertTrue(file.readText(Charsets.UTF_8).contains("\"version\":5"))

            RemotePageSyncStore(file).apply {
                assertEquals(1L, studentGeneration())
                assertEquals(
                    page.localBookId,
                    mappedLocalBookId(page.workbookToken, page.contentSha256),
                )
                assertEquals(
                    page.studentLayerSha256,
                    teacherStudentLayerEvidence(
                        page.workbookToken,
                        page.contentSha256,
                        page.localBookId,
                        page.pageNumber,
                    )?.studentLayerSha256,
                )
            }
        }

    private fun teacherPages(syncGeneration: Long, count: Int): List<TeacherPageSyncRecord> =
        List(count) { pageNumber ->
            val suffix = pageNumber.toString().padStart(5, '0')
            TeacherPageSyncRecord(
                syncGeneration = syncGeneration,
                pageToken = "generation_${syncGeneration}_page_$suffix",
                workbookToken = "workbook_generation_$syncGeneration",
                contentSha256 = "content_generation_$syncGeneration",
                studentLayerSha256 = "layer_generation_${syncGeneration}_$suffix",
                workbookLabel = "Workbook $syncGeneration",
                localBookId = "local_book_generation_$syncGeneration",
                pageNumber = pageNumber,
                attemptNos = listOf(1),
                submittedAttemptNos = emptyList(),
                sourceRevision = 1L,
                appliedRevision = 0L,
                appliedStudentLayerSha256 = null,
                lastChangedAtEpochMs = pageNumber.toLong(),
                approximateBytes = 1_024L,
            )
        }

    private inline fun withStoreFile(block: (File) -> Unit) {
        val root = createTempDirectory("remote-page-sync-inventory").toFile()
        try {
            block(File(root, "page-sync.json"))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val PAIR_ID = "pair_inventory_0001"
    }
}
