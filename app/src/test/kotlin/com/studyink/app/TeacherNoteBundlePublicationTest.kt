package com.studyink.app

import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.storage.ConstructionMemoApplyResult
import com.studyink.construction.storage.ConstructionPublishResult
import com.studyink.construction.storage.ConstructionReplicaRole
import com.studyink.construction.storage.ConstructionReplicaStore
import com.studyink.construction.storage.ConstructionSyncCodec
import com.studyink.construction.storage.ConstructionSyncPacket
import com.studyink.construction.storage.ConstructionTarget
import com.studyink.memo.core.MemoAnchor
import com.studyink.memo.core.MemoPoint
import com.studyink.memo.core.MemoStroke
import com.studyink.memo.core.MemoTarget
import com.studyink.memo.core.MemoTool
import com.studyink.memo.core.StudentMemoRepository
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeacherNoteBundlePublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `publishing a teacher note carries parent ink with geometry and exact retries preserve later student edits`() {
        val teacherRoot = temporary.newFolder("teacher")
        val studentRoot = temporary.newFolder("student")
        val clock = AtomicLong(1_000L)
        val teacherMemos = StudentMemoRepository(teacherRoot, { clock.getAndIncrement() }, { MEMO_ID }, teacherDraft = true)
        val studentMemos = StudentMemoRepository(studentRoot, { clock.getAndIncrement() }, { UNRELATED_ID })
        val teacher = ConstructionReplicaStore(teacherRoot)
        val student = ConstructionReplicaStore(studentRoot)
        val teacherMemoTarget = MemoTarget("teacher-local-book", 7, 2)
        val studentMemoTarget = MemoTarget("student-local-book", 7, 2)
        val teacherTarget = ConstructionTarget(teacherMemoTarget.bookId, 7, 2, MEMO_ID, "authenticated-student")
        val studentTarget = ConstructionTarget(studentMemoTarget.bookId, 7, 2, MEMO_ID)
        val emptyNote = teacherMemos.create(teacherMemoTarget, MemoAnchor(.2f, .6f))
        val note = teacherMemos.replaceStrokes(teacherMemoTarget, emptyNote.id, emptyNote.revision, listOf(stroke(TEACHER_STROKE)))
        val unrelatedNote = studentMemos.create(studentMemoTarget, MemoAnchor(.8f, .1f))
        val scene = ConstructionScene(
            points = listOf(GeometryPoint("a", 0.0, 0.0), GeometryPoint("b", 10.0, 0.0)),
            segments = listOf(GeometrySegment("ab", "a", "b")),
            constraints = listOf(GeometryConstraint("length", ConstraintType.LENGTH, listOf("ab"), value = 10.0)),
        )
        teacher.saveLocal(teacher.load(teacherTarget, ConstructionReplicaRole.TEACHER), scene)
        assertNull(studentMemos.memo(studentMemoTarget, MEMO_ID))
        assertFalse(student.hasAttachment(studentTarget, ConstructionReplicaRole.STUDENT))

        // Exercise the new request and known-missing response through the real wire codec.
        val query = wire(teacher.requestState(teacherTarget, includeMemo = true))
        assertTrue(query.includeMemo)
        val snapshot = wire(student.studentSnapshot(studentTarget, query.requestId, memoStateKnown = true, memoJson = null))
        assertTrue(requireNotNull(snapshot.student).memoStateKnown)
        assertNull(snapshot.student!!.memoJson)
        val comparison = teacher.receiveStudentSnapshot(teacherTarget, snapshot)
        assertEquals(scene, comparison.scene)
        val prepared = teacher.preparePublish(
            comparison,
            memoJson = teacherMemos.encodeMemo(note).toString(Charsets.UTF_8),
            expectedMemoDigest = null,
        )
        assertFalse(prepared.conflict)
        val publication = wire(requireNotNull(prepared.packet))
        assertEquals(note, studentMemos.decodeMemo(requireNotNull(publication.memoJson).toByteArray(Charsets.UTF_8)))
        var parentApplyCount = 0
        val response = wire(student.receivePublish(studentTarget, publication, applyMemo = {
            parentApplyCount++
            val decoded = studentMemos.decodeMemo(requireNotNull(publication.memoJson).toByteArray(Charsets.UTF_8))
            val saved = studentMemos.applyPublishedMemo(decoded, studentMemoTarget, publication.expectedMemoDigest)
            ConstructionMemoApplyResult(saved != null, saved?.let { studentMemos.encodeMemo(it).toString(Charsets.UTF_8) })
        }))

        assertEquals(ConstructionPublishResult.APPLIED, response.result)
        assertEquals(1, parentApplyCount)
        val publishedNote = requireNotNull(studentMemos.memo(studentMemoTarget, MEMO_ID))
        assertEquals(studentMemoTarget, publishedNote.target)
        assertEquals(note.anchor, publishedNote.anchor)
        assertEquals(note.strokes, publishedNote.strokes)
        assertTrue(StudentMemoRepository.sameContent(note, publishedNote))
        assertEquals(unrelatedNote, studentMemos.memo(studentMemoTarget, unrelatedNote.id))
        assertEquals(scene, student.load(studentTarget, ConstructionReplicaRole.STUDENT).scene)
        assertTrue(requireNotNull(response.student).memoStateKnown)
        assertEquals(publishedNote, studentMemos.decodeMemo(requireNotNull(response.student!!.memoJson).toByteArray(Charsets.UTF_8)))
        val teacherResult = teacher.receiveResult(teacherTarget, response)
        assertNull(teacherResult.pendingPublish)
        assertFalse(teacherResult.draftDirty)
        assertEquals(response.student, teacherResult.commonBase)
        assertEquals(note, teacherMemos.memo(teacherMemoTarget, MEMO_ID))

        // Later ink and geometry are student-owned. A delayed exact delivery must only replay
        // the durable receipt, including after reopening the replica store.
        val editedNote = studentMemos.replaceStrokes(studentMemoTarget, MEMO_ID, publishedNote.revision,
            publishedNote.strokes + stroke(STUDENT_STROKE))
        val editedScene = scene.copy(points = scene.points.map { it.copy(y = it.y + 2.0) })
        student.saveLocal(student.load(studentTarget, ConstructionReplicaRole.STUDENT), editedScene)
        val noteBytesAfterEdit = studentMemos.exportSnapshot(studentMemoTarget)
        val studentGeometryVersion = student.load(studentTarget, ConstructionReplicaRole.STUDENT).studentShadow!!.version
        val reopened = ConstructionReplicaStore(studentRoot)
        val retry = wire(reopened.receivePublish(studentTarget, wire(publication), applyMemo = {
            error("An exact retry must not apply the teacher's old parent note again")
        }))
        assertEquals(response, retry)
        assertEquals(1, parentApplyCount)
        assertArrayEquals(noteBytesAfterEdit, studentMemos.exportSnapshot(studentMemoTarget))
        assertEquals(editedNote, studentMemos.memo(studentMemoTarget, MEMO_ID))
        assertEquals(unrelatedNote, studentMemos.memo(studentMemoTarget, unrelatedNote.id))
        val afterRetry = reopened.load(studentTarget, ConstructionReplicaRole.STUDENT)
        assertEquals(editedScene, afterRetry.scene)
        assertEquals(studentGeometryVersion, afterRetry.studentShadow!!.version)
    }

    private fun wire(packet: ConstructionSyncPacket) = ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(packet))

    private fun stroke(id: String) = MemoStroke(
        id = id,
        tool = MemoTool.PEN,
        colorArgb = 0xff102030.toInt(),
        widthFraction = .004f,
        points = listOf(MemoPoint(.1f, .2f, .5f), MemoPoint(.3f, .4f, .8f)),
        createdAtEpochMillis = 1_000L,
    )

    private companion object {
        const val MEMO_ID = "00000000-0000-0000-0000-000000000001"
        const val UNRELATED_ID = "00000000-0000-0000-0000-000000000002"
        const val TEACHER_STROKE = "10000000-0000-0000-0000-000000000001"
        const val STUDENT_STROKE = "10000000-0000-0000-0000-000000000002"
    }
}
