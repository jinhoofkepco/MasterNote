package com.studyink.memo.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.ConcurrentModificationException
import java.util.concurrent.atomic.AtomicLong

class StudentMemoRepositoryTest {
    @Test
    fun `single memo decoder enforces the shared transport limit`() {
        val repository = repository(temporary.newFolder("decode-limit")) { MEMO_ONE }

        assertThrows(IllegalArgumentException::class.java) {
            repository.decodeMemo(ByteArray(MemoTransportLimits.MAX_ENCODED_MEMO_BYTES + 1))
        }
    }

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `multiple memos persist independently by exact attempt`() {
        val ids = ArrayDeque(listOf(MEMO_ONE, MEMO_TWO, MEMO_THREE))
        val root = temporary.newFolder("data")
        val repository = repository(root) { ids.removeFirst() }

        val first = repository.create(TARGET_ONE, MemoAnchor(.2f, .3f))
        val second = repository.create(TARGET_ONE, MemoAnchor(.7f, .8f))
        repository.create(TARGET_TWO, MemoAnchor(.5f, .5f))

        assertEquals(listOf(first.id, second.id), repository.activeMemos(TARGET_ONE).map(StudentMemo::id))
        assertEquals(2L, repository.snapshot(TARGET_ONE).revision)
        assertEquals(1, repository.activeMemos(TARGET_TWO).size)
        assertTrue(repository.activeMemos(OTHER_ATTEMPT).isEmpty())

        val reopened = repository(root) { error("No new id expected") }
        assertEquals(first, reopened.memo(TARGET_ONE, first.id))
        assertEquals(second, reopened.memo(TARGET_ONE, second.id))
        assertTrue(reopened.targetFileForTest(TARGET_ONE).path.contains("student-memos-v1"))
    }

