package com.studyink.annotation.storage

import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOperationLogStoreTest {
    @Test
    fun staleLocalChangeMergesWithRemoteOperationAndSurvivesReload() {
        val sourceRoot = Files.createTempDirectory("masternote-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-target").toFile()
        try {
            val sourceStore = PageOperationLogStore(sourceRoot, checkpointInterval = 2)
            val targetStore = PageOperationLogStore(targetRoot, checkpointInterval = 2)
            val empty = AnnotationSnapshot.empty(BOOK_ID, PAGE)

            // Reader creates this change from revision zero, but has not persisted it yet.
            val localStroke = stroke("local-device")
            val staleLocalChange = AnnotationDocument(empty).addStroke(localStroke)

            // Meanwhile LAN persists another operation into the same target page.
            val remoteStroke = stroke("remote-device")
            sourceStore.append(AnnotationDocument(empty).addStroke(remoteStroke))
            val encodedRemote = sourceStore.encodedOperationsAfter(BOOK_ID, PAGE, 0L).single()
            targetStore.appendEncodedOperation(BOOK_ID, PAGE, encodedRemote)

            val merged = targetStore.append(staleLocalChange)

            assertEquals(2L, merged.revision)
            assertTrue(localStroke.id in merged.activeStrokeIds)
            assertTrue(remoteStroke.id in merged.activeStrokeIds)

            // Re-reading the append log must produce the same union, not skip a same-revision row.
            val reloaded = PageOperationLogStore(targetRoot, checkpointInterval = 2)
                .loadPage(BOOK_ID, PAGE)
            assertEquals(2L, reloaded.revision)
            assertEquals(setOf(localStroke.id, remoteStroke.id), reloaded.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun pageStateRestoresClockAdvancedByOperationWithoutAddedAssets() {
        val root = Files.createTempDirectory("masternote-clock").toFile()
        try {
            val store = PageOperationLogStore(root)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val added = document.addStroke(stroke("student-device"))
            store.append(added)
            val erased = document.erase(
                page = PAGE,
                path = added.addedAssets.single().points,
                radius = 20f,
                wholeStroke = true,
                authorId = "student",
                attemptNo = 1,
                deviceId = "student-device",
            )!!
            assertTrue(erased.addedAssets.isEmpty())
            store.append(erased)

            val loaded = PageOperationLogStore(root).loadPageState(BOOK_ID, PAGE)
            val next = AnnotationDocument(
                initial = loaded.snapshot,
                operationClockHighWater = loaded.operationClockHighWater,
            ).addStroke(stroke("student-device"))

            assertEquals(2L, loaded.operationClockHighWater)
            assertEquals(3L, next.operation.logicalClock)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun redoOfUnpublishedTeacherDraftIsNotEligibleForSync() {
        val root = Files.createTempDirectory("masternote-draft-redo").toFile()
        try {
            val store = PageOperationLogStore(root)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val draft = stroke("teacher-device").copy(
                authorId = "teacher",
                publishedAtEpochMillis = null,
            )
            store.append(document.addStroke(draft))
            store.append(document.undo("teacher-device")!!)
            store.append(document.redo("teacher-device")!!)

            val publishable = store.encodedOperationsAfter(
                bookId = BOOK_ID,
                pageNumber = PAGE,
                originDeviceId = "teacher-device",
                logicalClock = 0L,
                includeTeacherDrafts = false,
            )

            assertTrue(publishable.isEmpty())
            assertFalse(store.loadPage(BOOK_ID, PAGE).activeStrokes.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun stroke(deviceId: String) = StrokeAsset(
        pageNumber = PAGE,
        tool = StrokeTool.PEN,
        colorArgb = 0xFF17233C.toInt(),
        width = 3f,
        points = listOf(PagePoint(1f, 1f), PagePoint(2f, 2f)),
        authorId = "student",
        attemptNo = 1,
        deviceId = deviceId,
    )

    private companion object {
        const val BOOK_ID = "book"
        const val PAGE = 3
    }
}
