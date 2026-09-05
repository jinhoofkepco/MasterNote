package com.studyink.construction.storage

import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConstructionRelationsStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `legacy constraint scene keeps canonical digest and has no new option fields`() {
        val legacy = ConstructionScene(
            points = listOf(GeometryPoint("a", 0.0, 0.0), GeometryPoint("b", 5.0, 0.0)),
            segments = listOf(GeometrySegment("ab", "a", "b")),
            constraints = listOf(GeometryConstraint("length", ConstraintType.LENGTH, listOf("ab"), 5.0)),
        )
        val frozen = """{"attached":true,"deleted":false,"scene":{"circles":[],"constraints":[{"enabled":true,"entityIds":["ab"],"id":"length","targetX":null,"targetY":null,"type":"LENGTH","value":5}],"measurements":[],"points":[{"colorArgb":null,"id":"a","label":"","x":0,"y":0},{"colorArgb":null,"id":"b","label":"","x":5,"y":0}],"segments":[{"colorArgb":null,"endPointId":"b","id":"ab","label":"","startPointId":"a"}]}}"""
        assertEquals(ConstructionSyncCodec.sha256(frozen.toByteArray()), ConstructionSyncCodec.sceneDigest(legacy))
        val json = ConstructionJsonCodec.encodeScene(legacy)
        val constraint = json.getJSONArray("constraints").getJSONObject(0)
        listOf("numerator", "denominator", "fromEnd", "allowExtension").forEach { assertFalse(constraint.has(it)) }
        assertEquals(legacy, ConstructionJsonCodec.decodeScene(json))
    }

    @Test fun `all new relations and exact fraction options survive store reopen`() {
        val root = temporary.newFolder("store")
        val store = ConstructionSceneStore(root)
        val original = scene()
        val saved = store.save(store.load(TARGET), original)
        assertEquals(original, ConstructionSceneStore(root).load(TARGET).scene)
        assertEquals(saved.revision, ConstructionSceneStore(root).load(TARGET).revision)
        val rational = original.constraints.first { it.type == ConstraintType.POINT_FRACTION }
        val json = ConstructionJsonCodec.encodeScene(original)
        val fractionJson = json.getJSONArray("constraints").getJSONObject(1)
        assertEquals(1, fractionJson.getInt("numerator"))
        assertEquals(3, fractionJson.getInt("denominator"))
        assertEquals(rational, ConstructionJsonCodec.decodeScene(json).constraints[1])
    }

    @Test fun `new relations survive student snapshot teacher draft publication receipt and reopen`() {
        val studentRoot = temporary.newFolder("student")
        val teacherRoot = temporary.newFolder("teacher")
        val student = ConstructionReplicaStore(studentRoot)
        val teacher = ConstructionReplicaStore(teacherRoot)
        student.saveLocal(student.load(TARGET, ConstructionReplicaRole.STUDENT), scene())
        val base = teacher.receiveStudentSnapshot(TARGET, wire(student.studentSnapshot(TARGET)))
        val modified = scene().copy(constraints = scene().constraints.map {
            if (it.type == ConstraintType.POINT_FRACTION) it.copy(numerator = 2) else it
        })
        val draft = teacher.saveLocal(base, modified)
        assertNotEquals(ConstructionSyncCodec.sceneDigest(scene()), ConstructionSyncCodec.sceneDigest(modified))
        val publication = teacher.preparePublish(draft).packet!!
        val reply = student.receivePublish(TARGET, wire(publication))
        assertEquals(ConstructionPublishResult.APPLIED, reply.result)
        val accepted = teacher.receiveResult(TARGET, wire(reply))
        assertFalse(accepted.draftDirty)
        assertEquals(modified, ConstructionReplicaStore(studentRoot).load(TARGET, ConstructionReplicaRole.STUDENT).scene)
        assertEquals(modified, ConstructionReplicaStore(teacherRoot).load(TARGET, ConstructionReplicaRole.TEACHER).scene)
        assertEquals(reply, student.receivePublish(TARGET, wire(publication)))
    }

    @Test fun `rational decoder rejects fractional integers missing values and out of range denominators`() {
        listOf("numerator" to 1.5, "numerator" to "1", "numerator" to 0, "numerator" to JSONObject.NULL,
            "denominator" to 1, "denominator" to 1_000_001, "denominator" to 3.2).forEach { (key, value) ->
            val json = ConstructionJsonCodec.encodeScene(scene())
            json.getJSONArray("constraints").getJSONObject(1).put(key, value)
            assertThrows(Exception::class.java) { ConstructionJsonCodec.decodeScene(json) }
        }
        val missing = ConstructionJsonCodec.encodeScene(scene())
        missing.getJSONArray("constraints").getJSONObject(1).remove("denominator")
        assertThrows(Exception::class.java) { ConstructionJsonCodec.decodeScene(missing) }
    }

    @Test fun `optional booleans are strict and cannot silently change old relation semantics`() {
        listOf("true", 1, JSONObject.NULL).forEach { value ->
            val json = ConstructionJsonCodec.encodeScene(scene())
            json.getJSONArray("constraints").getJSONObject(2).put("allowExtension", value)
            assertThrows(Exception::class.java) { ConstructionJsonCodec.decodeScene(json) }
        }
        val json = ConstructionJsonCodec.encodeScene(scene())
        json.getJSONArray("constraints").getJSONObject(0).put("fromEnd", true)
        assertThrows(Exception::class.java) { ConstructionJsonCodec.decodeScene(json) }
    }

    @Test fun `six shared angle references are supported but excess or invalid references fail`() {
        assertEquals(scene(), ConstructionJsonCodec.decodeScene(ConstructionJsonCodec.encodeScene(scene())))
        val extra = ConstructionJsonCodec.encodeScene(scene())
        extra.getJSONArray("constraints").getJSONObject(5).getJSONArray("entityIds").put("p")
        assertThrows(Exception::class.java) { ConstructionJsonCodec.decodeScene(extra) }
        val sameAngle = ConstructionJsonCodec.encodeScene(scene())
        sameAngle.getJSONArray("constraints").getJSONObject(5).put("entityIds", JSONArray(listOf("a", "p", "c", "c", "p", "a")))
        assertThrows(Exception::class.java) { ConstructionJsonCodec.decodeScene(sameAngle) }
        val unknown = ConstructionJsonCodec.encodeScene(scene())
        unknown.getJSONArray("constraints").getJSONObject(0).put("type", "FUTURE_RELATION")
        assertThrows(Exception::class.java) { ConstructionJsonCodec.decodeScene(unknown) }
    }

    private fun wire(packet: ConstructionSyncPacket) = ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(packet))
    private fun scene() = ConstructionScene(
        points = listOf(GeometryPoint("a", 0.0, 0.0), GeometryPoint("b", 12.0, 0.0), GeometryPoint("p", 4.0, 0.0),
            GeometryPoint("c", 0.0, 6.0), GeometryPoint("d", 6.0, 6.0)),
        segments = listOf(GeometrySegment("ab", "a", "b"), GeometrySegment("cd", "c", "d")),
        constraints = listOf(
            GeometryConstraint("bounded", ConstraintType.POINT_ON_SEGMENT, listOf("p", "ab")),
            GeometryConstraint("third", ConstraintType.POINT_FRACTION, listOf("p", "ab"), numerator = 1, denominator = 3),
            GeometryConstraint("distance", ConstraintType.POINT_DISTANCE, listOf("p", "ab"), 8.0,
                enabled = false, fromEnd = true, allowExtension = true),
            GeometryConstraint("ratio", ConstraintType.LENGTH_RATIO, listOf("ab", "cd"), 2.0),
            GeometryConstraint("angle", ConstraintType.INTERIOR_ANGLE, listOf("a", "p", "c"), 60.0, enabled = false),
            GeometryConstraint("angles", ConstraintType.EQUAL_ANGLE, listOf("a", "p", "c", "c", "p", "b"), enabled = false),
        ),
    )

    companion object {
        private val TARGET = ConstructionTarget("book", 0, 1, "11111111-1111-4111-8111-111111111111")
    }
}
