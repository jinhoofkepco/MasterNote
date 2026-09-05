package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintSolver
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.DragTarget
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import com.studyink.core.model.MasterNoteDataRootBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.ConcurrentModificationException
import kotlin.math.hypot

class ConstructionPresentationStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `existing v1 bytes load without rewriting and first edited save retains geometry in v2`() {
        val root = temporary.newFolder("data")
        val store = ConstructionSceneStore(root)
        val legacyBytes = legacyDocument()
        val file = store.targetFileForTest(TARGET).apply { parentFile!!.mkdirs(); writeBytes(legacyBytes) }
        val oldInk = File(root, "student-memos-v1/original.json").apply {
            parentFile!!.mkdirs(); writeText("existing memo must not be migrated")
        }

        val loaded = store.load(TARGET)
        assertEquals(7L, loaded.revision)
        assertEquals(listOf("a", "b", "c"), loaded.scene.points.map { it.id })
        assertTrue(loaded.scene.points.all { it.colorArgb == null })
        assertTrue(loaded.scene.segments.all { it.colorArgb == null })
        assertTrue(loaded.scene.circles.all { it.colorArgb == null })
        assertTrue(loaded.scene.measurements.isEmpty())
        assertArrayEquals("Opening an old document must not rewrite its bytes", legacyBytes, file.readBytes())

