package com.studyink.construction.storage

import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.MasterNoteDataRootBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.ConcurrentModificationException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConstructionSceneStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `save reopen preserves every geometry and condition field without touching old data`() {
        val root = temporary.newFolder("data")
        val originalStroke = File(root, "annotations-v2/old-page.json").apply {
            parentFile!!.mkdirs()
            writeText("original handwriting bytes")
        }
        val store = ConstructionSceneStore(root)
        val empty = store.load(TARGET)
        assertEquals(0L, empty.revision)
        assertEquals(ConstructionScene(), empty.scene)
        assertFalse(store.targetFileForTest(TARGET).exists())

        val committed = store.save(empty, exampleScene())
        assertEquals(1L, committed.revision)
        val reopened = ConstructionSceneStore(root).load(TARGET)
        assertEquals(committed.scene, reopened.scene)
        assertEquals(committed.commitId, reopened.commitId)
        assertEquals("original handwriting bytes", originalStroke.readText())
        assertTrue(store.targetFileForTest(TARGET).canonicalPath.startsWith(
            File(root, ConstructionSceneStore.FEATURE_DIRECTORY).canonicalPath + File.separator,
        ))
    }

    @Test fun `book page attempt memo and owner each isolate a document and path input is hashed`() {
        val root = temporary.newFolder("data")
        val store = ConstructionSceneStore(root)
        val targets = listOf(
            TARGET, TARGET.copy(bookId = "../../another/book"), TARGET.copy(pageNumber = 1),
            TARGET.copy(attemptNo = 0), TARGET.copy(attemptNo = 2),
            TARGET.copy(memoId = "22222222-2222-4222-8222-222222222222"),
            TARGET.copy(ownerScope = "teacher:device-A"),
        )
        targets.forEachIndexed { index, target ->
            store.save(store.load(target), ConstructionScene(points = listOf(GeometryPoint("p", index.toDouble(), 0.0))))
        }
        assertEquals(targets.size, targets.map(store::targetFileForTest).distinct().size)
        targets.forEachIndexed { index, target ->
            assertEquals(index.toDouble(), store.load(target).scene.points.single().x, 0.0)
            assertTrue(store.targetFileForTest(target).canonicalPath.startsWith(root.canonicalPath + File.separator))
        }
    }

    @Test fun `CAS rejects stale editors across store instances without modifying bytes`() {
        val root = temporary.newFolder("data")
        val first = ConstructionSceneStore(root)
        val second = ConstructionSceneStore(root)
        val stale = second.load(TARGET)
        val committed = first.save(first.load(TARGET), exampleScene())
        val before = first.targetFileForTest(TARGET).readBytes()
        assertThrows(ConcurrentModificationException::class.java) {
            second.save(stale, ConstructionScene())
        }
        assertArrayEquals(before, first.targetFileForTest(TARGET).readBytes())
        val cleared = first.save(committed, ConstructionScene())
        assertEquals(2L, cleared.revision)
        assertTrue(first.targetFileForTest(TARGET).exists())
        assertEquals(ConstructionScene(), second.load(TARGET).scene)
    }

    @Test fun `saving to a different data root with an edit base is rejected`() {
        val a = ConstructionSceneStore(temporary.newFolder("a"))
        val b = ConstructionSceneStore(temporary.newFolder("b"))
        assertThrows(ConcurrentModificationException::class.java) { b.save(a.load(TARGET), exampleScene()) }
        assertFalse(b.targetFileForTest(TARGET).exists())
    }

    @Test fun `restore invalidates even an identical revision and commit token`() {
        val root = temporary.newFolder("data")
        val store = ConstructionSceneStore(root)
        val oldEditBase = store.save(store.load(TARGET), exampleScene())
        val oldBytes = store.targetFileForTest(TARGET).readBytes()
        store.save(oldEditBase, ConstructionScene())
        val notifications = AtomicInteger()
        store.addRestoreListener { notifications.incrementAndGet() }.use {
            MasterNoteOptionalDataRootGuard.withStableDataRoot(root) {
                store.targetFileForTest(TARGET).writeBytes(oldBytes)
                MasterNoteDataRootBus.dataRootReplaced()
            }
        }
        assertEquals(1, notifications.get())
        assertThrows(ConcurrentModificationException::class.java) {
            store.save(oldEditBase, ConstructionScene())
        }
        assertArrayEquals(oldBytes, store.targetFileForTest(TARGET).readBytes())
        assertEquals(2L, store.save(store.load(TARGET), ConstructionScene()).revision)
    }

    @Test fun `same revision with a different commit identity cannot overwrite restored state`() {
        val root = temporary.newFolder("data")
        val store = ConstructionSceneStore(root)
        val expected = store.save(store.load(TARGET), exampleScene())
        val external = ConstructionJsonCodec.encode(StoredConstructionDocument(
            TARGET, expected.revision, UUID.randomUUID().toString(), ConstructionScene(),
        ))
        store.targetFileForTest(TARGET).writeBytes(external)
        assertThrows(ConcurrentModificationException::class.java) { store.save(expected, exampleScene()) }
        assertArrayEquals(external, store.targetFileForTest(TARGET).readBytes())
    }

    @Test fun `corruption fails load and stale save preserving bytes and backup generation`() {
        val root = temporary.newFolder("data")
        val store = ConstructionSceneStore(root)
        val expected = store.save(store.load(TARGET), exampleScene())
        val base = store.targetFileForTest(TARGET)
        val valid = base.readBytes()
        val tampered = JSONObject(valid.toString(Charsets.UTF_8)).apply {
            put("sceneJson", getString("sceneJson").replace("10", "11"))
        }.toString().toByteArray()
        val badDocuments = listOf(
            "{ broken json".toByteArray(), tampered,
            JSONObject(valid.toString(Charsets.UTF_8)).put("schemaVersion", 9).toString().toByteArray(),
            JSONObject(valid.toString(Charsets.UTF_8)).put("revision", 1.5).toString().toByteArray(),
            ByteArray(ConstructionJsonCodec.MAX_BYTES + 1),
            ConstructionJsonCodec.encode(StoredConstructionDocument(
                TARGET.copy(pageNumber = 9), 1L, UUID.randomUUID().toString(), exampleScene(),
            )),
        )
        badDocuments.forEach { bytes ->
            base.writeBytes(bytes)
            val generation = MasterNoteDataCommitBus.currentGeneration()
            assertThrows(ConstructionDataException::class.java) { store.load(TARGET) }
            assertThrows(ConstructionDataException::class.java) { store.save(expected, ConstructionScene()) }
            assertArrayEquals(bytes, base.readBytes())
            assertEquals(generation, MasterNoteDataCommitBus.currentGeneration())
        }
    }

    @Test fun `invalid graph is rejected before replacing the last good scene`() {
        val root = temporary.newFolder("data")
        val store = ConstructionSceneStore(root)
        val expected = store.save(store.load(TARGET), exampleScene())
        val before = store.targetFileForTest(TARGET).readBytes()
        val invalid = exampleScene().copy(segments = listOf(GeometrySegment("missing", "o", "unknown")))
        assertThrows(IllegalArgumentException::class.java) { store.save(expected, invalid) }
        assertArrayEquals(before, store.targetFileForTest(TARGET).readBytes())
    }

    @Test fun `input collections and snapshot collections cannot mutate a saved edit base`() {
        val store = ConstructionSceneStore(temporary.newFolder("data"))
        val mutablePoints = mutableListOf(GeometryPoint("o", 0.0, 0.0))
        val mutableRefs = mutableListOf("o")
        val condition = GeometryConstraint("f", ConstraintType.FIXED_POINT, mutableRefs, targetX = 0.0, targetY = 0.0)
        val result = store.save(store.load(TARGET), ConstructionScene(points = mutablePoints, constraints = listOf(condition)))
        mutablePoints.clear()
        mutableRefs.clear()
        assertEquals("o", result.scene.points.single().id)
        assertEquals(listOf("o"), result.scene.constraints.single().entityIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.scene.points as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.scene.constraints.single().entityIds as MutableList).clear()
        }
        assertEquals(result.scene, store.load(TARGET).scene)
    }

    @Test fun `legacy AtomicFile backup is recovered instead of reading partial base`() {
        val store = ConstructionSceneStore(temporary.newFolder("data"))
        val expected = store.save(store.load(TARGET), exampleScene())
        val base = store.targetFileForTest(TARGET)
        val bytes = base.readBytes()
        File(base.path + ".bak").writeBytes(bytes)
        base.writeText("partial replacement")
        assertEquals(expected.scene, store.load(TARGET).scene)
        assertArrayEquals(bytes, base.readBytes())
    }

    @Test fun `whole root backup guard excludes a geometry commit and commit signal follows durable write`() {
        val root = temporary.newFolder("data")
        val store = ConstructionSceneStore(root)
        val expected = store.load(TARGET)
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val signals = AtomicInteger()
        val subscription = MasterNoteDataCommitBus.addListener {
            assertEquals(exampleScene(), store.load(TARGET).scene)
            signals.incrementAndGet()
        }
        try {
            val future = MasterNoteOptionalDataRootGuard.withStableDataRoot(root) {
                val pending = worker.submit<ConstructionSceneSnapshot> {
                    started.countDown()
                    store.save(expected, exampleScene())
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertFalse(pending.isDone)
                assertFalse(store.targetFileForTest(TARGET).exists())
                pending
            }
            assertEquals(1L, future.get(5, TimeUnit.SECONDS).revision)
            assertEquals(1, signals.get())
        } finally {
            subscription.close()
            worker.shutdownNow()
        }
    }

    private fun exampleScene() = ConstructionScene(
        points = listOf(
            GeometryPoint("o", 0.0, 0.0, "ㄱ"), GeometryPoint("p", 10.0, 0.0, "P"),
            GeometryPoint("q", 10.0, 0.0, "Q"), GeometryPoint("r", 10.0, 6.0, "R"),
        ),
        segments = listOf(GeometrySegment("op", "o", "p", "10cm"), GeometrySegment("qr", "q", "r")),
        circles = listOf(GeometryCircle("circle", "o", 10.0, "보조원")),
        constraints = listOf(
            GeometryConstraint("fixed", ConstraintType.FIXED_POINT, listOf("o"), targetX = 0.0, targetY = 0.0),
            GeometryConstraint("join", ConstraintType.COINCIDENT, listOf("p", "q")),
            GeometryConstraint("line", ConstraintType.POINT_ON_LINE, listOf("q", "op")),
            GeometryConstraint("on-circle", ConstraintType.POINT_ON_CIRCLE, listOf("p", "circle")),
            GeometryConstraint("len", ConstraintType.LENGTH, listOf("qr"), value = 6.0),
            GeometryConstraint("radius", ConstraintType.RADIUS, listOf("circle"), value = 10.0),
            GeometryConstraint("parallel", ConstraintType.PARALLEL, listOf("op", "qr"), enabled = false),
            GeometryConstraint("perp", ConstraintType.PERPENDICULAR, listOf("op", "qr")),
            GeometryConstraint("equal", ConstraintType.EQUAL_LENGTH, listOf("op", "qr"), enabled = false),
            GeometryConstraint("angle", ConstraintType.ANGLE, listOf("op", "qr"), value = 90.0, enabled = false),
        ),
    )

    companion object {
        private val TARGET = ConstructionTarget("book", 0, 1, "11111111-1111-4111-8111-111111111111")
    }
}
