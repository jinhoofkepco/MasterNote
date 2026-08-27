package com.studyink.app

import com.studyink.monitor.core.pageAnnotationSha256Hex
import java.io.IOException
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePageSyncChunkStoreTest {
    @Test
    fun studentFragmentReservationSurvivesRestartUntilGroupSemanticAck() {
        val directory = createTempDirectory("page-sync-student-reservation").toFile()
        try {
            val journal = directory.resolve("sync.json")
            val store = RemotePageSyncStore(journal)
            store.bindPair("pair_0001")
            val generation = store.beginStudentGeneration()
            store.updateStudentPage(
                expectedSyncGeneration = generation,
                pageToken = "page_token_0001",
                workbookToken = "workbook_token_0001",
                bookId = "book_0001",
                contentSha256 = "b".repeat(64),
                studentLayerSha256 = "a".repeat(64),
                workbookLabel = "Workbook",
                pageNumber = 81,
                attemptNos = listOf(1),
                submittedAttemptNos = emptyList(),
                originDeviceHighWater = 9L,
                lastChangedAtEpochMs = 10L,
                approximateBytes = 2_500_000L,
            )
            store.markStudentAnnotationInFlight(
                pageToken = "page_token_0001",
                requestTransferId = "request_transfer_0001",
                annotationTransferId = "checkpoint_group_0001",
                annotationChunkTransferIds = listOf("chunk_0001", "chunk_0002"),
                sourceRevision = 1L,
                originCursor = 9L,
                stateFingerprint = requireNotNull(store.studentPage("page_token_0001")).stateFingerprint,
                resultLayerSha256 = "a".repeat(64),
                sentAtEpochMs = 20L,
            )

            val restarted = RemotePageSyncStore(journal)
            val reserved = requireNotNull(restarted.studentPage("page_token_0001"))
            assertTrue(reserved.outgoingAnnotationTransferId == "checkpoint_group_0001")
            assertTrue(reserved.outgoingAnnotationChunkTransferIds == listOf("chunk_0001", "chunk_0002"))
            assertTrue(
                restarted.resolveStudentAnnotationAck(
                    generation,
                    "page_token_0001",
                    "checkpoint_group_0001",
                    1L,
                    accepted = true,
                ),
            )
            assertTrue(requireNotNull(restarted.studentPage("page_token_0001"))
                .outgoingAnnotationChunkTransferIds.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun outOfOrderFragmentsSurviveRestartAndAssembleExactlyOnce() {
        val directory = createTempDirectory("page-sync-chunks").toFile()
        try {
            val journal = directory.resolve("sync.json")
            val first = "first|".toByteArray()
            val second = "second".toByteArray()
            val assembled = first + second
            val descriptor = descriptor(assembled)

            val initial = RemotePageSyncStore(journal)
            assertTrue(
                initial.offerTeacherPageChunk(
                    descriptor,
                    1,
                    pageAnnotationSha256Hex(second),
                    second,
                ) is TeacherPageChunkOfferResult.Partial,
            )

            val restarted = RemotePageSyncStore(journal)
            val complete = restarted.offerTeacherPageChunk(
                descriptor,
                0,
                pageAnnotationSha256Hex(first),
                first,
            ) as TeacherPageChunkOfferResult.Complete
            assertArrayEquals(assembled, complete.assembledPayload)

            // An exact duplicate is harmless and reconstructs the same semantic group.
            val duplicate = restarted.offerTeacherPageChunk(
                descriptor,
                1,
                pageAnnotationSha256Hex(second),
                second,
            ) as TeacherPageChunkOfferResult.Complete
            assertArrayEquals(assembled, duplicate.assembledPayload)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun duplicateIndexWithDifferentBytesIsRejected() {
        val directory = createTempDirectory("page-sync-chunk-mismatch").toFile()
        try {
            val first = "first|".toByteArray()
            val second = "second".toByteArray()
            val descriptor = descriptor(first + second)
            val store = RemotePageSyncStore(directory.resolve("sync.json"))
            store.offerTeacherPageChunk(descriptor, 0, pageAnnotationSha256Hex(first), first)

            val changed = "other!".toByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                store.offerTeacherPageChunk(
                    descriptor,
                    0,
                    pageAnnotationSha256Hex(changed),
                    changed,
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedAtomicWriteCanRetryWithoutAcceptingPartialBytes() {
        val directory = createTempDirectory("page-sync-chunk-write-failure").toFile()
        try {
            var writes = 0
            var fail = true
            val journal = directory.resolve("sync.json")
            val first = "first|".toByteArray()
            val second = "second".toByteArray()
            val descriptor = descriptor(first + second)
            val failing = RemotePageSyncStore(journal, beforeChunkWrite = {
                writes++
                if (fail && writes == 2) throw IOException("simulated full disk")
            })
            assertThrows(IOException::class.java) {
                failing.offerTeacherPageChunk(
                    descriptor,
                    0,
                    pageAnnotationSha256Hex(first),
                    first,
                )
            }

            fail = false
            assertTrue(
                failing.offerTeacherPageChunk(
                    descriptor,
                    0,
                    pageAnnotationSha256Hex(first),
                    first,
                ) is TeacherPageChunkOfferResult.Partial,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun descriptor(payload: ByteArray) = TeacherPageChunkDescriptor(
        syncGeneration = 4L,
        chunkGroupId = "checkpoint_group_0001",
        responseToTransferId = "request_transfer_0001",
        pageToken = "page_token_0001",
        pageNumber = 82,
        attemptNos = listOf(1, 2),
        sourceRevision = 9L,
        resultLayerSha256 = "a".repeat(64),
        payloadSha256 = pageAnnotationSha256Hex(payload),
        chunkCount = 2,
        assembledPayloadSizeBytes = payload.size,
    )
}
