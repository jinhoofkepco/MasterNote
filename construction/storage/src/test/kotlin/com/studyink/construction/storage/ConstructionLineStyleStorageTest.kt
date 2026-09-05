package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryLineStyle
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConstructionLineStyleStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `legacy solid scene keeps exact canonical digest and omitted style fields`() {
        val base = scene()
        // Frozen canonical representation before line styles were introduced. Existing replicas
        // and in-flight publish acknowledgements already contain this digest; it must not change.
        val legacyCanonical = """{"attached":true,"deleted":false,"scene":{"circles":[{"centerPointId":"a","colorArgb":null,"id":"circle","label":"","radius":3}],"constraints":[],"measurements":[],"points":[{"colorArgb":null,"id":"a","label":"A","x":0,"y":0},{"colorArgb":null,"id":"b","label":"B","x":5,"y":0}],"segments":[{"colorArgb":null,"endPointId":"b","id":"ab","label":"","startPointId":"a"}]}}"""
        val oldDigest = ConstructionSyncCodec.sha256(legacyCanonical.toByteArray(Charsets.UTF_8))
        assertEquals(oldDigest, ConstructionSyncCodec.sceneDigest(base))
        val json = ConstructionJsonCodec.encodeScene(base)
        assertFalse(json.getJSONArray("segments").getJSONObject(0).has("lineStyle"))
        assertFalse(json.getJSONArray("circles").getJSONObject(0).has("lineStyle"))
        assertEquals(base, ConstructionJsonCodec.decodeScene(json))
    }

    @Test fun `all three line styles survive scene store reopen without changing geometry`() {
        val root = temporary.newFolder("store")
        val store = ConstructionSceneStore(root)
        var editBase = store.load(TARGET)
        GeometryLineStyle.entries.forEach { style ->
            val styled = scene().copy(
                segments = scene().segments.map { it.copy(lineStyle = style) },
                circles = scene().circles.map { it.copy(lineStyle = style) },
            )
            editBase = store.save(editBase, styled)
            val restored = ConstructionSceneStore(root).load(TARGET)
            assertEquals(styled, restored.scene)
            assertEquals(scene().points, restored.scene.points)
            assertEquals(style, restored.scene.segments.single().lineStyle)
            assertEquals(style, restored.scene.circles.single().lineStyle)
        }
    }

    @Test fun `style-only change participates in replica comparison and survives publication`() {
        val studentRoot = temporary.newFolder("student")
        val teacherRoot = temporary.newFolder("teacher")
        val student = ConstructionReplicaStore(studentRoot)
        val teacher = ConstructionReplicaStore(teacherRoot)
        student.saveLocal(student.load(TARGET, ConstructionReplicaRole.STUDENT), scene())
        val snapshot = student.studentSnapshot(TARGET)
        val teacherBase = teacher.receiveStudentSnapshot(TARGET, wire(snapshot))
        val styled = scene().copy(
            segments = scene().segments.map { it.copy(lineStyle = GeometryLineStyle.DASHED) },
            circles = scene().circles.map { it.copy(lineStyle = GeometryLineStyle.DOTTED) },
        )
        assertNotEquals(ConstructionSyncCodec.sceneDigest(scene()), ConstructionSyncCodec.sceneDigest(styled))
        val draft = teacher.saveLocal(teacherBase, styled)
        assertTrue(draft.draftDirty)
        val publication = teacher.preparePublish(draft).packet!!
        val reply = student.receivePublish(TARGET, wire(publication))
        assertEquals(ConstructionPublishResult.APPLIED, reply.result)
        val accepted = teacher.receiveResult(TARGET, wire(reply))
        assertFalse(accepted.draftDirty)
        assertEquals(styled, ConstructionReplicaStore(studentRoot).load(TARGET, ConstructionReplicaRole.STUDENT).scene)
        assertEquals(styled, ConstructionReplicaStore(teacherRoot).load(TARGET, ConstructionReplicaRole.TEACHER).scene)
    }

    @Test fun `unknown or malformed style is rejected instead of silently losing its value`() {
        listOf("DASH_DOT_FUTURE", "", JSONObject.NULL, 1).forEach { invalid ->
            val json = ConstructionJsonCodec.encodeScene(scene())
            json.getJSONArray("segments").getJSONObject(0).put("lineStyle", invalid)
            assertThrows(Exception::class.java) { ConstructionJsonCodec.decodeScene(json) }
        }
    }

    private fun wire(packet: ConstructionSyncPacket) = ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(packet))
    private fun scene() = ConstructionScene(
        points = listOf(GeometryPoint("a", 0.0, 0.0, "A"), GeometryPoint("b", 5.0, 0.0, "B")),
        segments = listOf(GeometrySegment("ab", "a", "b")),
        circles = listOf(GeometryCircle("circle", "a", 3.0)),
    )

    companion object {
        private val TARGET = ConstructionTarget("book", 0, 1, "11111111-1111-4111-8111-111111111111")
    }
}
