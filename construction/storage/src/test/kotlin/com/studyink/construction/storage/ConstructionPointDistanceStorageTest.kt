package com.studyink.construction.storage

import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.MeasurementType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConstructionPointDistanceStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = ConstructionTarget("book", 0, 1, "11111111-1111-4111-8111-111111111111")

    @Test fun `point distances preserve measurement anchors and offsets across local reopen`() {
        val root = temporary.newFolder("local")
        val store = ConstructionSceneStore(root)
        val original = scene()
        store.save(store.load(target), original)
        assertEquals(original, ConstructionSceneStore(root).load(target).scene)
        assertTrue(ConstructionSceneStore(root).load(target).scene.segments.isEmpty())
    }

    @Test fun `point distance and four reference equality survive publication application receipt`() {
        val student = ConstructionReplicaStore(temporary.newFolder("student"))
        val teacher = ConstructionReplicaStore(temporary.newFolder("teacher"))
        student.saveLocal(student.load(target, ConstructionReplicaRole.STUDENT), scene())
        val base = teacher.receiveStudentSnapshot(target, wire(student.studentSnapshot(target)))
        val changed = scene().copy(constraints = scene().constraints.map { if (it.id == "fixed") it.copy(value = 8.0) else it })
        val draft = teacher.saveLocal(base, changed)
        val publication = teacher.preparePublish(draft).packet!!
        val result = student.receivePublish(target, wire(publication))
        assertEquals(ConstructionPublishResult.APPLIED, result.result)
        assertEquals(changed, teacher.receiveResult(target, wire(result)).scene)
        assertEquals(changed, student.load(target, ConstructionReplicaRole.STUDENT).scene)
    }

    @Test fun `old scenes keep exact JSON after point distance support`() {
        // No additional fields/version changes are needed: the new semantics have explicit enum names.
        val original = scene().copy(constraints = emptyList())
        val encoded = ConstructionJsonCodec.encodeScene(original).toString()
        val decoded = ConstructionJsonCodec.decodeScene(org.json.JSONObject(encoded))
        assertEquals(original, decoded)
        assertEquals(encoded, ConstructionJsonCodec.encodeScene(decoded).toString())
        assertEquals(ConstructionSyncCodec.sceneDigest(original), ConstructionSyncCodec.sceneDigest(decoded))
    }

    private fun wire(packet: ConstructionSyncPacket) = ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(packet))
    private fun scene() = ConstructionScene(
        points = listOf(GeometryPoint("a", 0.0, 0.0), GeometryPoint("b", 5.0, 0.0), GeometryPoint("c", 0.0, 5.0)),
        constraints = listOf(GeometryConstraint("fixed", ConstraintType.DISTANCE_POINTS, listOf("a", "b"), 5.0),
            GeometryConstraint("equal", ConstraintType.EQUAL_DISTANCE_POINTS, listOf("a", "b", "a", "c"), enabled = false)),
        measurements = listOf(GeometryMeasurement("ab", MeasurementType.DISTANCE, listOf("a", "b"), 2.5, -3.25)),
    )
}
