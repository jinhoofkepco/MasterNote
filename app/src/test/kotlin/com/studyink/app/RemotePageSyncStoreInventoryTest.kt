package com.studyink.app

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePageSyncStoreInventoryTest {
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
