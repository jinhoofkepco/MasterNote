package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryPoint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConstructionLargeMemoStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `ten MiB parent fits the larger envelope and packed or large bodies refuse old format tags`() {
        val store = ConstructionReplicaStore(temporary.newFolder())
        val sceneOnly = store.studentSnapshot(TARGET)
        val oldEnvelope = JSONObject(ConstructionSyncCodec.encode(sceneOnly).toString(Charsets.UTF_8))
        assertEquals(1, oldEnvelope.getInt("formatVersion"))
        assertEquals(sceneOnly, ConstructionSyncCodec.decode(oldEnvelope.toString().toByteArray()))
        assertEquals(sceneOnly, ConstructionSyncCodec.decode(oldEnvelope.put("formatVersion", 2).toString().toByteArray()))
        val legacy = store.studentSnapshot(TARGET, memoStateKnown = true, memoJson = "{\"formatVersion\":2}")
        assertEquals(2, JSONObject(ConstructionSyncCodec.encode(legacy).toString(Charsets.UTF_8)).getInt("formatVersion"))
        assertEquals(legacy, ConstructionSyncCodec.decode(ConstructionSyncCodec.encode(legacy)))
        val packed = legacy.copy(student = legacy.student!!.copy(memoJson = "{\"formatVersion\":3}"))
        val maximum = packed.copy(student = packed.student!!.copy(memoJson = "x".repeat(ConstructionSyncCodec.MAX_MEMO_BYTES)))
        for (packet in listOf(packed, maximum)) {
            val bytes = ConstructionSyncCodec.encode(packet)
            assertTrue(bytes.size <= ConstructionSyncCodec.MAX_PACKET_BYTES)
            assertEquals(packet, ConstructionSyncCodec.decode(bytes))
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            assertEquals(3, json.getInt("formatVersion"))
            assertThrows(IllegalArgumentException::class.java) {
                ConstructionSyncCodec.decode(json.put("formatVersion", 2).toString().toByteArray())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionSyncCodec.encode(maximum.copy(student = maximum.student!!.copy(memoJson = "x".repeat(ConstructionSyncCodec.MAX_MEMO_BYTES + 1))))
        }
    }

    @Test fun `large applied parent is stored once across shadows bases and receipts and oldest receipt replays exactly`() {
        val studentRoot = temporary.newFolder("student")
        val teacherRoot = temporary.newFolder("teacher")
        val student = ConstructionReplicaStore(studentRoot)
        val teacher = ConstructionReplicaStore(teacherRoot)
        val memo = "{\"formatVersion\":3,\"packed\":\"" + "a".repeat(2 * 1024 * 1024) + "\"}"
        val requests = mutableListOf<ConstructionSyncPacket>()
        val responses = mutableListOf<ConstructionSyncPacket>()
        repeat(3) { index ->
            teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET, memoStateKnown = true, memoJson = memo))
            val draft = teacher.saveLocal(teacher.load(TARGET, TEACHER), scene(index + 1.0))
            val pending = teacher.preparePublish(draft, memoJson = memo).packet!!
            assertEquals(pending, ConstructionReplicaStore(teacherRoot).load(TARGET, TEACHER).pendingPublish)
            val response = student.receivePublish(TARGET, pending) { ConstructionMemoApplyResult(true, memo) }
            assertEquals(ConstructionPublishResult.APPLIED, response.result)
            requests += pending; responses += response
            teacher.receiveResult(TARGET, response)
        }
        val file = student.fileForTest(TARGET, STUDENT)
        val envelope = JSONObject(file.readText())
        assertEquals(2, envelope.getInt("formatVersion"))
        val body = JSONObject(envelope.getString("body"))
        assertEquals(1, body.getJSONObject("memoBodies").length())
        assertEquals(3, body.getJSONArray("receipts").length())
        assertTrue("One body, not a full copy per shadow/base/receipt", file.length() < memo.toByteArray().size + 100_000L)
        student.saveLocal(student.load(TARGET, STUDENT), scene(77.0))
        val reopened = ConstructionReplicaStore(studentRoot)
        assertEquals(responses.first(), reopened.receivePublish(TARGET, requests.first()) {
            fail("The durable receipt must replay without running the parent write again")
            ConstructionMemoApplyResult(true, "wrong")
        })
        assertEquals(scene(77.0), reopened.load(TARGET, STUDENT).scene)
    }

    @Test fun `inline legacy replicas still load while corrupt memo references preserve original bytes`() {
        val student = ConstructionReplicaStore(temporary.newFolder("student"))
        val root = temporary.newFolder("teacher")
        val teacher = ConstructionReplicaStore(root)
        val memo = " {\n \"message\": \"exact legacy text\"\n} "
        val snapshot = teacher.receiveStudentSnapshot(TARGET, student.studentSnapshot(TARGET, memoStateKnown = true, memoJson = memo))
        val file = teacher.fileForTest(TARGET, TEACHER)
        val modern = file.readBytes()
        val body = JSONObject(JSONObject(modern.toString(Charsets.UTF_8)).getString("body"))
        val bodies = body.getJSONObject("memoBodies")
        body.remove("memoBodies")
        fun inline(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.has("memoBodySha256")) {
                        value.put("memoJson", bodies.getString(value.getString("memoBodySha256")))
                        value.remove("memoBodySha256")
                    }
                    value.keys().asSequence().toList().forEach { inline(value.get(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { inline(value.get(it)) }
            }
        }
        inline(body)
        file.writeBytes(envelope(body, 1))
        assertEquals(snapshot.studentShadow, ConstructionReplicaStore(root).load(TARGET, TEACHER).studentShadow)
        val corrupt = JSONObject(JSONObject(modern.toString(Charsets.UTF_8)).getString("body"))
        val table = corrupt.getJSONObject("memoBodies")
        table.put(table.keys().next(), "changed body")
        val bad = envelope(corrupt, 2)
        file.writeBytes(bad)
        assertThrows(ConstructionDataException::class.java) { ConstructionReplicaStore(root).load(TARGET, TEACHER) }
        assertArrayEquals(bad, file.readBytes())
    }

    private fun envelope(body: JSONObject, version: Int): ByteArray {
        val text = body.toString()
        return JSONObject().put("formatVersion", version).put("body", text)
            .put("sha256", ConstructionSyncCodec.sha256(text.toByteArray())).toString().toByteArray()
    }
    private fun scene(x: Double) = ConstructionScene(points = listOf(GeometryPoint("A", x, 0.0)))
    private companion object {
        val STUDENT = ConstructionReplicaRole.STUDENT
        val TEACHER = ConstructionReplicaRole.TEACHER
        val TARGET = ConstructionTarget("local-book", 0, 1, "11111111-1111-4111-8111-111111111111")
    }
}
