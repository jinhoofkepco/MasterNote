package com.studyink.memo.core

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeacherMemoPublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `teacher drafts and student snapshots remain isolated under the same data root`() {
        val root = temporary.newFolder("shared-data")
        val teacher = repository(root, teacherDraft = true) { PUBLISHED_ID }
        val student = repository(root) { PUBLISHED_ID }
        val draft = teacher.create(STUDENT_TARGET, MemoAnchor(.2f, .3f))
        val draftBytes = teacher.exportSnapshot(STUDENT_TARGET)

        assertTrue(student.targets().isEmpty())
        assertTrue(student.snapshot(STUDENT_TARGET).memos.isEmpty())
        assertNull(student.memo(STUDENT_TARGET, draft.id))
        // Even identical target and UUID values identify independent role-owned notes.
        val studentNote = student.create(STUDENT_TARGET, MemoAnchor(.7f, .8f))
        assertArrayEquals(draftBytes, teacher.exportSnapshot(STUDENT_TARGET))
        assertNotEquals(teacher.targetFileForTest(STUDENT_TARGET), student.targetFileForTest(STUDENT_TARGET))
        assertTrue(teacher.targetFileForTest(STUDENT_TARGET).path.contains("teacher-memo-drafts-v1"))
        assertTrue(student.targetFileForTest(STUDENT_TARGET).path.contains("student-memos-v1"))

        val reopenedTeacher = repository(root, teacherDraft = true) { error("No new ID expected") }
        val reopenedStudent = repository(root) { error("No new ID expected") }
        assertEquals(draft, reopenedTeacher.memo(STUDENT_TARGET, draft.id))
        assertEquals(studentNote, reopenedStudent.memo(STUDENT_TARGET, studentNote.id))
        assertEquals(listOf(STUDENT_TARGET), reopenedTeacher.targets())
        assertEquals(listOf(STUDENT_TARGET), reopenedStudent.targets())
    }

    @Test
    fun `publication remaps one parent memo with ink and anchor without replacing other notes or replaying writes`() {
        val teacher = repository(temporary.newFolder("teacher"), teacherDraft = true) { PUBLISHED_ID }
        val studentRoot = temporary.newFolder("student")
        val student = repository(studentRoot) { UNRELATED_ID }
        val sourceEmpty = teacher.create(TEACHER_TARGET, MemoAnchor(.26f, .64f))
        val source = teacher.replaceStrokes(TEACHER_TARGET, sourceEmpty.id, sourceEmpty.revision, listOf(stroke()))
        val unrelated = student.create(STUDENT_TARGET, MemoAnchor(.8f, .1f))
        val sourceBytes = teacher.exportSnapshot(TEACHER_TARGET)

        val published = requireNotNull(student.applyPublishedMemo(source, STUDENT_TARGET, expectedDigest = null))
        assertEquals(source.id, published.id)
        assertEquals(STUDENT_TARGET, published.target)
        assertEquals(source.anchor, published.anchor)
        assertEquals(source.strokes, published.strokes)
        assertEquals(source.createdAtEpochMillis, published.createdAtEpochMillis)
        assertEquals(1L, published.revision)
        assertNotEquals(source.digestSha256, published.digestSha256)
        assertEquals(StudentMemoDigest.memoSha256(published), published.digestSha256)
        assertTrue(StudentMemoRepository.sameContent(source, published))
        assertEquals(unrelated, student.memo(STUDENT_TARGET, unrelated.id))
        assertEquals(setOf(source.id, unrelated.id), student.activeMemos(STUDENT_TARGET).map { it.id }.toSet())
        assertArrayEquals(sourceBytes, teacher.exportSnapshot(TEACHER_TARGET))

        val afterFirstPublication = student.exportSnapshot(STUDENT_TARGET)
        // A delivery retry retains its original comparison token (null on creation).
        assertEquals(published, student.applyPublishedMemo(source, STUDENT_TARGET, expectedDigest = null))
        assertArrayEquals(afterFirstPublication, student.exportSnapshot(STUDENT_TARGET))

        val revisedSource = teacher.move(TEACHER_TARGET, source.id, source.revision, MemoAnchor(.4f, .5f))
        val updated = requireNotNull(student.applyPublishedMemo(revisedSource, STUDENT_TARGET, published.digestSha256))
        assertEquals(published.revision + 1, updated.revision)
        assertEquals(published.createdAtEpochMillis, updated.createdAtEpochMillis)
        assertEquals(revisedSource.anchor, updated.anchor)
        assertEquals(source.strokes, updated.strokes)
        assertEquals(unrelated, student.memo(STUDENT_TARGET, unrelated.id))
        val afterUpdate = student.exportSnapshot(STUDENT_TARGET)
        assertEquals(updated, student.applyPublishedMemo(revisedSource, STUDENT_TARGET, published.digestSha256))
        assertArrayEquals(afterUpdate, student.exportSnapshot(STUDENT_TARGET))

        val reopened = repository(studentRoot) { error("No new ID expected") }
        assertEquals(updated, reopened.memo(STUDENT_TARGET, updated.id))
        assertEquals(unrelated, reopened.memo(STUDENT_TARGET, unrelated.id))
        assertEquals(updated, reopened.decodeMemo(requireNotNull(reopened.exportMemo(STUDENT_TARGET, updated.id))))
    }

    @Test
    fun `student changes reject stale publication and a tombstone cannot be resurrected even with its current digest`() {
        val teacher = repository(temporary.newFolder("teacher"), teacherDraft = true) { PUBLISHED_ID }
        val student = repository(temporary.newFolder("student")) { error("Publication supplies the ID") }
        val source = teacher.create(TEACHER_TARGET, MemoAnchor(.2f, .3f))
        val published = requireNotNull(student.applyPublishedMemo(source, STUDENT_TARGET, expectedDigest = null))
        val revisedSource = teacher.move(TEACHER_TARGET, source.id, source.revision, MemoAnchor(.6f, .7f))
        val studentEdited = student.replaceStrokes(STUDENT_TARGET, source.id, published.revision, listOf(stroke()))
        val afterStudentEdit = student.exportSnapshot(STUDENT_TARGET)

        assertNull(student.applyPublishedMemo(revisedSource, STUDENT_TARGET, published.digestSha256))
        assertNull(student.applyPublishedMemo(revisedSource, STUDENT_TARGET, expectedDigest = null))
        assertArrayEquals(afterStudentEdit, student.exportSnapshot(STUDENT_TARGET))
        assertEquals(studentEdited, student.memo(STUDENT_TARGET, source.id))

        val deleted = student.delete(STUDENT_TARGET, source.id, studentEdited.revision)
        val afterDeletion = student.exportSnapshot(STUDENT_TARGET)
        assertNull(student.applyPublishedMemo(revisedSource, STUDENT_TARGET, studentEdited.digestSha256))
        assertNull(student.applyPublishedMemo(revisedSource, STUDENT_TARGET, deleted.digestSha256))
        assertArrayEquals(afterDeletion, student.exportSnapshot(STUDENT_TARGET))
        assertNull(student.memo(STUDENT_TARGET, source.id))
        assertEquals(deleted, student.memo(STUDENT_TARGET, source.id, includeDeleted = true))
        assertFalse(student.activeMemos(STUDENT_TARGET).any { it.id == source.id })
    }

    private fun repository(root: File, teacherDraft: Boolean = false, uuid: () -> String): StudentMemoRepository {
        val clock = AtomicLong(1_000L)
        return StudentMemoRepository(root, { clock.getAndIncrement() }, uuid, teacherDraft = teacherDraft)
    }

    private fun stroke() = MemoStroke(
        id = "10000000-0000-0000-0000-000000000001",
        tool = MemoTool.PEN,
        colorArgb = 0xff102030.toInt(),
        widthFraction = .004f,
        points = listOf(MemoPoint(.1f, .2f, .5f), MemoPoint(.3f, .4f, .8f)),
        createdAtEpochMillis = 1_000L,
    )

    private companion object {
        val TEACHER_TARGET = MemoTarget("teacher-local-book", 7, 2)
        val STUDENT_TARGET = MemoTarget("student-local-book", 7, 2)
        const val PUBLISHED_ID = "00000000-0000-0000-0000-000000000001"
        const val UNRELATED_ID = "00000000-0000-0000-0000-000000000002"
    }
}
