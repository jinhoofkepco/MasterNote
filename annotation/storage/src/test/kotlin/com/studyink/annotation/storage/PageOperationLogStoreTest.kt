package com.studyink.annotation.storage

import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOperationLogStoreTest {
    @Test
    fun boundedDeltaCopiesOnlyThePrefixThatFitsItsWireBudget() {
        val root = Files.createTempDirectory("masternote-bounded-delta").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            var snapshot = AnnotationSnapshot.empty(BOOK_ID, PAGE)
            repeat(3) {
                snapshot = store.append(AnnotationDocument(snapshot).addStroke(stroke("student-device")))
            }
            val all = store.encodedOperationsAfter(BOOK_ID, PAGE, "student-device", 0L)
            val oneFrameBudget = 8 + 4 + all.first().size

            val bounded = store.encodedOperationsAfterBounded(
                BOOK_ID,
                PAGE,
                "student-device",
                0L,
                maxFramedBytes = oneFrameBudget,
                fixedFrameBytes = 8,
                perOperationFrameBytes = 4,
            )

            assertFalse(bounded.complete)
            assertEquals(oneFrameBudget, bounded.framedByteCount)
            assertEquals(1, bounded.operations.size)
            assertArrayEquals(all.first(), bounded.operations.single())
            assertEquals(store.operationCursor(all.first()).logicalClock, bounded.lastLogicalClock)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun vmErrorEscapesCorruptionBoundaryWithoutRunningQuarantineHandler() {
        var quarantined = false
        val fatal = OutOfMemoryError("simulated")

        val thrown = assertThrows(OutOfMemoryError::class.java) {
            readAnnotationDataOrHandleCorruption<Unit>(
                read = { throw fatal },
                onCorruption = {
                    quarantined = true
                },
            )
        }

        assertTrue(thrown === fatal)
        assertFalse(quarantined)
    }

    @Test
    fun durableAppendSurvivesReloadWithoutAForcedCheckpoint() {
        val root = Files.createTempDirectory("masternote-submit-without-checkpoint").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            val added = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                .addStroke(stroke("student-device"))

            store.append(added)

            assertFalse(root.resolve("$BOOK_ID/pages/$PAGE/checkpoint.json").exists())
            val reloaded = PageOperationLogStore(root, checkpointInterval = 10_000)
                .loadPage(BOOK_ID, PAGE)
            assertEquals(1L, reloaded.revision)
            assertEquals(added.addedAssets.map(StrokeAsset::id).toSet(), reloaded.activeStrokeIds)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pageIndexCacheIsBoundedAndAnEvictedPageRebuildsFromDurableLog() {
        val root = Files.createTempDirectory("masternote-page-index-lru").toFile()
        try {
            val store = PageOperationLogStore(root, checkpointInterval = 10_000)
            val firstPage = 0
            val added = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, firstPage))
                .addStroke(stroke("student-device").copy(pageNumber = firstPage))
            store.append(added)

            (1..PageOperationLogStore.MAX_CACHED_PAGE_INDEXES).forEach { page ->
                store.loadPage(BOOK_ID, page)
            }

            assertEquals(PageOperationLogStore.MAX_CACHED_PAGE_INDEXES, store.cachedPageIndexCount())
            assertFalse(store.isPageIndexCached(BOOK_ID, firstPage))
            val rebuilt = store.loadPage(BOOK_ID, firstPage)
            assertEquals(1L, rebuilt.revision)
            assertEquals(added.addedAssets.map(StrokeAsset::id).toSet(), rebuilt.activeStrokeIds)
            assertEquals(PageOperationLogStore.MAX_CACHED_PAGE_INDEXES, store.cachedPageIndexCount())
            assertTrue(store.isPageIndexCached(BOOK_ID, firstPage))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localAppendSignalsExactlyOnceAfterItIsReadableFromTheStableRoot() {
        val root = Files.createTempDirectory("masternote-commit-local").toFile()
        try {
            val store = PageOperationLogStore(root)
            val change = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                .addStroke(stroke("local-device"))
            val observedRevisions = mutableListOf<Long>()
            val subscription = MasterNoteDataCommitBus.addListener {
                store.withStableDataRoot {
                    observedRevisions += store.loadPage(BOOK_ID, PAGE).revision
                }
            }
            try {
                store.append(change)
                store.append(change)
            } finally {
                subscription.close()
            }

            assertEquals(listOf(1L), observedRevisions)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun remoteAppendSignalsExactlyOnceAndDuplicateReplayDoesNotSignal() {
        val sourceRoot = Files.createTempDirectory("masternote-commit-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-commit-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val target = PageOperationLogStore(targetRoot)
            source.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("remote-device")),
            )
            val encoded = source.encodedOperationsAfter(BOOK_ID, PAGE, 0L).single()
            val observedRevisions = mutableListOf<Long>()
            val subscription = MasterNoteDataCommitBus.addListener {
                observedRevisions += target.loadPage(BOOK_ID, PAGE).revision
            }
            try {
                target.appendEncodedOperation(BOOK_ID, PAGE, encoded)
                target.appendEncodedOperation(BOOK_ID, PAGE, encoded)
            } finally {
                subscription.close()
            }

            assertEquals(listOf(1L), observedRevisions)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

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
    fun syntheticCheckpointPreservesClockRevisionAndLaterLocalHistoryAcrossRestart() {
        val sourceRoot = Files.createTempDirectory("masternote-synthetic-clock-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-synthetic-clock-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val sourceAdd = sourceDocument.addStroke(stroke("remote-student"))
            source.append(sourceAdd)
            val sourceErase = requireNotNull(
                sourceDocument.erase(
                    page = PAGE,
                    path = sourceAdd.addedAssets.single().points,
                    radius = 20f,
                    wholeStroke = true,
                    authorId = "student",
                    attemptNo = 1,
                    deviceId = "remote-student",
                ),
            )
            assertTrue(sourceErase.addedAssets.isEmpty())
            source.append(sourceErase)
            val exported = source.exportStudentLayerCheckpoint(BOOK_ID, PAGE, "remote-student")
            assertEquals(2L, exported.originDeviceHighWater)

            val target = PageOperationLogStore(targetRoot, checkpointInterval = 10_000)
            val targetDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            target.append(targetDocument.addStroke(stroke("local-device")))
            val logBytesBeforeCheckpoint = target.operationLogFile(BOOK_ID, PAGE).length()

            val applied = target.applyStudentLayerCheckpoint(
                localBookId = BOOK_ID,
                pageNumber = PAGE,
                checkpointBytes = exported.copyCheckpointBytes(),
                expectedResultLayerSha256 = exported.layerSha256,
            )
            assertEquals(2L, applied.snapshot.revision)
            assertEquals(logBytesBeforeCheckpoint, target.operationLogFile(BOOK_ID, PAGE).length())
            assertEquals(2L, target.loadPageState(BOOK_ID, PAGE).operationClockHighWater)
            assertEquals(2L, target.maxOperationClock(BOOK_ID, PAGE, "student-layer-checkpoint"))

            val restarted = PageOperationLogStore(targetRoot, checkpointInterval = 10_000)
            val durable = restarted.loadPageState(BOOK_ID, PAGE)
            assertEquals(applied.snapshot.revision, durable.snapshot.revision)
            assertEquals(applied.snapshot.assets, durable.snapshot.assets)
            assertEquals(applied.snapshot.activeStrokeIds, durable.snapshot.activeStrokeIds)
            assertEquals(applied.snapshot.appliedOperationIds, durable.snapshot.appliedOperationIds)
            assertEquals(2L, durable.operationClockHighWater)
            assertEquals(2L, restarted.maxOperationClock(BOOK_ID, PAGE, "student-layer-checkpoint"))

            val localDocument = AnnotationDocument(durable.snapshot, durable.operationClockHighWater)
            val localAdd = localDocument.addStroke(
                stroke("local-device").copy(authorId = "teacher", publishedAtEpochMillis = 100L),
            )
            assertEquals(3L, localAdd.operation.logicalClock)
            restarted.append(localAdd)
            val localUndo = requireNotNull(localDocument.undo("local-device"))
            assertEquals(4L, localUndo.operation.logicalClock)
            val afterUndo = restarted.append(localUndo)
            assertEquals(4L, afterUndo.revision)

            val localDelta = restarted.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "local-device",
                1L,
            )
            assertEquals(listOf(3L, 4L), localDelta.map { restarted.operationCursor(it).logicalClock })

            val secondRestart = PageOperationLogStore(targetRoot, checkpointInterval = 10_000)
            val finalPage = secondRestart.loadPageState(BOOK_ID, PAGE)
            assertEquals(afterUndo.revision, finalPage.snapshot.revision)
            assertEquals(afterUndo.assets, finalPage.snapshot.assets)
            assertEquals(afterUndo.activeStrokeIds, finalPage.snapshot.activeStrokeIds)
            assertEquals(afterUndo.appliedOperationIds, finalPage.snapshot.appliedOperationIds)
            assertEquals(4L, finalPage.operationClockHighWater)
            assertEquals(4L, finalPage.snapshot.revision)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
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

    @Test
    fun studentCheckpointReplacesEveryAttemptButPreservesTeacherLayerAndIsIdempotent() {
        val sourceRoot = Files.createTempDirectory("masternote-student-checkpoint-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-student-checkpoint-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val attemptOne = sourceDocument.addStroke(stroke("student-source").copy(attemptNo = 1))
            val attemptTwo = sourceDocument.addStroke(
                stroke("student-source").copy(
                    attemptNo = 2,
                    points = listOf(PagePoint(20f, 30f), PagePoint(40f, 50f)),
                ),
            )
            source.append(attemptOne)
            source.append(attemptTwo)

            val firstEncoding = source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val stableEncoding = firstEncoding.copyOf()
            firstEncoding.fill(0)
            assertArrayEquals(stableEncoding, source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE))

            val localBookId = "local-copy-of-book"
            val target = PageOperationLogStore(targetRoot, checkpointInterval = 2)
            val targetDocument = AnnotationDocument(AnnotationSnapshot.empty(localBookId, PAGE))
            val oldStudent = targetDocument.addStroke(stroke("old-student"))
            val teacher = targetDocument.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 123L,
                ),
            )
            target.append(oldStudent)
            target.append(teacher)
            val logBytesBeforeRemoteCheckpoint = target.operationLogFile(localBookId, PAGE).length()

            val applied = target.applyStudentLayerCheckpoint(localBookId, PAGE, stableEncoding)
            val expectedStudentIds = setOf(
                attemptOne.addedAssets.single().id,
                attemptTwo.addedAssets.single().id,
            )
            val activeStudents = applied.snapshot.activeStrokes.filter { it.authorId == "student" }
            assertTrue(applied.changed)
            assertEquals(expectedStudentIds, activeStudents.mapTo(hashSetOf(), StrokeAsset::id))
            assertEquals(setOf(1, 2), activeStudents.mapTo(hashSetOf(), StrokeAsset::attemptNo))
            assertTrue(oldStudent.addedAssets.single().id !in applied.snapshot.activeStrokeIds)
            assertTrue(teacher.addedAssets.single().id in applied.snapshot.activeStrokeIds)
            assertEquals(teacher.addedAssets.single(), applied.snapshot.assets[teacher.addedAssets.single().id])
            assertEquals(
                "Remote full-layer payload must not be duplicated into operations.log",
                logBytesBeforeRemoteCheckpoint,
                target.operationLogFile(localBookId, PAGE).length(),
            )
            assertTrue(targetRoot.resolve("$localBookId/pages/$PAGE/checkpoint.json").isFile)

            val duplicate = target.applyStudentLayerCheckpoint(localBookId, PAGE, stableEncoding)
            assertFalse(duplicate.changed)
            assertEquals(applied.checkpointId, duplicate.checkpointId)
            assertEquals(applied.snapshot.revision, duplicate.snapshot.revision)

            val reloaded = PageOperationLogStore(targetRoot).loadPage(localBookId, PAGE)
            assertEquals(applied.snapshot.revision, reloaded.revision)
            assertEquals(applied.snapshot.activeStrokeIds, reloaded.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun emptyStudentCheckpointRemovesStudentLayerWithoutTouchingTeacherInk() {
        val sourceRoot = Files.createTempDirectory("masternote-empty-student-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-empty-student-target").toFile()
        try {
            val checkpoint = PageOperationLogStore(sourceRoot)
                .encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val student = document.addStroke(stroke("student-device"))
            val teacher = document.addStroke(
                stroke("teacher-device").copy(authorId = "teacher", publishedAtEpochMillis = 50L),
            )
            target.append(student)
            target.append(teacher)

            val applied = target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, checkpoint)

            assertTrue(applied.changed)
            assertEquals(listOf(teacher.addedAssets.single()), applied.snapshot.activeStrokes)
            assertTrue(student.addedAssets.single().id in applied.snapshot.assets)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun checkpointCarriesRedoPayloadAndInactiveParentChain() {
        val sourceRoot = Files.createTempDirectory("masternote-complete-student-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-complete-student-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val parent = document.addStroke(
                stroke("student-device").copy(
                    points = listOf(PagePoint(5f, 5f), PagePoint(8f, 8f)),
                ),
            )
            source.append(parent)
            val parentAsset = parent.addedAssets.single()
            val child = document.addStroke(
                stroke("student-device").copy(
                    points = listOf(PagePoint(200f, 200f), PagePoint(220f, 220f)),
                    parentStrokeId = parentAsset.id,
                ),
            )
            source.append(child)
            source.append(
                requireNotNull(
                    document.erase(
                        page = PAGE,
                        path = parentAsset.points,
                        radius = 4f,
                        wholeStroke = true,
                        authorId = "student",
                        attemptNo = 1,
                        deviceId = "student-device",
                    ),
                ),
            )
            val redoAssetChange = document.addStroke(
                stroke("student-device").copy(
                    points = listOf(PagePoint(400f, 400f), PagePoint(420f, 420f)),
                ),
            )
            source.append(redoAssetChange)
            source.append(requireNotNull(document.undo("student-device")))
            val redo = requireNotNull(document.redo("student-device"))
            assertTrue(redo.addedAssets.isEmpty())
            source.append(redo)

            val checkpoint = source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)
            val applied = target.applyStudentLayerCheckpoint("portable-local-book", PAGE, checkpoint)
            val childAsset = child.addedAssets.single()
            val redoAsset = redoAssetChange.addedAssets.single()

            assertEquals(setOf(childAsset.id, redoAsset.id), applied.snapshot.activeStrokeIds)
            assertEquals(parentAsset, applied.snapshot.assets[parentAsset.id])
            assertEquals(parentAsset.id, applied.snapshot.assets[childAsset.id]?.parentStrokeId)
            assertEquals(redoAsset, applied.snapshot.assets[redoAsset.id])
            assertTrue(parentAsset.id !in applied.snapshot.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun malformedWrongPageAndOversizedCheckpointsAreRejectedWithoutACommit() {
        val sourceRoot = Files.createTempDirectory("masternote-invalid-checkpoint-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-invalid-checkpoint-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            source.append(document.addStroke(stroke("student-device")))
            val valid = source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)

            val malformed = valid.copyOf().also { bytes -> bytes[bytes.lastIndex / 2] = 0 }
            assertThrows(Exception::class.java) {
                target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, malformed)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(BOOK_ID, PAGE + 1, valid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(
                    BOOK_ID,
                    PAGE,
                    ByteArray(PageOperationLogStore.MAX_STUDENT_LAYER_CHECKPOINT_BYTES + 1),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(
                    localBookId = BOOK_ID,
                    pageNumber = PAGE,
                    checkpointBytes = valid,
                    expectedResultLayerSha256 = "0".repeat(64),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(
                    localBookId = BOOK_ID,
                    pageNumber = PAGE,
                    checkpointBytes = valid,
                    allowedAttemptNos = listOf(2),
                )
            }
            assertEquals(0L, target.loadPage(BOOK_ID, PAGE).revision)
            assertEquals(0L, target.loadPage(BOOK_ID, PAGE + 1).revision)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun portableLayerDigestsAndAtomicExportsExcludeUnrelatedLayersAndDefendBytes() {
        val sourceRoot = Files.createTempDirectory("masternote-layer-digest-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-layer-digest-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val student = document.addStroke(stroke("student-device"))
            source.append(student)
            val studentDigest = source.studentLayerSha256(BOOK_ID, PAGE)
            val studentExport = source.exportStudentLayerCheckpoint(
                BOOK_ID,
                PAGE,
                originDeviceId = "student-device",
            )

            assertTrue(Regex("[0-9a-f]{64}").matches(studentDigest))
            assertEquals(studentDigest, studentExport.layerSha256)
            assertEquals(1L, studentExport.originDeviceHighWater)
            val ownedStudentBytes = studentExport.checkpointBytes
            ownedStudentBytes.fill(0)
            assertFalse(studentExport.checkpointBytes.contentEquals(ownedStudentBytes))

            val publishedAttemptOne = document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    publishedAtEpochMillis = 100L,
                ),
            )
            source.append(publishedAttemptOne)
            val teacherDigest = source.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1)
            val teacherExport = source.exportPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1)
            assertTrue(Regex("[0-9a-f]{64}").matches(teacherDigest))
            assertEquals(teacherDigest, teacherExport.layerSha256)

            source.append(document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(40f, 40f), PagePoint(50f, 50f)),
                ),
            ))
            source.append(document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 200L,
                    points = listOf(PagePoint(60f, 60f), PagePoint(70f, 70f)),
                ),
            ))

            assertEquals(studentDigest, source.studentLayerSha256(BOOK_ID, PAGE))
            assertEquals(teacherDigest, source.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1))

            val target = PageOperationLogStore(targetRoot)
            val studentApplied = target.applyStudentLayerCheckpoint(
                "different-local-book-id",
                PAGE,
                studentExport.copyCheckpointBytes(),
            )
            assertEquals(studentDigest, studentApplied.layerSha256)
            assertEquals(
                studentDigest,
                target.studentLayerSha256("different-local-book-id", PAGE),
            )
            val teacherApplied = target.applyPublishedTeacherLayerCheckpoint(
                "different-local-book-id",
                PAGE,
                1,
                teacherExport.copyCheckpointBytes(),
            )
            assertEquals(teacherDigest, teacherApplied.layerSha256)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun studentAndTeacherCheckpointsCanReapplyAAfterBAndSurviveReload() {
        val studentARoot = Files.createTempDirectory("masternote-checkpoint-student-a").toFile()
        val studentBRoot = Files.createTempDirectory("masternote-checkpoint-student-b").toFile()
        val teacherARoot = Files.createTempDirectory("masternote-checkpoint-teacher-a").toFile()
        val teacherBRoot = Files.createTempDirectory("masternote-checkpoint-teacher-b").toFile()
        val targetRoot = Files.createTempDirectory("masternote-checkpoint-a-b-a-target").toFile()
        try {
            val studentA = PageOperationLogStore(studentARoot).also { store ->
                store.append(
                    AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                        .addStroke(stroke("student-a")),
                )
            }
            val studentB = PageOperationLogStore(studentBRoot).also { store ->
                store.append(
                    AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                        .addStroke(stroke("student-b").copy(
                            points = listOf(PagePoint(100f, 100f), PagePoint(120f, 120f)),
                        )),
                )
            }
            val studentACheckpoint = studentA.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val studentBCheckpoint = studentB.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)
            val firstA = target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, studentACheckpoint)
            val b = target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, studentBCheckpoint)
            val secondA = target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, studentACheckpoint)

            assertTrue(firstA.changed)
            assertTrue(b.changed)
            assertTrue(secondA.changed)
            assertEquals(firstA.layerSha256, secondA.layerSha256)
            assertEquals(3L, secondA.snapshot.revision)

            fun teacherCheckpoint(root: java.io.File, device: String, offset: Float): ByteArray {
                val store = PageOperationLogStore(root)
                val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                store.append(document.addStroke(
                    stroke(device).copy(
                        authorId = "teacher",
                        attemptNo = 1,
                        publishedAtEpochMillis = 300L,
                        points = listOf(PagePoint(offset, offset), PagePoint(offset + 5f, offset + 5f)),
                    ),
                ))
                return store.encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1)
            }
            val teacherACheckpoint = teacherCheckpoint(teacherARoot, "teacher-a", 200f)
            val teacherBCheckpoint = teacherCheckpoint(teacherBRoot, "teacher-b", 300f)
            val teacherFirstA = target.applyPublishedTeacherLayerCheckpoint(
                BOOK_ID,
                PAGE,
                1,
                teacherACheckpoint,
            )
            target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1, teacherBCheckpoint)
            val teacherSecondA = target.applyPublishedTeacherLayerCheckpoint(
                BOOK_ID,
                PAGE,
                1,
                teacherACheckpoint,
            )
            assertTrue(teacherSecondA.changed)
            assertEquals(teacherFirstA.layerSha256, teacherSecondA.layerSha256)

            val reloaded = PageOperationLogStore(targetRoot)
            assertEquals(secondA.layerSha256, reloaded.studentLayerSha256(BOOK_ID, PAGE))
            assertEquals(
                teacherSecondA.layerSha256,
                reloaded.publishedTeacherLayerSha256(BOOK_ID, PAGE, 1),
            )
        } finally {
            studentARoot.deleteRecursively()
            studentBRoot.deleteRecursively()
            teacherARoot.deleteRecursively()
            teacherBRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun studentCheckpointRejectsSameIdWithDifferentPayloadWithoutAppending() {
        val sourceRoot = Files.createTempDirectory("masternote-checkpoint-collision-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-checkpoint-collision-target").toFile()
        try {
            val sharedId = StrokeId("shared-student-stroke")
            val source = PageOperationLogStore(sourceRoot)
            source.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("source").copy(id = sharedId, width = 3f)),
            )
            val checkpoint = source.encodeStudentLayerCheckpoint(BOOK_ID, PAGE)
            val target = PageOperationLogStore(targetRoot)
            target.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("target").copy(id = sharedId, width = 9f)),
            )
            val before = target.loadPage(BOOK_ID, PAGE)
            val beforeBytes = target.operationLogFile(BOOK_ID, PAGE).length()

            assertThrows(IllegalArgumentException::class.java) {
                target.applyStudentLayerCheckpoint(BOOK_ID, PAGE, checkpoint)
            }

            assertEquals(before, target.loadPage(BOOK_ID, PAGE))
            assertEquals(beforeBytes, target.operationLogFile(BOOK_ID, PAGE).length())
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun pageOperationSyncStatsReportsPendingBytesAndOriginHighWater() {
        val root = Files.createTempDirectory("masternote-operation-log-stats").toFile()
        try {
            val store = PageOperationLogStore(root)
            assertEquals(
                PageOperationSyncStats(0L, 0L, 0, 0L),
                store.pageOperationSyncStats(BOOK_ID, PAGE, "student-device"),
            )
            store.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("student-device")),
            )

            val stats = store.pageOperationSyncStats(BOOK_ID, PAGE, "student-device")
            assertTrue(stats.logByteCount > 0L)
            assertTrue(stats.pendingEncodedByteCount > 0L)
            assertEquals(1, stats.pendingOperationCount)
            assertEquals(1L, stats.originDeviceHighWater)
            assertTrue(stats.lastMutationEpochMillis > 0L)
            assertEquals(
                PageOperationSyncStats(
                    stats.logByteCount,
                    0L,
                    0,
                    1L,
                    stats.lastMutationEpochMillis,
                ),
                store.pageOperationSyncStats(BOOK_ID, PAGE, "student-device", afterLogicalClock = 1L),
            )
            assertEquals(0L, store.pageOperationSyncStats(BOOK_ID, PAGE, "other-device").originDeviceHighWater)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publishedTeacherCheckpointReplacesOnlyExactAttemptAndPreservesDraftsStudentAndOtherAttempts() {
        val sourceRoot = Files.createTempDirectory("masternote-teacher-checkpoint-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-teacher-checkpoint-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val sourceDraft = sourceDocument.addStroke(
                stroke("source-teacher").copy(authorId = "teacher", attemptNo = 1),
            )
            source.append(sourceDraft)
            val sourcePublished = requireNotNull(sourceDocument.publishTeacherDrafts(1, "source-teacher"))
            source.append(sourcePublished)
            val excludedDraft = sourceDocument.addStroke(
                stroke("source-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(80f, 80f), PagePoint(90f, 90f)),
                ),
            )
            source.append(excludedDraft)
            val encoded = source.encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, attemptNo = 1)
            val stableEncoded = encoded.copyOf()
            source.append(sourceDocument.addStroke(
                stroke("unrelated-student").copy(
                    points = listOf(PagePoint(140f, 140f), PagePoint(150f, 150f)),
                ),
            ))
            source.append(sourceDocument.addStroke(
                stroke("unrelated-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 30L,
                    points = listOf(PagePoint(160f, 160f), PagePoint(170f, 170f)),
                ),
            ))
            encoded.fill(0)
            assertArrayEquals(
                stableEncoded,
                source.encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, attemptNo = 1),
            )

            val localBookId = "portable-teacher-book"
            val target = PageOperationLogStore(targetRoot, checkpointInterval = 2)
            val targetDocument = AnnotationDocument(AnnotationSnapshot.empty(localBookId, PAGE))
            val student = targetDocument.addStroke(stroke("student-device"))
            val oldPublished = targetDocument.addStroke(
                stroke("old-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    publishedAtEpochMillis = 10L,
                ),
            )
            val protectedDraft = targetDocument.addStroke(
                stroke("draft-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(120f, 120f), PagePoint(130f, 130f)),
                ),
            )
            val otherAttempt = targetDocument.addStroke(
                stroke("other-teacher").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 20L,
                ),
            )
            target.append(student)
            target.append(oldPublished)
            target.append(protectedDraft)
            target.append(otherAttempt)

            val applied = target.applyPublishedTeacherLayerCheckpoint(
                localBookId = localBookId,
                pageNumber = PAGE,
                attemptNo = 1,
                checkpointBytes = stableEncoded,
            )
            val publishedAsset = sourcePublished.addedAssets.single()
            val sourceParent = sourceDraft.addedAssets.single()

            assertTrue(applied.changed)
            assertEquals(
                setOf(
                    student.addedAssets.single().id,
                    protectedDraft.addedAssets.single().id,
                    otherAttempt.addedAssets.single().id,
                    publishedAsset.id,
                ),
                applied.snapshot.activeStrokeIds,
            )
            assertTrue(oldPublished.addedAssets.single().id !in applied.snapshot.activeStrokeIds)
            assertTrue(excludedDraft.addedAssets.single().id !in applied.snapshot.assets)
            assertEquals(sourceParent, applied.snapshot.assets[sourceParent.id])
            assertTrue(sourceParent.id !in applied.snapshot.activeStrokeIds)
            assertEquals(sourceParent.id, applied.snapshot.assets[publishedAsset.id]?.parentStrokeId)

            val duplicate = target.applyPublishedTeacherLayerCheckpoint(
                localBookId,
                PAGE,
                1,
                stableEncoded,
            )
            assertFalse(duplicate.changed)
            assertEquals(applied.checkpointId, duplicate.checkpointId)
            assertEquals(applied.snapshot.revision, duplicate.snapshot.revision)

            val reloaded = PageOperationLogStore(targetRoot).loadPage(localBookId, PAGE)
            assertEquals(applied.snapshot.activeStrokeIds, reloaded.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun emptyPublishedTeacherCheckpointRemovesOnlyExactPublishedLayer() {
        val sourceRoot = Files.createTempDirectory("masternote-empty-teacher-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-empty-teacher-target").toFile()
        try {
            val checkpoint = PageOperationLogStore(sourceRoot)
                .encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, attemptNo = 1)
            val target = PageOperationLogStore(targetRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val student = document.addStroke(stroke("student-device"))
            val published = document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    publishedAtEpochMillis = 100L,
                ),
            )
            val draft = document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 1,
                    points = listOf(PagePoint(50f, 50f), PagePoint(60f, 60f)),
                ),
            )
            val otherAttempt = document.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 200L,
                ),
            )
            target.append(student)
            target.append(published)
            target.append(draft)
            target.append(otherAttempt)

            val applied = target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1, checkpoint)

            assertTrue(applied.changed)
            assertEquals(
                setOf(
                    student.addedAssets.single().id,
                    draft.addedAssets.single().id,
                    otherAttempt.addedAssets.single().id,
                ),
                applied.snapshot.activeStrokeIds,
            )
            assertTrue(published.addedAssets.single().id in applied.snapshot.assets)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun publishedTeacherCheckpointRejectsMalformedWrongTargetAndOversizeWithoutCommit() {
        val sourceRoot = Files.createTempDirectory("masternote-invalid-teacher-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-invalid-teacher-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            source.append(document.addStroke(stroke("teacher").copy(
                authorId = "teacher",
                attemptNo = 1,
                publishedAtEpochMillis = 100L,
            )))
            val valid = source.encodePublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1)
            val target = PageOperationLogStore(targetRoot)

            val malformed = valid.copyOf().also { bytes -> bytes[bytes.lastIndex / 2] = 0 }
            assertThrows(Exception::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 1, malformed)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE + 1, 1, valid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(BOOK_ID, PAGE, 2, valid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(
                    BOOK_ID,
                    PAGE,
                    1,
                    ByteArray(PageOperationLogStore.MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES + 1),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyPublishedTeacherLayerCheckpoint(
                    localBookId = BOOK_ID,
                    pageNumber = PAGE,
                    attemptNo = 1,
                    checkpointBytes = valid,
                    expectedResultLayerSha256 = "0".repeat(64),
                )
            }
            assertEquals(0L, target.loadPage(BOOK_ID, PAGE).revision)
            assertEquals(0L, target.loadPage(BOOK_ID, PAGE + 1).revision)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun studentOperationAppendAcceptsStudentDeltaAndRejectsTeacherAddOrRemoval() {
        val sourceRoot = Files.createTempDirectory("masternote-student-delta-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-student-delta-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceStudentDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val studentAdded = sourceStudentDocument.addStroke(stroke("student-device"))
            source.append(studentAdded)
            val encodedStudentAdd = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "student-device",
                0L,
            ).single()

            val target = PageOperationLogStore(targetRoot)
            assertEquals(1L, target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedStudentAdd))
            assertEquals(1L, target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedStudentAdd))
            assertTrue(studentAdded.addedAssets.single().id in target.loadPage(BOOK_ID, PAGE).activeStrokeIds)

            val sourceTeacherDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val teacherAdded = sourceTeacherDocument.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    publishedAtEpochMillis = 10L,
                ),
            )
            source.append(teacherAdded)
            val encodedTeacherAdd = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "teacher-device",
                0L,
            ).single()
            assertThrows(IllegalArgumentException::class.java) {
                target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedTeacherAdd)
            }

            target.appendEncodedOperation(BOOK_ID, PAGE, encodedTeacherAdd)
            val teacherAsset = teacherAdded.addedAssets.single()
            val teacherErase = requireNotNull(
                sourceTeacherDocument.erase(
                    page = PAGE,
                    path = teacherAsset.points,
                    radius = 20f,
                    wholeStroke = true,
                    authorId = "teacher",
                    attemptNo = 1,
                    deviceId = "teacher-device",
                ),
            )
            source.append(teacherErase)
            val encodedTeacherErase = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "teacher-device",
                1L,
            ).single()
            assertThrows(IllegalArgumentException::class.java) {
                target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedTeacherErase)
            }
            assertTrue(teacherAsset.id in target.loadPage(BOOK_ID, PAGE).activeStrokeIds)

            val studentAsset = studentAdded.addedAssets.single()
            val studentErase = requireNotNull(
                sourceStudentDocument.erase(
                    page = PAGE,
                    path = studentAsset.points,
                    radius = 20f,
                    wholeStroke = true,
                    authorId = "student",
                    attemptNo = 1,
                    deviceId = "student-device",
                ),
            )
            source.append(studentErase)
            val encodedStudentErase = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "student-device",
                1L,
            ).single()
            target.appendEncodedStudentOperation(BOOK_ID, PAGE, encodedStudentErase)
            val finalSnapshot = target.loadPage(BOOK_ID, PAGE)
            assertTrue(studentAsset.id !in finalSnapshot.activeStrokeIds)
            assertTrue(teacherAsset.id in finalSnapshot.activeStrokeIds)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun studentOperationExportSkipsTeacherHistoryCreatedByTheSamePhysicalDevice() {
        val sourceRoot = Files.createTempDirectory("masternote-student-export-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-student-export-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val firstStudent = document.addStroke(stroke("shared-device").copy(attemptNo = 1))
            source.append(firstStudent)
            source.append(
                document.addStroke(
                    stroke("shared-device").copy(
                        authorId = "teacher",
                        attemptNo = 1,
                        publishedAtEpochMillis = null,
                    ),
                ),
            )
            val secondStudent = document.addStroke(
                stroke("shared-device").copy(
                    attemptNo = 1,
                    points = listOf(PagePoint(40f, 40f), PagePoint(50f, 50f)),
                ),
            )
            source.append(secondStudent)

            val outgoing = source.encodedStudentOperationsAfter(
                BOOK_ID,
                PAGE,
                "shared-device",
                0L,
            )
            assertEquals(2, outgoing.size)

            val target = PageOperationLogStore(targetRoot)
            outgoing.forEach { target.appendEncodedStudentOperation(BOOK_ID, PAGE, it) }
            assertEquals(
                setOf(firstStudent.addedAssets.single().id, secondStudent.addedAssets.single().id),
                target.loadPage(BOOK_ID, PAGE).activeStrokeIds,
            )
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun atomicStudentDeltaAppliesOneRecordPreservesTeacherAndReplayIsNoOp() {
        val sourceRoot = Files.createTempDirectory("masternote-atomic-delta-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-atomic-delta-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val sourceDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val first = sourceDocument.addStroke(stroke("student-origin").copy(attemptNo = 1))
            source.append(first)
            val second = sourceDocument.addStroke(
                stroke("student-origin").copy(
                    attemptNo = 2,
                    points = listOf(PagePoint(20f, 20f), PagePoint(30f, 30f)),
                ),
            )
            source.append(second)
            source.append(requireNotNull(sourceDocument.erase(
                page = PAGE,
                path = first.addedAssets.single().points,
                radius = 20f,
                wholeStroke = true,
                authorId = "student",
                attemptNo = 1,
                deviceId = "student-origin",
            )))
            val operations = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                originDeviceId = "student-origin",
                logicalClock = 0L,
            )
            assertEquals(listOf(1L, 2L, 3L), operations.map { source.operationCursor(it).logicalClock })
            val expectedDigest = source.studentLayerSha256(BOOK_ID, PAGE)

            val target = PageOperationLogStore(targetRoot)
            val targetDocument = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val teacher = targetDocument.addStroke(
                stroke("teacher-device").copy(
                    authorId = "teacher",
                    attemptNo = 2,
                    publishedAtEpochMillis = 500L,
                ),
            )
            target.append(teacher)
            val revisionBefore = target.loadPage(BOOK_ID, PAGE).revision

            val applied = target.applyEncodedStudentDelta(
                localBookId = BOOK_ID,
                pageNumber = PAGE,
                encodedOperations = operations,
                expectedOriginDeviceId = "student-origin",
                baseOriginCursor = 0L,
                sourceOriginCursor = 3L,
                allowedAttemptNos = listOf(1, 2),
                expectedResultLayerSha256 = expectedDigest,
            )

            assertTrue(applied.changed)
            assertEquals(revisionBefore + 1L, applied.snapshot.revision)
            assertEquals(expectedDigest, applied.layerSha256)
            assertEquals(3L, applied.sourceOriginCursor)
            assertEquals(setOf(second.addedAssets.single().id), applied.snapshot.activeStrokes
                .filter { it.authorId == "student" }
                .mapTo(hashSetOf(), StrokeAsset::id))
            assertTrue(teacher.addedAssets.single().id in applied.snapshot.activeStrokeIds)
            assertEquals(3L, target.loadPageState(BOOK_ID, PAGE).operationClockHighWater)
            assertEquals(3L, target.maxOperationClock(BOOK_ID, PAGE, "student-layer-delta"))

            val replay = target.applyEncodedStudentDelta(
                localBookId = BOOK_ID,
                pageNumber = PAGE,
                encodedOperations = operations,
                expectedOriginDeviceId = "student-origin",
                baseOriginCursor = 0L,
                sourceOriginCursor = 3L,
                allowedAttemptNos = setOf(1, 2),
                expectedResultLayerSha256 = expectedDigest,
            )
            assertFalse(replay.changed)
            assertEquals(applied.snapshot.revision, replay.snapshot.revision)

            val reloaded = PageOperationLogStore(targetRoot)
            assertEquals(expectedDigest, reloaded.studentLayerSha256(BOOK_ID, PAGE))
            assertTrue(teacher.addedAssets.single().id in reloaded.loadPage(BOOK_ID, PAGE).activeStrokeIds)
            assertEquals(3L, reloaded.loadPageState(BOOK_ID, PAGE).operationClockHighWater)
            assertEquals(3L, reloaded.maxOperationClock(BOOK_ID, PAGE, "student-layer-delta"))
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun malformedIncompleteOrWrongDigestAtomicDeltaLeavesNoPrefixMutation() {
        val sourceRoot = Files.createTempDirectory("masternote-invalid-atomic-delta-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-invalid-atomic-delta-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            source.append(document.addStroke(stroke("student-origin")))
            source.append(document.addStroke(stroke("student-origin").copy(
                points = listOf(PagePoint(40f, 40f), PagePoint(50f, 50f)),
            )))
            val operations = source.encodedOperationsAfter(
                BOOK_ID,
                PAGE,
                "student-origin",
                0L,
            )
            val expectedDigest = source.studentLayerSha256(BOOK_ID, PAGE)

            val target = PageOperationLogStore(targetRoot)
            target.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("teacher").copy(
                        authorId = "teacher",
                        publishedAtEpochMillis = 1L,
                    )),
            )
            val before = target.loadPage(BOOK_ID, PAGE)
            val beforeBytes = target.operationLogFile(BOOK_ID, PAGE).length()

            assertThrows(IllegalArgumentException::class.java) {
                target.applyEncodedStudentDelta(
                    BOOK_ID,
                    PAGE,
                    operations.dropLast(1),
                    "student-origin",
                    0L,
                    2L,
                    setOf(1),
                    expectedDigest,
                )
            }
            assertThrows(Exception::class.java) {
                target.applyEncodedStudentDelta(
                    BOOK_ID,
                    PAGE,
                    listOf(operations.first(), byteArrayOf('{'.code.toByte())),
                    "student-origin",
                    0L,
                    2L,
                    setOf(1),
                    expectedDigest,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                target.applyEncodedStudentDelta(
                    BOOK_ID,
                    PAGE,
                    operations,
                    "student-origin",
                    0L,
                    2L,
                    setOf(1),
                    "0".repeat(64),
                )
            }

            assertEquals(before, target.loadPage(BOOK_ID, PAGE))
            assertEquals(beforeBytes, target.operationLogFile(BOOK_ID, PAGE).length())
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun checkpointExportTupleStaysSelfConsistentWhileStudentWrites() {
        val sourceRoot = Files.createTempDirectory("masternote-export-race-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-export-race-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot)
            val target = PageOperationLogStore(targetRoot)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            val writerFailure = AtomicReference<Throwable?>()
            val writer = Thread {
                runCatching {
                    repeat(12) { index ->
                        source.append(document.addStroke(stroke("race-origin").copy(
                            points = listOf(
                                PagePoint(index.toFloat(), index.toFloat()),
                                PagePoint(index + 1f, index + 1f),
                            ),
                        )))
                        Thread.yield()
                    }
                }.onFailure(writerFailure::set)
            }
            writer.start()
            repeat(16) {
                val exported = source.exportStudentLayerCheckpoint(
                    BOOK_ID,
                    PAGE,
                    originDeviceId = "race-origin",
                )
                val applied = target.applyStudentLayerCheckpoint(
                    "portable-race-target",
                    PAGE,
                    exported.copyCheckpointBytes(),
                )
                assertEquals(exported.layerSha256, applied.layerSha256)
                assertEquals(
                    exported.layerSha256,
                    target.studentLayerSha256("portable-race-target", PAGE),
                )
                val activeHighWater = applied.snapshot.activeStrokes
                    .filter { it.authorId == "student" }
                    .maxOfOrNull(StrokeAsset::logicalClock) ?: 0L
                assertTrue(exported.originDeviceHighWater >= activeHighWater)
            }
            writer.join()
            writerFailure.get()?.let { throw AssertionError("Concurrent writer failed", it) }
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun teacherReviewPublishIntentJournalIsDurableExactAndCorruptionIsIsolated() {
        val root = Files.createTempDirectory("masternote-teacher-publish-intents").toFile()
        try {
            val store = PageOperationLogStore(root)
            store.append(
                AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
                    .addStroke(stroke("student-device")),
            )
            val first = TeacherReviewPublishIntent(BOOK_ID, PAGE, 1, 100L)
            val otherAttempt = TeacherReviewPublishIntent(BOOK_ID, PAGE, 2, 90L)
            val firstPublication = store.recordTeacherReviewPublishIntent(first)
            store.recordTeacherReviewPublishIntent(otherAttempt)
            val repeatedPublication = store.recordTeacherReviewPublishIntent(first.copy(updatedAtEpochMillis = 120L))
            val latestPublication = store.recordTeacherReviewPublishIntent(first.copy(updatedAtEpochMillis = 110L))

            val persisted = PageOperationLogStore(root)
            val intents = persisted.teacherReviewPublishIntents()
            // Every explicit publish is a new delivery intent, even when its immutable bytes are
            // unchanged. Call order wins; wall-clock rollback cannot resurrect the older press.
            assertEquals(listOf(90L, 110L), intents.map { it.updatedAtEpochMillis })
            assertEquals(listOf(2, 1), intents.map { it.attemptNo })
            assertTrue(firstPublication.publicationId != repeatedPublication.publicationId)
            assertTrue(repeatedPublication.publicationId != latestPublication.publicationId)
            intents.forEach { intent ->
                assertTrue(intent.publicationId.isNotEmpty())
                assertTrue(
                    persisted.teacherReviewPublicationArtifact(
                        intent.bookId,
                        intent.pageNumber,
                        intent.attemptNo,
                        intent.publicationId,
                    )!!.copyCheckpointBytes().isNotEmpty(),
                )
            }
            val reloaded = PageOperationLogStore(root)
            assertTrue(reloaded.removeTeacherReviewPublishIntent(BOOK_ID, PAGE, 1))
            assertFalse(reloaded.removeTeacherReviewPublishIntent(BOOK_ID, PAGE, 1))
            assertEquals(listOf(2), PageOperationLogStore(root).teacherReviewPublishIntents().map { it.attemptNo })

            root.resolve("teacher-review-publish-intents.json").writeText("{broken", Charsets.UTF_8)
            val afterCorruption = PageOperationLogStore(root)
            assertTrue(afterCorruption.teacherReviewPublishIntents().isEmpty())
            assertEquals(1L, afterCorruption.loadPage(BOOK_ID, PAGE).revision)
            assertTrue(root.listFiles().orEmpty().any {
                it.name.startsWith("teacher-review-publish-intents.json.corrupt-")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preparedTeacherReviewPromotesAfterRestartOnlyWhenPublishedLayerIsDurable() {
        val root = Files.createTempDirectory("masternote-teacher-publish-prepare").toFile()
        try {
            val store = PageOperationLogStore(root)
            val document = AnnotationDocument(AnnotationSnapshot.empty(BOOK_ID, PAGE))
            store.append(
                document.addStroke(
                    stroke("teacher-device").copy(authorId = "teacher", attemptNo = 1),
                ),
            )
            val published = requireNotNull(document.publishTeacherDrafts(1, "teacher-device"))
            val prepared = store.prepareTeacherReviewPublication(
                intent = TeacherReviewPublishIntent(
                    BOOK_ID,
                    PAGE,
                    1,
                    500L,
                    remotePairId = "pair_telegram_1",
                    remoteWorkbookToken = "workbook_telegram_1",
                    remoteManifestGeneration = 7L,
                    remoteManifestSequence = 9L,
                ),
                publishedSnapshot = published.snapshot,
                publishedMarkGroups = emptyList(),
            )

            val beforeInkCommit = PageOperationLogStore(root)
            assertEquals(listOf(prepared.publicationId), beforeInkCommit
                .teacherReviewPublicationPreparations().map { it.publicationId })
            assertEquals(
                "pair_telegram_1",
                beforeInkCommit.teacherReviewPublicationPreparations().single().remotePairId,
            )
            assertTrue(beforeInkCommit.teacherReviewPublishIntents().isEmpty())
            assertEquals(
                null,
                beforeInkCommit.promotePreparedTeacherReviewPublication(
                    BOOK_ID,
                    PAGE,
                    1,
                    prepared.publicationId,
                    emptyList(),
                ),
            )

            store.append(published)
            val restarted = PageOperationLogStore(root)
            val promoted = requireNotNull(
                restarted.promotePreparedTeacherReviewPublication(
                    BOOK_ID,
                    PAGE,
                    1,
                    prepared.publicationId,
                    emptyList(),
                ),
            )
            assertEquals(prepared.publicationId, promoted.publicationId)
            assertEquals("pair_telegram_1", promoted.remotePairId)
            assertEquals("workbook_telegram_1", promoted.remoteWorkbookToken)
            assertEquals(7L, promoted.remoteManifestGeneration)
            assertEquals(9L, promoted.remoteManifestSequence)
            assertTrue(restarted.teacherReviewPublicationPreparations().isEmpty())
            assertEquals(
                prepared.publicationId,
                restarted.teacherReviewPublishIntents().single().publicationId,
            )
            val promotedAgain = requireNotNull(
                restarted.promotePreparedTeacherReviewPublication(
                    BOOK_ID,
                    PAGE,
                    1,
                    prepared.publicationId,
                    emptyList(),
                ),
            )
            assertEquals(promoted, promotedAgain)
            assertTrue(restarted.teacherReviewPublicationPreparations().isEmpty())
            assertEquals(
                listOf(prepared.publicationId),
                restarted.teacherReviewPublishIntents().map { it.publicationId },
            )
            assertTrue(
                restarted.teacherReviewPublicationArtifact(
                    BOOK_ID,
                    PAGE,
                    1,
                    prepared.publicationId,
                ) != null,
            )
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