        val dimension = GeometryMeasurement("distance-ab", MeasurementType.DISTANCE, listOf("a", "b"), -1.25, 2.5)
        val edited = loaded.scene.copy(
            segments = loaded.scene.segments.map { it.copy(colorArgb = BLUE) },
            measurements = listOf(dimension),
        )
        val committed = store.save(loaded, edited)
        assertEquals(8L, committed.revision)
        assertEquals(2, JSONObject(file.readText()).getInt("schemaVersion"))
        val reopened = ConstructionSceneStore(root).load(TARGET)
        assertEquals(loaded.scene.points, reopened.scene.points)
        assertEquals(loaded.scene.constraints, reopened.scene.constraints)
        assertEquals(loaded.scene.circles, reopened.scene.circles)
        assertEquals(listOf(dimension), reopened.scene.measurements)
        assertEquals(BLUE, reopened.scene.segments.single().colorArgb)
        assertEquals("existing memo must not be migrated", oldInk.readText())
    }

    @Test fun `all presentation types colors ordered anchors and world offsets survive reopen`() {
        val root = temporary.newFolder("data")
        val store = ConstructionSceneStore(root)
        val scene = presentationScene()
        val result = store.save(store.load(TARGET), scene)
        val reopened = ConstructionSceneStore(root).load(TARGET)
        assertEquals(scene, reopened.scene)
        assertEquals(result.revision, reopened.revision)
        assertEquals(listOf("a", "b", "c"), reopened.scene.measurements.single { it.type == MeasurementType.ANGLE }.entityIds)
        assertEquals(-1.25, reopened.scene.measurements.first().offsetX, 0.0)
        assertEquals(2.5, reopened.scene.measurements.first().offsetY, 0.0)
        assertEquals(RED, reopened.scene.points[0].colorArgb)
        assertEquals(BLUE, reopened.scene.segments.single().colorArgb)
        assertEquals(BLACK, reopened.scene.circles.single().colorArgb)
    }

    @Test fun `displayed measurement and its label position do not hold geometry fixed`() {
        val store = ConstructionSceneStore(temporary.newFolder("data"))
        val initial = presentationScene().copy(
            constraints = listOf(GeometryConstraint("fix-a", ConstraintType.FIXED_POINT, listOf("a"), targetX = 0.0, targetY = 0.0)),
        )
        val before = store.save(store.load(TARGET), initial)
        val moved = ConstraintSolver().solve(before.scene, DragTarget("b", 8.0, 0.0))
        assertTrue(moved.message, moved.success)
        val a = moved.scene.point("a")!!
        val b = moved.scene.point("b")!!
        assertEquals(8.0, hypot(b.x - a.x, b.y - a.y), 1e-4)
        assertEquals(initial.measurements, moved.scene.measurements)
        assertEquals(initial.points.single { it.id == "b" }.colorArgb, b.colorArgb)
        val committed = store.save(before, moved.scene)
        assertEquals(moved.scene, store.load(TARGET).scene)
        assertEquals(1, committed.scene.constraints.size)
    }

    @Test fun `annotation collection and anchor lists are immutable edit bases`() {
        val store = ConstructionSceneStore(temporary.newFolder("data"))
        val refs = mutableListOf("a", "b")
        val measurements = mutableListOf(GeometryMeasurement("dimension", MeasurementType.DISTANCE, refs))
        val saved = store.save(store.load(TARGET), presentationScene().copy(measurements = measurements))
        refs.clear(); measurements.clear()
        assertEquals(listOf("a", "b"), saved.scene.measurements.single().entityIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (saved.scene.measurements as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (saved.scene.measurements.single().entityIds as MutableList).clear()
        }
        assertEquals(saved.scene, store.load(TARGET).scene)
    }

    @Test fun `invalid annotations cannot replace an existing valid document`() {
        val store = ConstructionSceneStore(temporary.newFolder("data"))
        val saved = store.save(store.load(TARGET), presentationScene())
        val file = store.targetFileForTest(TARGET)
        val bytes = file.readBytes()
        val invalid = listOf(
            GeometryMeasurement("invalid", MeasurementType.DISTANCE, listOf("a", "ab")),
            GeometryMeasurement("invalid", MeasurementType.ANGLE, listOf("a", "b")),
            GeometryMeasurement("invalid", MeasurementType.AREA, listOf("a", "b", "b")),
            GeometryMeasurement("invalid", MeasurementType.RADIUS, listOf("a")),
            GeometryMeasurement("invalid", MeasurementType.DISTANCE, listOf("a", "b"), Double.NaN),
            GeometryMeasurement("a", MeasurementType.DISTANCE, listOf("a", "b")),
        )
        invalid.forEach { measurement ->
            assertThrows(IllegalArgumentException::class.java) {
                store.save(saved, saved.scene.copy(measurements = listOf(measurement)))
            }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun `malformed color and annotation metadata fail visibly even with a valid payload checksum`() {
        val store = ConstructionSceneStore(temporary.newFolder("data"))
        store.save(store.load(TARGET), presentationScene())
        val file = store.targetFileForTest(TARGET)
        val original = file.readBytes()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("points").getJSONObject(0).put("colorArgb", 1.25) },
            { it.getJSONArray("segments").getJSONObject(0).put("colorArgb", 4_294_967_295L) },
            { it.getJSONArray("measurements").getJSONObject(0).put("type", "UNKNOWN") },
            { it.getJSONArray("measurements").getJSONObject(0).put("entityIds", org.json.JSONArray(listOf("missing", "b"))) },
            { it.put("measurements", JSONObject.NULL) },
        )
        mutations.forEach { mutate ->
            val envelope = JSONObject(original.toString(Charsets.UTF_8))
            val payload = JSONObject(envelope.getString("sceneJson")).also(mutate).toString()
            envelope.put("sceneJson", payload).put("sceneSha256", digest(payload))
            val corrupt = envelope.toString().toByteArray(Charsets.UTF_8)
            file.writeBytes(corrupt)
            assertThrows(ConstructionDataException::class.java) { store.load(TARGET) }
            assertArrayEquals(corrupt, file.readBytes())
        }
    }

    @Test fun `restoring a v1 backup after saving annotations invalidates the newer edit base`() {
        val root = temporary.newFolder("data")
        val store = ConstructionSceneStore(root)
        val file = store.targetFileForTest(TARGET).apply { parentFile!!.mkdirs(); writeBytes(legacyDocument()) }
        val old = store.load(TARGET)
        val newer = store.save(old, old.scene.copy(measurements = listOf(
            GeometryMeasurement("distance-ab", MeasurementType.DISTANCE, listOf("a", "b")),
        )))
        MasterNoteOptionalDataRootGuard.withStableDataRoot(root) {
            file.writeBytes(legacyDocument())
            MasterNoteDataRootBus.dataRootReplaced()
        }
        assertThrows(ConcurrentModificationException::class.java) { store.save(newer, presentationScene()) }
        val restored = store.load(TARGET)
        assertEquals(7L, restored.revision)
        assertTrue(restored.scene.measurements.isEmpty())
        assertTrue(restored.scene.points.all { it.colorArgb == null })
    }

    private fun presentationScene() = ConstructionScene(
        points = listOf(
            GeometryPoint("a", 0.0, 0.0, "A", RED),
            GeometryPoint("b", 5.0, 0.0, "B", BLUE),
            GeometryPoint("c", 5.0, 4.0, "C", BLACK),
        ),
        segments = listOf(GeometrySegment("ab", "a", "b", colorArgb = BLUE)),
        circles = listOf(GeometryCircle("circle", "a", 5.0, colorArgb = BLACK)),
        measurements = listOf(
            GeometryMeasurement("distance", MeasurementType.DISTANCE, listOf("a", "b"), -1.25, 2.5),
            GeometryMeasurement("angle", MeasurementType.ANGLE, listOf("a", "b", "c"), 0.125, -0.5),
            GeometryMeasurement("radius", MeasurementType.RADIUS, listOf("circle"), -2.0, -1.0),
            GeometryMeasurement("area", MeasurementType.AREA, listOf("a", "b", "c"), 0.5, 0.0),
        ),
    )

    /** This is the original v1 wire shape: no colors, measurements, or display offsets. */
    private fun legacyDocument(): ByteArray {
        val payload = """{"points":[{"id":"a","x":0,"y":0,"label":"A"},{"id":"b","x":5,"y":0,"label":"B"},{"id":"c","x":5,"y":4,"label":"C"}],"segments":[{"id":"ab","startPointId":"a","endPointId":"b","label":""}],"circles":[{"id":"circle","centerPointId":"a","radius":5,"label":""}],"constraints":[{"id":"length","type":"LENGTH","entityIds":["ab"],"value":5,"targetX":null,"targetY":null,"enabled":true}]}"""
        return JSONObject().put("schemaVersion", 1).put("revision", 7L)
            .put("commitId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            .put("target", JSONObject().put("bookId", TARGET.bookId).put("pageNumber", TARGET.pageNumber)
                .put("attemptNo", TARGET.attemptNo).put("memoId", TARGET.memoId).put("ownerScope", TARGET.ownerScope))
            .put("sceneJson", payload).put("sceneSha256", digest(payload))
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun digest(payload: String) = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val RED = -0x36c6c7
        private const val BLUE = -0xe2913a
        private const val BLACK = -0xd3c4b8
        private val TARGET = ConstructionTarget("book", 0, 1, "11111111-1111-4111-8111-111111111111")
    }
}
