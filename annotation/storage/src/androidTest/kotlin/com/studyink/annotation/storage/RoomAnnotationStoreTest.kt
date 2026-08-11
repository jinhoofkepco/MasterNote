package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.AnnotationOperationType
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RoomAnnotationStoreTest {
    private lateinit var database: AnnotationDatabase
    private lateinit var store: RoomAnnotationStore

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AnnotationDatabase::class.java).build()
        store = RoomAnnotationStore(database)
    }

    @After
    fun closeDatabase() {
        store.close()
    }

    @Test
    fun inkCodecRoundTripsInputsUsingStableStreamApi() {
        val points = listOf(
            PagePoint(12.5f, 20.25f, pressure = 0.2f, elapsedTimeMillis = 10L),
            PagePoint(40f, 60f, pressure = 0.8f, elapsedTimeMillis = 17L),
        )

        val decoded = InkStrokeCodec.decode(InkStrokeCodec.encode(points))

        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, 0.001f)
            assertEquals(expected.y, actual.y, 0.001f)
            assertEquals(expected.pressure, actual.pressure, 0.001f)
            assertEquals(expected.elapsedTimeMillis, actual.elapsedTimeMillis)
        }
    }

    @Test
    fun strokesOnFivePagesRestoreWithIndependentPageRevisions() = runTest {
        val document = AnnotationDocument(AnnotationSnapshot.empty("five-pages"))
        repeat(5) { page -> store.applyMutation(document.addStroke(stroke(page, 20f + page))) }

        val restored = store.load("five-pages")

        assertEquals(5L, restored.revision)
        assertEquals((0..4).associateWith { 1L }, restored.pageRevisions)
        assertEquals((0..4).toSet(), restored.activeStrokes.map { it.pageNumber }.toSet())
    }

    @Test
    fun partialEraseIsCommittedAsOneReplaceOperation() = runTest {
        val document = AnnotationDocument(AnnotationSnapshot.empty("replace-success"))
        val original = stroke(page = 0, y = 50f)
        store.applyMutation(document.addStroke(original))

        val mutation = requireNotNull(
            document.erase(
                page = 0,
                path = listOf(PagePoint(50f, 35f), PagePoint(50f, 65f)),
                radius = 8f,
                wholeStroke = false,
            )
        )
        assertEquals(AnnotationOperationType.REPLACE_STROKES, mutation.operation.operationType)
        store.applyMutation(mutation)

        val restored = store.load("replace-success")
        assertTrue(original.id !in restored.activeStrokeIds)
        assertEquals(2, restored.activeStrokes.size)
        assertTrue(restored.activeStrokes.all { it.parentStrokeId == original.id })
        assertEquals(2, database.annotationDao().operationCount(RoomAnnotationStore.pageId("replace-success", 0)))
    }

    @Test
    fun injectedReplaceFailureRollsBackAssetsLinksOperationAndRevision() = runTest {
        val document = AnnotationDocument(AnnotationSnapshot.empty("replace-rollback"))
        val original = stroke(page = 0, y = 50f)
        store.applyMutation(document.addStroke(original))
        val before = store.load("replace-rollback")
        val mutation = requireNotNull(
            document.erase(
                page = 0,
                path = listOf(PagePoint(50f, 35f), PagePoint(50f, 65f)),
                radius = 8f,
                wholeStroke = false,
            )
        )
        val failingStore = RoomAnnotationStore(
            database = database,
            faultInjector = AnnotationTransactionFaultInjector { throw IOException("injected") },
        )

        val failure = runCatching { failingStore.applyMutation(mutation) }.exceptionOrNull()

        assertTrue(failure is IOException)
        val restored = store.load("replace-rollback")
        assertEquals(before.revision, restored.revision)
        assertEquals(before.pageRevisions, restored.pageRevisions)
        assertEquals(setOf(original.id), restored.activeStrokeIds)
        assertEquals(setOf(original.id), restored.assets.keys)
        assertEquals(1, database.annotationDao().operationCount(RoomAnnotationStore.pageId("replace-rollback", 0)))
    }

    @Test
    fun corruptStrokeBlobIsSkippedWithoutBlockingOtherStrokes() = runTest {
        val document = AnnotationDocument(AnnotationSnapshot.empty("corrupt-one"))
        val healthy = stroke(page = 0, y = 20f)
        val corrupt = stroke(page = 0, y = 80f)
        store.applyMutation(document.addStroke(healthy))
        store.applyMutation(document.addStroke(corrupt))
        database.annotationDao().replaceEncodedInputForTest(corrupt.id.value, byteArrayOf(1, 2, 3))

        val restored = store.load("corrupt-one")

        assertEquals(2L, restored.revision)
        assertEquals(setOf(healthy.id), restored.activeStrokeIds)
        assertTrue(healthy.id in restored.assets)
        assertTrue(corrupt.id !in restored.assets)
        assertNotEquals(AnnotationSnapshot.empty("corrupt-one"), restored)
    }

    private fun stroke(page: Int, y: Float) = StrokeAsset(
        pageNumber = page,
        tool = StrokeTool.PEN,
        colorArgb = 0xff112233.toInt(),
        width = 4f,
        points = listOf(
            PagePoint(0f, y, pressure = 0.4f, elapsedTimeMillis = 1L),
            PagePoint(50f, y, pressure = 0.6f, elapsedTimeMillis = 5L),
            PagePoint(100f, y, pressure = 0.8f, elapsedTimeMillis = 10L),
        ),
    )
}
