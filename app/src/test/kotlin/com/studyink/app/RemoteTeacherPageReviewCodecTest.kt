package com.studyink.app

import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTeacherPageReviewCodecTest {
    @Test fun roundTripIsLosslessAndIncludesHiddenTombstonesWithoutBase64Expansion() {
        val checkpoint = ByteArray(257) { index -> (index * 31).toByte() }
        val groups = listOf(
            MarkGroup(
                id = "mark-group-visible-0001",
                bookId = BOOK_ID,
                pageNumber = CORE_PAGE,
                anchor = PagePoint(123.4567f, 98_765.125f, 0.375f),
                marks = listOf(
                    Mark(
                        attemptNo = 2,
                        color = MarkColor.BLUE,
                        gradedAtEpochMillis = 1L,
                    ),
                    Mark(
                        attemptNo = 2,
                        color = MarkColor.RED,
                        gradedAtEpochMillis = Long.MAX_VALUE,
                        hiddenAtEpochMillis = 5_000L,
                    ),
                    Mark(
                        attemptNo = 2,
                        color = MarkColor.GRAY,
                        gradedAtEpochMillis = 6_000L,
                    ),
                ),
                createdAtEpochMillis = 700L,
                syncRevision = Long.MAX_VALUE,
                lastModifiedByDeviceId = "teacher-device-0001",
            ),
            MarkGroup(
                id = "mark-group-tombstone-0002",
                bookId = BOOK_ID,
                pageNumber = CORE_PAGE,
                anchor = PagePoint(999.75f, 1_000_000f, 1f),
                marks = listOf(
                    Mark(
                        attemptNo = 2,
                        color = MarkColor.GRAY,
                        gradedAtEpochMillis = 8_000L,
                        hiddenAtEpochMillis = 9_000L,
                    ),
                ),
                createdAtEpochMillis = 7_000L,
                hiddenAtEpochMillis = 10_000L,
                syncRevision = 4L,
                lastModifiedByDeviceId = "teacher-device-0001",
            ),
        )

        val encoded = RemoteTeacherPageReviewCodec.encode(
            pageNumber = WIRE_PAGE,
            attemptNo = 2,
            publishedTeacherCheckpoint = checkpoint,
            markGroups = groups,
        )
        val decoded = RemoteTeacherPageReviewCodec.decode(encoded)

        assertEquals(RemoteTeacherPageReviewCodec.VERSION, decoded.version)
        assertEquals(WIRE_PAGE, decoded.pageNumber)
        assertEquals(2, decoded.attemptNo)
        assertArrayEquals(checkpoint, decoded.copyPublishedTeacherCheckpoint())
        assertEquals(groups, decoded.markGroups)
        assertEquals(
            groups.first().anchor.x.toRawBits(),
            decoded.markGroups.first().anchor.x.toRawBits(),
        )
        assertEquals(
            groups.first().anchor.pressure.toRawBits(),
            decoded.markGroups.first().anchor.pressure.toRawBits(),
        )
        assertArrayEquals(checkpoint, encoded.checkpointBytes())
        assertTrue(decoded.markGroups.last().hiddenAtEpochMillis != null)
        assertTrue(decoded.markGroups.last().marks.single().hiddenAtEpochMillis != null)
    }

    @Test fun decodedCheckpointAndNestedCollectionsCannotBeMutatedThroughReturnedValues() {
        val expectedCheckpoint = byteArrayOf(1, 2, 3, 4)
        val mutableMarks = mutableListOf(mark(attemptNo = 1))
        val mutableGroups = mutableListOf(group(marks = mutableMarks))
        val decoded = RemoteTeacherPageReviewCodec.decode(
            RemoteTeacherPageReviewCodec.encode(
                pageNumber = WIRE_PAGE,
                attemptNo = 1,
                publishedTeacherCheckpoint = expectedCheckpoint,
                markGroups = mutableGroups,
            ),
        )

        val leakedCheckpoint = decoded.copyPublishedTeacherCheckpoint()
        leakedCheckpoint[0] = 99
        assertArrayEquals(expectedCheckpoint, decoded.copyPublishedTeacherCheckpoint())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (decoded.markGroups as MutableList<MarkGroup>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (decoded.markGroups.single().marks as MutableList<Mark>).clear()
        }

        mutableMarks.clear()
        mutableGroups.clear()
        assertEquals(1, decoded.markGroups.size)
        assertEquals(1, decoded.markGroups.single().marks.size)
    }

    @Test fun exactPageAttemptCheckpointAndPageOwnershipAreStrict() {
        assertRejected {
            encode(pageNumber = 0)
        }
        assertRejected {
            encode(pageNumber = 1_000_001)
        }
        assertRejected {
            encode(attemptNo = 0)
        }
        assertRejected {
            encode(attemptNo = 1_000_001)
        }
        assertRejected {
            encode(checkpoint = ByteArray(0))
        }
        assertRejected {
            encode(groups = listOf(group(pageNumber = CORE_PAGE + 1)))
        }
        assertRejected {
            encode(groups = listOf(group(bookId = BOOK_ID), group(id = "mark-group-0002", bookId = "book-other-0001")))
        }
        assertRejected {
            encode(groups = listOf(group(), group()))
        }
    }

    @Test fun idsTimesRevisionAttemptsAndHistoryShapeAreStrict() {
        assertRejected { encode(groups = listOf(group(id = "bad id"))) }
        assertRejected { encode(groups = listOf(group(id = "a".repeat(257)))) }
        assertRejected { encode(groups = listOf(group(bookId = ""))) }
        assertRejected { encode(groups = listOf(group(createdAt = -1L))) }
        assertRejected { encode(groups = listOf(group(hiddenAt = -1L))) }
        assertRejected { encode(groups = listOf(group(syncRevision = -1L))) }
        assertRejected {
            encode(groups = listOf(group(syncRevision = 1L, lastModifiedByDeviceId = "")))
        }
        assertRejected { encode(groups = listOf(group(marks = emptyList()))) }
        assertRejected {
            encode(groups = listOf(group(marks = listOf(mark(attemptNo = -1)))))
        }
        assertRejected {
            encode(groups = listOf(group(marks = listOf(mark(attemptNo = 1_000_001)))))
        }
        assertRejected {
            encode(groups = listOf(group(marks = listOf(mark(gradedAt = -1L)))))
        }
        assertRejected {
            encode(groups = listOf(group(marks = listOf(mark(hiddenAt = -1L)))))
        }
        assertRejected {
            encode(
                groups = listOf(
                    group(
                        marks = listOf(
                            mark(attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO),
                            mark(attemptNo = 1),
                        ),
                    ),
                ),
            )
        }
    }

    @Test fun nonFiniteOrOutOfPageCoordinatesAreRejected() {
        listOf(
            PagePoint(Float.NaN, 1f, 1f),
            PagePoint(Float.POSITIVE_INFINITY, 1f, 1f),
            PagePoint(-0.01f, 1f, 1f),
            PagePoint(1_000.01f, 1f, 1f),
            PagePoint(1f, -0.01f, 1f),
            PagePoint(1f, 1_000_000.1f, 1f),
            PagePoint(1f, 1f, -0.01f),
            PagePoint(1f, 1f, 1.01f),
        ).forEach { anchor ->
            assertRejected { encode(groups = listOf(group(anchor = anchor))) }
        }
    }

    @Test fun decoderRejectsWrongVersionPageAttemptColorCoordinatesAndRevision() {
        assertRejectedDecode(validEncoded().copyWithInt(VERSION_OFFSET, 2))
        assertRejectedDecode(validEncoded().copyWithInt(PAGE_OFFSET, 0))
        assertRejectedDecode(validEncoded().copyWithInt(ATTEMPT_OFFSET, 0))

        val valid = validEncoded()
        val offsets = valid.firstGroupOffsets()
        assertRejectedDecode(valid.copyWithInt(offsets.corePage, CORE_PAGE + 1))
        assertRejectedDecode(valid.copyWithInt(offsets.anchorX, Float.NaN.toRawBits()))
        assertRejectedDecode(valid.copyWithLong(offsets.createdAt, -1L))
        assertRejectedDecode(valid.copyWithLong(offsets.syncRevision, -1L))
        assertRejectedDecode(valid.copyOf().also { it[offsets.firstMarkColor] = 99 })
        assertRejectedDecode(valid.copyWithInt(offsets.firstMarkAttempt, -1))
    }

    @Test fun decoderRejectsMalformedIdsUtf8LengthsTruncationAndTrailingSchema() {
        val valid = validEncoded()
        val offsets = valid.firstGroupOffsets()
        assertRejectedDecode(valid.copyOf().also { it[offsets.idBytes] = ' '.code.toByte() })
        assertRejectedDecode(valid.copyOf().also {
            it[offsets.idBytes] = 0xc3.toByte()
            it[offsets.idBytes + 1] = 0x28
        })
        assertRejectedDecode(valid.copyWithInt(CHECKPOINT_LENGTH_OFFSET, Int.MAX_VALUE))
        assertRejectedDecode(valid.copyOf(valid.size - 1))
        assertRejectedDecode(valid + byteArrayOf(0))
        assertRejectedDecode(ByteArray(RemoteTeacherPageReviewCodec.MAX_ENCODED_BYTES + 1))
    }

    @Test fun maximumCheckpointFitsExactlyAndAnyAdditionalSchemaByteIsRejected() {
        val maximumCheckpoint = ByteArray(
            RemoteTeacherPageReviewCodec.MAX_PUBLISHED_CHECKPOINT_BYTES,
        ) { index -> index.toByte() }
        val encoded = encode(checkpoint = maximumCheckpoint, groups = emptyList())

        assertEquals(RemoteTeacherPageReviewCodec.MAX_ENCODED_BYTES, encoded.size)
        assertArrayEquals(
            maximumCheckpoint,
            RemoteTeacherPageReviewCodec.decode(encoded).copyPublishedTeacherCheckpoint(),
        )
        assertRejected {
            encode(checkpoint = maximumCheckpoint + byteArrayOf(1), groups = emptyList())
        }
        assertRejected {
            encode(checkpoint = maximumCheckpoint, groups = listOf(group()))
        }
    }

    @Test fun maliciousDecodedGroupPageIdTimeAndNullableMarkersAreValidatedIndependently() {
        val valid = validEncoded()
        val offsets = valid.firstGroupOffsets()
        assertRejectedDecode(valid.copyOf().also { it[offsets.groupHiddenMarker] = 2 })
        assertRejectedDecode(valid.copyOf().also { it[offsets.firstMarkHiddenMarker] = 2 })
        assertRejectedDecode(valid.copyOf().also { it[offsets.lastModifiedIdBytes] = ' '.code.toByte() })
    }

    private fun validEncoded(): ByteArray = encode(groups = listOf(group()))

    private fun encode(
        pageNumber: Int = WIRE_PAGE,
        attemptNo: Int = 2,
        checkpoint: ByteArray = byteArrayOf(1, 2, 3),
        groups: List<MarkGroup> = listOf(group()),
    ): ByteArray = RemoteTeacherPageReviewCodec.encode(
        pageNumber = pageNumber,
        attemptNo = attemptNo,
        publishedTeacherCheckpoint = checkpoint,
        markGroups = groups,
    )

    private fun group(
        id: String = "mark-group-0001",
        bookId: String = BOOK_ID,
        pageNumber: Int = CORE_PAGE,
        anchor: PagePoint = PagePoint(100f, 200f, 0.5f),
        marks: List<Mark> = listOf(mark()),
        createdAt: Long = 100L,
        hiddenAt: Long? = null,
        syncRevision: Long = 3L,
        lastModifiedByDeviceId: String = "teacher-device-0001",
    ) = MarkGroup(
        id = id,
        bookId = bookId,
        pageNumber = pageNumber,
        anchor = anchor,
        marks = marks,
        createdAtEpochMillis = createdAt,
        hiddenAtEpochMillis = hiddenAt,
        syncRevision = syncRevision,
        lastModifiedByDeviceId = lastModifiedByDeviceId,
    )

    private fun mark(
        attemptNo: Int = 2,
        color: MarkColor = MarkColor.BLUE,
        gradedAt: Long = 200L,
        hiddenAt: Long? = null,
    ) = Mark(
        attemptNo = attemptNo,
        color = color,
        gradedAtEpochMillis = gradedAt,
        hiddenAtEpochMillis = hiddenAt,
    )

    private fun assertRejected(block: () -> Unit) {
        assertThrows(RemoteTeacherPageReviewException::class.java, block)
    }

    private fun assertRejectedDecode(bytes: ByteArray) {
        assertThrows(RemoteTeacherPageReviewException::class.java) {
            RemoteTeacherPageReviewCodec.decode(bytes)
        }
    }

    private fun ByteArray.copyWithInt(offset: Int, value: Int): ByteArray = copyOf().also {
        ByteBuffer.wrap(it).putInt(offset, value)
    }

    private fun ByteArray.copyWithLong(offset: Int, value: Long): ByteArray = copyOf().also {
        ByteBuffer.wrap(it).putLong(offset, value)
    }

    private fun ByteArray.checkpointBytes(): ByteArray {
        val length = ByteBuffer.wrap(this).getInt(CHECKPOINT_LENGTH_OFFSET)
        return copyOfRange(CHECKPOINT_BYTES_OFFSET, CHECKPOINT_BYTES_OFFSET + length)
    }

    private fun ByteArray.firstGroupOffsets(): FirstGroupOffsets {
        val buffer = ByteBuffer.wrap(this)
        val checkpointLength = buffer.getInt(CHECKPOINT_LENGTH_OFFSET)
        var offset = CHECKPOINT_BYTES_OFFSET + checkpointLength
        assertEquals(1, buffer.getInt(offset))
        offset += Int.SIZE_BYTES

        val idLength = buffer.getInt(offset)
        val idBytes = offset + Int.SIZE_BYTES
        offset = idBytes + idLength
        val bookLength = buffer.getInt(offset)
        offset += Int.SIZE_BYTES + bookLength
        val corePage = offset
        val anchorX = corePage + Int.SIZE_BYTES
        val createdAt = anchorX + (3 * Float.SIZE_BYTES)
        val groupHiddenMarker = createdAt + Long.SIZE_BYTES
        assertEquals(0, this[groupHiddenMarker].toInt())
        val syncRevision = groupHiddenMarker + 1
        offset = syncRevision + Long.SIZE_BYTES
        val lastModifiedLength = buffer.getInt(offset)
        val lastModifiedIdBytes = offset + Int.SIZE_BYTES
        offset = lastModifiedIdBytes + lastModifiedLength
        assertEquals(1, buffer.getInt(offset))
        val firstMarkAttempt = offset + Int.SIZE_BYTES
        val firstMarkColor = firstMarkAttempt + Int.SIZE_BYTES
        val firstMarkHiddenMarker = firstMarkColor + 1 + Long.SIZE_BYTES
        return FirstGroupOffsets(
            idBytes = idBytes,
            corePage = corePage,
            anchorX = anchorX,
            createdAt = createdAt,
            groupHiddenMarker = groupHiddenMarker,
            syncRevision = syncRevision,
            lastModifiedIdBytes = lastModifiedIdBytes,
            firstMarkAttempt = firstMarkAttempt,
            firstMarkColor = firstMarkColor,
            firstMarkHiddenMarker = firstMarkHiddenMarker,
        )
    }

    private data class FirstGroupOffsets(
        val idBytes: Int,
        val corePage: Int,
        val anchorX: Int,
        val createdAt: Int,
        val groupHiddenMarker: Int,
        val syncRevision: Int,
        val lastModifiedIdBytes: Int,
        val firstMarkAttempt: Int,
        val firstMarkColor: Int,
        val firstMarkHiddenMarker: Int,
    )

    private companion object {
        const val WIRE_PAGE = 37
        const val CORE_PAGE = WIRE_PAGE - 1
        const val BOOK_ID = "book-00000001"
        const val VERSION_OFFSET = 4
        const val PAGE_OFFSET = 8
        const val ATTEMPT_OFFSET = 12
        const val CHECKPOINT_LENGTH_OFFSET = 16
        const val CHECKPOINT_BYTES_OFFSET = 20
    }
}
