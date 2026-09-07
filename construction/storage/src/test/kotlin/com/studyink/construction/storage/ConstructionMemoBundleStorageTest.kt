package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryPoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ConstructionMemoBundleStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `legacy envelopes stay byte identical while known memo and pending bundle survive reopen`() {
        val student = ConstructionReplicaStore(temporary.newFolder("student"))
        val teacherRoot = temporary.newFolder("teacher")
        val teacher = ConstructionReplicaStore(teacherRoot)
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.0))
        val legacySnapshot = student.studentSnapshot(TARGET)
        val legacyPublish = ConstructionSyncPacket(ConstructionPacketKind.PUBLISH, id(), TARGET.memoId, 0, 1,
            expectedStudent = legacySnapshot.student!!.version, scene = scene(2.0))
        val legacyResult = legacySnapshot.copy(kind = ConstructionPacketKind.RESULT, result = ConstructionPublishResult.CONFLICT)
        listOf(student.requestState(TARGET), legacySnapshot, legacyPublish, legacyResult).forEach { packet ->
            assertArrayEquals(legacyJson(packet).toString().toByteArray(Charsets.UTF_8), ConstructionSyncCodec.encode(packet))
            assertEquals(packet, ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(packet)))
        }

        val query = student.requestState(TARGET, includeMemo = true)
        assertEquals(2, JSONObject(ConstructionSyncCodec.encode(query).toString(Charsets.UTF_8)).getInt("formatVersion"))
        assertEquals(query, ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(query)))
        teacher.receiveStudentSnapshot(TARGET, legacySnapshot)
        val first = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET, memoStateKnown = true, memoJson = OLD_MEMO))
        assertEquals(OLD_MEMO, first.commonBase!!.memoJson)
        val refreshed = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET, memoStateKnown = true, memoJson = NEW_MEMO))
        assertEquals(NEW_MEMO, refreshed.studentShadow!!.memoJson)
        assertEquals(OLD_MEMO, refreshed.commonBase!!.memoJson)
        assertEquals(NEW_MEMO, teacher.receiveStudentSnapshot(TARGET, legacySnapshot).studentShadow!!.memoJson)
        val absent = student.studentSnapshot(TARGET, memoStateKnown = true)
        assertTrue(ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(absent)).student!!.memoStateKnown)
        assertNull(ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(absent)).student!!.memoJson)

        val draft = teacher.saveLocal(teacher.load(TARGET, TEACHER), scene(2.0))
        val request = teacher.preparePublish(draft, memoJson = NEW_MEMO, expectedMemoDigest = digest(NEW_MEMO)).packet!!
        val bytes = ConstructionSyncCodec.encode(request)
        assertEquals(2, JSONObject(bytes.toString(Charsets.UTF_8)).getInt("formatVersion"))
        assertEquals(request, ConstructionSyncCodec.decode(bytes))
        val reopened = ConstructionReplicaStore(teacherRoot).load(TARGET, TEACHER)
        assertEquals(request, reopened.pendingPublish)
        assertEquals(NEW_MEMO, reopened.studentShadow!!.memoJson)
        assertEquals(OLD_MEMO, reopened.commonBase!!.memoJson)
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionSyncCodec.decode(JSONObject(bytes.toString(Charsets.UTF_8)).put("formatVersion", 1).toString().toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionSyncCodec.encode(request.copy(memoJson = "가".repeat(ConstructionSyncCodec.MAX_MEMO_BYTES / 3 + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionSyncCodec.encode(legacySnapshot.copy(student = legacySnapshot.student!!.copy(memoJson = OLD_MEMO)))
        }
    }

    @Test fun `parent callback rejection preserves geometry and durable retry returns original memo without reapplying`() {
        val studentRoot = temporary.newFolder("student")
        val student = ConstructionReplicaStore(studentRoot)
        val teacher = ConstructionReplicaStore(temporary.newFolder("teacher"))
        student.saveLocal(student.load(TARGET, STUDENT), scene(1.0))
        val base = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET, memoStateKnown = true, memoJson = OLD_MEMO))
        val draft = teacher.saveLocal(base, scene(2.0))
        val rejectedRequest = teacher.preparePublish(draft, memoJson = NEW_MEMO, expectedMemoDigest = digest(OLD_MEMO)).packet!!
        val prior = student.load(TARGET, STUDENT)
        var calls = 0
        val rejected = student.receivePublish(TARGET, rejectedRequest) {
            calls++
            assertEquals(prior.scene, student.load(TARGET, STUDENT).scene)
            ConstructionMemoApplyResult(false, OLD_MEMO)
        }
        assertEquals(ConstructionPublishResult.CONFLICT, rejected.result)
        assertTrue(rejected.student!!.memoStateKnown)
        assertEquals(OLD_MEMO, rejected.student!!.memoJson)
        assertEquals(prior.scene, student.load(TARGET, STUDENT).scene)
        assertEquals(prior.studentShadow!!.version, student.load(TARGET, STUDENT).studentShadow!!.version)
        assertEquals(rejected, ConstructionReplicaStore(studentRoot).receivePublish(TARGET, rejectedRequest))
        assertEquals(1, calls)

        val nextDraft = teacher.receiveResult(TARGET, rejected)
        val request = teacher.preparePublish(nextDraft, memoJson = NEW_MEMO, expectedMemoDigest = digest(OLD_MEMO)).packet!!
        val stale = request.copy(requestId = id(), expectedStudent = request.expectedStudent!!.copy(revision = request.expectedStudent!!.revision + 1))
        val conflict = student.receivePublish(TARGET, stale) {
            fail("A geometry mismatch must not invoke the parent callback")
            ConstructionMemoApplyResult(true, NEW_MEMO)
        }
        assertEquals(ConstructionPublishResult.CONFLICT, conflict.result)
        assertThrows(IllegalArgumentException::class.java) { student.receivePublish(TARGET, request) }
        val applied = student.receivePublish(TARGET, request) {
            calls++
            assertEquals(prior.scene, student.load(TARGET, STUDENT).scene)
            ConstructionMemoApplyResult(true, NEW_MEMO)
        }
        assertEquals(ConstructionPublishResult.APPLIED, applied.result)
        assertEquals(2, calls)
        val committed = ConstructionReplicaStore(studentRoot).load(TARGET, STUDENT)
        assertEquals(scene(2.0), committed.scene)
        assertEquals(NEW_MEMO, committed.studentShadow!!.memoJson)
        assertEquals(applied.student, committed.commonBase)

        val geometryOnlyAck = applied.copy(student = applied.student!!.copy(memoStateKnown = false, memoJson = null))
        assertThrows(IllegalArgumentException::class.java) { teacher.receiveResult(TARGET, geometryOnlyAck) }
        assertNotNull(teacher.load(TARGET, TEACHER).pendingPublish)
        student.saveLocal(student.load(TARGET, STUDENT), scene(3.0))
        val retried = ConstructionReplicaStore(studentRoot).receivePublish(TARGET, request) {
            fail("A durable receipt must be checked before the callback")
            ConstructionMemoApplyResult(true, OLD_MEMO)
        }
        assertEquals(applied, retried)
        assertEquals(NEW_MEMO, retried.student!!.memoJson)
        assertEquals(scene(3.0), student.load(TARGET, STUDENT).scene)
        val done = teacher.receiveResult(TARGET, retried)
        assertNull(done.pendingPublish)
        assertEquals(applied.student, done.commonBase)
        assertEquals(NEW_MEMO, done.commonBase!!.memoJson)
    }

    /** Pre-extension writer: keep the old field set and insertion order as the byte fixture. */
    private fun legacyJson(packet: ConstructionSyncPacket) = JSONObject()
        .put("formatVersion", 1).put("kind", packet.kind.name).put("requestId", packet.requestId)
        .put("memoId", packet.memoId).put("pageNumber", packet.pageNumber).put("attemptNo", packet.attemptNo)
        .put("student", packet.student?.let { remote -> JSONObject()
            .put("version", ConstructionSyncCodec.versionJson(remote.version)).put("deleted", remote.deleted).put("attached", remote.attached)
            .put("scene", ConstructionJsonCodec.encodeScene(remote.scene)) } ?: JSONObject.NULL)
        .put("expectedStudent", packet.expectedStudent?.let(ConstructionSyncCodec::versionJson) ?: JSONObject.NULL)
        .put("scene", packet.scene?.let(ConstructionJsonCodec::encodeScene) ?: JSONObject.NULL)
        .put("result", packet.result?.name ?: JSONObject.NULL)

    private fun scene(x: Double) = ConstructionScene(points = listOf(GeometryPoint("A", x, 0.0, "A")))
    private fun id() = UUID.randomUUID().toString()
    private fun digest(value: String) = ConstructionSyncCodec.sha256(value.toByteArray(Charsets.UTF_8))

    companion object {
        private val STUDENT = ConstructionReplicaRole.STUDENT
        private val TEACHER = ConstructionReplicaRole.TEACHER
        private val TARGET = ConstructionTarget("local-book", 0, 1, "11111111-1111-4111-8111-111111111111")
        private const val OLD_MEMO = "{\"title\":\"기존 메모\",\"strokes\":[]}"
        private const val NEW_MEMO = " {\n  \"title\": \"선생님 메모\", \"strokes\": [1, 2]\n} "
    }
}
