package com.studyink.reader

import com.studyink.core.model.MarkColor
import com.studyink.core.model.PagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TeacherGradeDraftStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun restartRestoresStableIdsAndComposableMarkGroup() {
        val file = draftFile()
        val target = TeacherGradeDraftTarget("math-book", pageNumber = 7, attemptNo = 2)
        val anchor = PagePoint(x = 312f, y = 48_000f, pressure = 0.75f)
        val stored = store(file).add(
            target = target,
            anchor = anchor,
            color = MarkColor.BLUE,
            groupId = "stable-grade-group",
        )

        val restoredStore = store(file)

        assertEquals(listOf(stored), restoredStore.list(target))
        assertEquals(listOf(stored), restoredStore.listAll())
        val group = restoredStore.markGroups(target).single()
        assertEquals(stored.groupId, group.id)
        assertEquals(target.bookId, group.bookId)
        assertEquals(target.pageNumber, group.pageNumber)
        assertEquals(anchor, group.anchor)
        assertEquals(stored.createdAtEpochMillis, group.createdAtEpochMillis)
        assertEquals("teacher-grade-draft", group.lastModifiedByDeviceId)
        assertNull(group.hiddenAtEpochMillis)
        assertEquals(1, group.marks.size)
        assertEquals(target.attemptNo, group.marks.single().attemptNo)
        assertEquals(MarkColor.BLUE, group.marks.single().color)
        assertNull(group.marks.single().hiddenAtEpochMillis)
    }

    @Test
    fun dataRootReplacementReloadsCachedDraftsBeforeTheNextMutation() {
        val file = draftFile()
        val target = TeacherGradeDraftTarget("math-book", pageNumber = 7, attemptNo = 2)
        val cached = store(file)
        val backedUp = cached.add(target, point(10f), MarkColor.BLUE, "backup-group")
        val backupBytes = file.readBytes()
        cached.add(target, point(20f), MarkColor.RED, "later-group")

        file.writeBytes(backupBytes)
        cached.reloadAfterDataRootReplacement()

        assertEquals(listOf(backedUp), cached.list(target))
        cached.add(target, point(30f), MarkColor.GRAY, "after-restore-group")
        assertEquals(
            setOf("backup-group", "after-restore-group"),
            store(file).list(target).mapTo(linkedSetOf()) { it.groupId },
        )
    }

    @Test
    fun generatedDraftAndGroupIdsAreDistinctAndStableAcrossRestart() {
        val file = draftFile()
        val target = TeacherGradeDraftTarget("math-book", pageNumber = 8, attemptNo = 1)

        val added = store(file).add(target, point(20f), MarkColor.RED)
        val restored = store(file).list(target).single()

        assertNotEquals(added.draftId, added.groupId)
        assertEquals(added.draftId, restored.draftId)
        assertEquals(added.groupId, restored.groupId)
    }

    @Test
    fun sameGroupIdNeverMixesBookPageOrAttemptTargets() {
        val store = store(draftFile())
        val pageAttemptOne = TeacherGradeDraftTarget("book-a", pageNumber = 3, attemptNo = 1)
        val pageAttemptTwo = pageAttemptOne.copy(attemptNo = 2)
        val nextPage = pageAttemptOne.copy(pageNumber = 4)
        val otherBook = pageAttemptOne.copy(bookId = "book-b")

        val one = store.add(pageAttemptOne, point(10f), MarkColor.BLUE, SHARED_GROUP_ID)
        val two = store.add(pageAttemptTwo, point(20f), MarkColor.RED, SHARED_GROUP_ID)
        val three = store.add(nextPage, point(30f), MarkColor.GRAY, SHARED_GROUP_ID)
        val four = store.add(otherBook, point(40f), MarkColor.RED, SHARED_GROUP_ID)

        assertEquals(listOf(one), store.list(pageAttemptOne))
        assertEquals(listOf(two), store.list(pageAttemptTwo))
        assertEquals(listOf(three), store.list(nextPage))
        assertEquals(listOf(four), store.list(otherBook))
        assertEquals(4, store.listAll().size)
        assertEquals(MarkColor.RED, store.markGroups(pageAttemptTwo).single().marks.single().color)
    }

    @Test
    fun mutationsRotateDraftIdButKeepGroupIdAndOldCommitCannotClearNewEdit() {
        val store = store(draftFile())
        val target = TeacherGradeDraftTarget("science", pageNumber = 12, attemptNo = 3)
        val added = store.add(target, point(10f), MarkColor.BLUE, "grade-1")

        val recolored = requireNotNull(store.changeColor(target, added.groupId, MarkColor.RED))
        val moved = requireNotNull(store.move(target, added.groupId, point(90f)))

        assertEquals(added.groupId, recolored.groupId)
        assertEquals(added.groupId, moved.groupId)
        assertNotEquals(added.draftId, recolored.draftId)
        assertNotEquals(recolored.draftId, moved.draftId)
        assertEquals(0, store.clearCommittedIds(setOf(added.draftId, recolored.draftId)))
        assertEquals(listOf(moved), store.list(target))

        val hidden = requireNotNull(store.hide(target, added.groupId))
        assertNotEquals(moved.draftId, hidden.draftId)
        assertTrue(hidden.hidden)
        val hiddenGroup = store.markGroups(target).single()
        assertEquals(hidden.groupId, hiddenGroup.id)
        assertEquals(hidden.updatedAtEpochMillis, hiddenGroup.hiddenAtEpochMillis)
        assertEquals(hidden.updatedAtEpochMillis, hiddenGroup.marks.single().hiddenAtEpochMillis)
        assertEquals(1, store.clearCommittedIds(setOf(hidden.draftId)))
        assertTrue(store.list(target).isEmpty())
    }

    @Test
    fun removeCancelsOnlyTheExactTargetGroup() {
        val store = store(draftFile())
        val firstTarget = TeacherGradeDraftTarget("book", pageNumber = 1, attemptNo = 1)
        val secondTarget = firstTarget.copy(attemptNo = 2)
        store.add(firstTarget, point(10f), MarkColor.BLUE, SHARED_GROUP_ID)
        val retained = store.add(secondTarget, point(20f), MarkColor.RED, SHARED_GROUP_ID)

        assertTrue(store.remove(firstTarget, SHARED_GROUP_ID))
        assertFalse(store.remove(firstTarget, SHARED_GROUP_ID))
        assertTrue(store.list(firstTarget).isEmpty())
        assertEquals(listOf(retained), store.list(secondTarget))
    }

    @Test
    fun countBoundsRejectMutationWithoutChangingPersistedState() {
        val file = draftFile()
        val limits = TeacherGradeDraftLimits(
            maxDraftsTotal = 3,
            maxDraftsPerTarget = 2,
            maxFileBytes = 32 * 1024,
        )
        val store = store(file, limits)
        val firstTarget = TeacherGradeDraftTarget("book", pageNumber = 1, attemptNo = 1)
        val secondTarget = firstTarget.copy(pageNumber = 2)
        val thirdTarget = firstTarget.copy(pageNumber = 3)
        store.add(firstTarget, point(10f), MarkColor.BLUE, "group-1")
        store.add(firstTarget, point(20f), MarkColor.RED, "group-2")

        assertThrows(IllegalArgumentException::class.java) {
            store.add(firstTarget, point(30f), MarkColor.GRAY, "group-3")
        }
        store.add(secondTarget, point(40f), MarkColor.BLUE, "group-4")
        assertThrows(IllegalArgumentException::class.java) {
            store.add(thirdTarget, point(50f), MarkColor.RED, "group-5")
        }

        val restored = store(file, limits)
        assertEquals(3, restored.listAll().size)
        assertEquals(2, restored.list(firstTarget).size)
        assertEquals(1, restored.list(secondTarget).size)
        assertTrue(restored.list(thirdTarget).isEmpty())
    }

    @Test
    fun fileSizeBoundRejectsWriteWithoutPublishingPartialState() {
        val file = draftFile()
        val limits = TeacherGradeDraftLimits(
            maxDraftsTotal = 1,
            maxDraftsPerTarget = 1,
            maxFileBytes = 64,
        )
        val store = store(file, limits)

        assertThrows(IllegalArgumentException::class.java) {
            store.add(
                TeacherGradeDraftTarget("book", pageNumber = 1, attemptNo = 1),
                point(10f),
                MarkColor.BLUE,
                "group",
            )
        }

        assertTrue(store.listAll().isEmpty())
        assertFalse(file.exists())
        assertFalse(File(file.parentFile, "${file.name}.new").exists())
    }

    @Test
    fun corruptFileIsQuarantinedWithoutTouchingHandwritingOrCatalog() {
        val file = draftFile()
        val draftDirectory = requireNotNull(file.parentFile)
        draftDirectory.mkdirs()
        file.writeText("{ definitely-not-valid-json")
        val handwriting = File(draftDirectory, "handwriting.keep").apply { writeText("ink") }
        val catalog = File(draftDirectory, "catalog.keep").apply { writeText("books") }

        val recovered = store(file)

        assertTrue(recovered.listAll().isEmpty())
        assertFalse(file.exists())
        assertEquals("ink", handwriting.readText())
        assertEquals("books", catalog.readText())
        assertEquals(
            1,
            draftDirectory.listFiles().orEmpty().count {
                it.name.startsWith("${file.name}.corrupt-")
            },
        )

        val target = TeacherGradeDraftTarget("new-book", pageNumber = 2, attemptNo = 1)
        val newDraft = recovered.add(target, point(100f), MarkColor.GRAY, "new-group")
        assertEquals(listOf(newDraft), store(file).list(target))
        assertEquals("ink", handwriting.readText())
        assertEquals("books", catalog.readText())
    }

    @Test
    fun invalidTargetAnchorAndGroupAreRejectedBeforeDiskMutation() {
        val file = draftFile()
        val store = store(file)
        val target = TeacherGradeDraftTarget("book", pageNumber = 1, attemptNo = 1)

        assertThrows(IllegalArgumentException::class.java) {
            store.add(target.copy(bookId = ""), point(10f), MarkColor.BLUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.add(target, PagePoint(Float.NaN, 10f), MarkColor.BLUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.add(target, point(10f), MarkColor.BLUE, "x".repeat(257))
        }

        assertTrue(store.listAll().isEmpty())
        assertFalse(file.exists())
    }

    private fun draftFile(): File = File(
        temporaryFolder.root,
        "masternote/teacher-grade-drafts-v1.json",
    )

    private fun store(
        file: File,
        limits: TeacherGradeDraftLimits = TeacherGradeDraftLimits(),
    ): TeacherGradeDraftStore {
        var nextId = 0
        var now = 1_000L
        return TeacherGradeDraftStore(
            file = file,
            limits = limits,
            nowEpochMillis = { now++ },
            newUuid = { "generated-${nextId++}" },
        )
    }

    private fun point(x: Float): PagePoint = PagePoint(x = x, y = x * 100f)

    companion object {
        private const val SHARED_GROUP_ID = "shared-grade-group"
    }
}