    @Test
    fun `stroke move and deletion advance memo revision and retain tombstone`() {
        val repository = repository(temporary.newFolder("data")) { MEMO_ONE }
        val created = repository.create(TARGET_ONE, MemoAnchor(.2f, .3f))
        val stroke = stroke(STROKE_ONE)

        val written = repository.replaceStrokes(TARGET_ONE, created.id, created.revision, listOf(stroke))
        val moved = repository.move(TARGET_ONE, created.id, written.revision, MemoAnchor(.8f, .4f))
        val deleted = repository.delete(TARGET_ONE, created.id, moved.revision)

        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(created.revision, written.revision, moved.revision, deleted.revision))
        assertEquals(MemoAnchor(.8f, .4f), deleted.anchor)
        assertTrue(deleted.deleted)
        assertTrue(deleted.strokes.isEmpty())
        assertNull(repository.memo(TARGET_ONE, created.id))
        assertEquals(deleted, repository.memo(TARGET_ONE, created.id, includeDeleted = true))
        assertTrue(repository.activeMemos(TARGET_ONE).isEmpty())
        assertEquals(4L, repository.snapshot(TARGET_ONE).revision)
        assertEquals(deleted, repository.delete(TARGET_ONE, created.id, deleted.revision))
        assertEquals(4L, repository.snapshot(TARGET_ONE).revision)
    }

    @Test
    fun `expected revision rejects stale editor without changing disk`() {
        val repository = repository(temporary.newFolder("data")) { MEMO_ONE }
        val created = repository.create(TARGET_ONE, MemoAnchor(.2f, .3f))
        val moved = repository.move(TARGET_ONE, created.id, created.revision, MemoAnchor(.4f, .5f))
        val before = repository.exportSnapshot(TARGET_ONE)

        assertThrows(ConcurrentModificationException::class.java) {
            repository.replaceStrokes(TARGET_ONE, created.id, created.revision, listOf(stroke(STROKE_ONE)))
        }

        assertEquals(moved, repository.memo(TARGET_ONE, created.id))
        assertArrayEquals(before, repository.exportSnapshot(TARGET_ONE))
    }

    @Test
    fun `export decode remap and authoritative apply preserve exact state`() {
        val source = repository(temporary.newFolder("source")) { MEMO_ONE }
        val created = source.create(TARGET_ONE, MemoAnchor(.2f, .3f))
        source.replaceStrokes(
            TARGET_ONE,
            created.id,
            created.revision,
            listOf(stroke(STROKE_ONE, MemoTool.HIGHLIGHTER)),
        )
        val encoded = source.exportSnapshot(TARGET_ONE)
        val decoded = source.decodeSnapshot(encoded)
        val decodedMemo = source.decodeMemo(source.exportMemo(TARGET_ONE, created.id))
        val localTarget = MemoTarget("teacher-book", 8, 1)
        val remapped = decoded.remapTo(localTarget)

        assertEquals(decoded.revision, remapped.revision)
        assertEquals(localTarget, remapped.target)
        assertEquals(decoded.memos.single().id, remapped.memos.single().id)
        assertEquals(decoded.memos.single().revision, remapped.memos.single().revision)
        assertNotEquals(decoded.memos.single().digestSha256, remapped.memos.single().digestSha256)
        assertEquals(decoded.memos.single(), decodedMemo)
        assertEquals(remapped.memos.single(), decodedMemo.remapTo(localTarget))

        val receiver = repository(temporary.newFolder("receiver")) { error("No id expected") }
        val applied = receiver.applyAuthoritative(remapped)
        assertEquals(MemoAuthoritativeApplyStatus.APPLIED, applied.status)
        assertEquals(remapped, receiver.snapshot(localTarget))
        assertEquals(
            MemoAuthoritativeApplyStatus.ALREADY_CURRENT,
            receiver.applyAuthoritative(remapped).status,
        )
    }

    @Test
    fun `older full snapshot and deleted memo cannot overwrite current state`() {
        val source = repository(temporary.newFolder("source")) { MEMO_ONE }
        val created = source.create(TARGET_ONE, MemoAnchor(.2f, .3f))
        val oldSnapshot = source.snapshot(TARGET_ONE)
        source.delete(TARGET_ONE, created.id, created.revision)
        val deletedSnapshot = source.snapshot(TARGET_ONE)
        val receiver = repository(temporary.newFolder("receiver")) { error("No id expected") }

        assertEquals(MemoAuthoritativeApplyStatus.APPLIED, receiver.applyAuthoritative(deletedSnapshot).status)
        assertEquals(MemoAuthoritativeApplyStatus.STALE, receiver.applyAuthoritative(oldSnapshot).status)
        assertTrue(receiver.snapshot(TARGET_ONE).memos.single().deleted)
        assertEquals(
            MemoAuthoritativeApplyStatus.STALE,
            receiver.applyAuthoritative(oldSnapshot.memos.single()).status,
        )
    }

    @Test
    fun `equal layer revision with different valid content is conflict`() {
        val left = repository(temporary.newFolder("left"), 1_000L) { MEMO_ONE }
        val right = repository(temporary.newFolder("right"), 1_000L) { MEMO_ONE }
        left.create(TARGET_ONE, MemoAnchor(.1f, .2f))
        right.create(TARGET_ONE, MemoAnchor(.8f, .9f))
        val receiver = repository(temporary.newFolder("receiver")) { error("No id expected") }

        assertEquals(MemoAuthoritativeApplyStatus.APPLIED, receiver.applyAuthoritative(left.snapshot(TARGET_ONE)).status)
        val before = receiver.exportSnapshot(TARGET_ONE)
        val conflict = receiver.applyAuthoritative(right.snapshot(TARGET_ONE))

        assertEquals(MemoAuthoritativeApplyStatus.CONFLICT, conflict.status)
        assertTrue(conflict.conflictMemoCount > 0)
        assertArrayEquals(before, receiver.exportSnapshot(TARGET_ONE))
    }

    @Test
    fun `authenticated student memo replaces equal revision fork without accepting stale replay`() {
        val firstSource = repository(temporary.newFolder("first-source"), 1_000L) { MEMO_ONE }
        val restoredSource = repository(temporary.newFolder("restored-source"), 2_000L) { MEMO_ONE }
        val first = firstSource.create(TARGET_ONE, MemoAnchor(.1f, .2f))
        val restored = restoredSource.create(TARGET_ONE, MemoAnchor(.8f, .9f))
        val receiver = repository(temporary.newFolder("authenticated-receiver")) { error("No id expected") }

        assertEquals(MemoAuthoritativeApplyStatus.APPLIED, receiver.applyAuthoritative(first).status)
        assertEquals(
            MemoAuthoritativeApplyStatus.APPLIED,
            receiver.applyAuthenticatedStudentMemo(restored).status,
        )
        assertEquals(restored, receiver.memo(TARGET_ONE, MEMO_ONE))

        // A delayed strict transport cannot flip the teacher cache back to the abandoned fork.
        assertEquals(MemoAuthoritativeApplyStatus.CONFLICT, receiver.applyAuthoritative(first).status)
        assertEquals(restored, receiver.memo(TARGET_ONE, MEMO_ONE))

        val newer = restoredSource.move(TARGET_ONE, MEMO_ONE, restored.revision, MemoAnchor(.6f, .7f))
        assertEquals(
            MemoAuthoritativeApplyStatus.APPLIED,
            receiver.applyAuthenticatedStudentMemo(newer).status,
        )
        assertEquals(
            MemoAuthoritativeApplyStatus.STALE,
            receiver.applyAuthenticatedStudentMemo(first).status,
        )
        assertEquals(newer, receiver.memo(TARGET_ONE, MEMO_ONE))
    }

    @Test
    fun `tampered json digest is rejected`() {
        val repository = repository(temporary.newFolder("data")) { MEMO_ONE }
        repository.create(TARGET_ONE, MemoAnchor(.2f, .3f))
        val root = JSONObject(repository.exportSnapshot(TARGET_ONE).toString(Charsets.UTF_8))
        root.getJSONArray("memos").getJSONObject(0).getJSONObject("anchor").put("normalizedX", .9)

        assertThrows(IllegalArgumentException::class.java) {
            repository.decodeSnapshot(root.toString().toByteArray())
        }
    }

    @Test
    fun `atomic backup is restored after interrupted replacement`() {
        val root = temporary.newFolder("data")
        val repository = repository(root) { MEMO_ONE }
        val created = repository.create(TARGET_ONE, MemoAnchor(.2f, .3f))
        val base = repository.targetFileForTest(TARGET_ONE)
        val backup = File(base.path + ".bak")
        assertTrue(base.renameTo(backup))
        base.writeText("incomplete")

        val reopened = repository(root) { error("No id expected") }
        assertEquals(created, reopened.memo(TARGET_ONE, created.id))
        assertFalse(backup.exists())
    }

    @Test
    fun `inventory survives restart and filters by book`() {
        val ids = ArrayDeque(listOf(MEMO_ONE, MEMO_TWO, MEMO_THREE))
        val root = temporary.newFolder("data")
        val repository = repository(root) { ids.removeFirst() }
        repository.create(TARGET_ONE, MemoAnchor(.1f, .1f))
        repository.create(TARGET_TWO, MemoAnchor(.2f, .2f))
        repository.create(OTHER_ATTEMPT, MemoAnchor(.3f, .3f))

        val reopened = repository(root) { error("No id expected") }
        assertEquals(listOf(TARGET_ONE, OTHER_ATTEMPT, TARGET_TWO), reopened.targets())
        assertEquals(listOf(TARGET_ONE, OTHER_ATTEMPT, TARGET_TWO), reopened.targets(TARGET_ONE.bookId))
    }

    @Test
    fun `change bus fires after durable mutations but not no-ops`() {
        val repository = repository(temporary.newFolder("data")) { MEMO_ONE }
        val changes = mutableListOf<StudentMemoChange>()
        val subscription = StudentMemoChangeBus.addListener(changes::add)
        try {
            val created = repository.create(TARGET_ONE, MemoAnchor(.2f, .3f))
            repository.move(TARGET_ONE, created.id, created.revision, created.anchor)
            val written = repository.replaceStrokes(
                TARGET_ONE,
                created.id,
                created.revision,
                listOf(stroke(STROKE_ONE)),
            )

            assertEquals(listOf(StudentMemoChangeKind.CREATED, StudentMemoChangeKind.STROKES_REPLACED), changes.map { it.kind })
            assertNotNull(repository.memo(TARGET_ONE, written.id))
            assertTrue(repository.targetFileForTest(TARGET_ONE).isFile)
        } finally {
            subscription.close()
        }
    }

    @Test
    fun `oversized single memo is rejected before replacing durable state`() {
        val repository = repository(temporary.newFolder("data")) { MEMO_ONE }
        val created = repository.create(TARGET_ONE, MemoAnchor(.2f, .3f))
        val before = repository.exportSnapshot(TARGET_ONE)
        val oversized = MemoStroke(
            id = STROKE_ONE,
            tool = MemoTool.PEN,
            colorArgb = 0xff102030.toInt(),
            widthFraction = .004f,
            points = List(50_000) { index ->
                val coordinate = (index % 1_000) / 1_000f
                MemoPoint(coordinate, 1f - coordinate, .5f)
            },
            createdAtEpochMillis = 1_000L,
        )

        val error = assertThrows(MemoPayloadTooLargeException::class.java) {
            repository.replaceStrokes(TARGET_ONE, created.id, created.revision, listOf(oversized))
        }

        assertTrue(error.actualBytes > MemoTransportLimits.MAX_ENCODED_MEMO_BYTES)
        assertArrayEquals(before, repository.exportSnapshot(TARGET_ONE))
        assertTrue(repository.memo(TARGET_ONE, created.id)!!.strokes.isEmpty())
    }

    private fun repository(
        root: File,
        startTime: Long = 1_000L,
        uuid: () -> String,
    ): StudentMemoRepository {
        val clock = AtomicLong(startTime)
        return StudentMemoRepository(root, { clock.getAndIncrement() }, uuid)
    }

    private fun stroke(id: String, tool: MemoTool = MemoTool.PEN) = MemoStroke(
        id = id,
        tool = tool,
        colorArgb = 0xff102030.toInt(),
        widthFraction = .004f,
        points = listOf(MemoPoint(.1f, .2f, .5f), MemoPoint(.3f, .4f, .8f)),
        createdAtEpochMillis = 1_000L,
    )

    private companion object {
        val TARGET_ONE = MemoTarget("student-book", 7, 1)
        val TARGET_TWO = MemoTarget("student-book", 8, 1)
        val OTHER_ATTEMPT = MemoTarget("student-book", 7, 2)
        const val MEMO_ONE = "00000000-0000-0000-0000-000000000001"
        const val MEMO_TWO = "00000000-0000-0000-0000-000000000002"
        const val MEMO_THREE = "00000000-0000-0000-0000-000000000003"
        const val STROKE_ONE = "10000000-0000-0000-0000-000000000001"
    }
}
