package com.studyink.app

import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.storage.ConstructionPacketKind
import com.studyink.construction.storage.ConstructionSyncCodec
import com.studyink.construction.storage.ConstructionSyncPacket
import com.studyink.construction.storage.ConstructionVersion
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ConstructionInboxBoundaryTest {
    @Test fun unknownFutureRelationNeverCallsApplyOrAcknowledgesTheInbox() {
        val json = JSONObject(ConstructionSyncCodec.encode(packet()).toString(Charsets.UTF_8))
        json.getJSONObject("scene").getJSONArray("constraints").getJSONObject(0).put("type", "FUTURE_RELATION")
        var applied = false
        var acknowledged = false
        assertThrows(IllegalArgumentException::class.java) {
            constructionApplyBeforeAcknowledging(json.toString().toByteArray(),
                apply = { applied = true; true }, acknowledge = { acknowledged = true })
        }
        assertFalse(applied)
        assertFalse(acknowledged)
    }

    @Test fun malformedRationalOptionsNeverWeakenTheRelationOrAcknowledgeIt() {
        val json = JSONObject(ConstructionSyncCodec.encode(packet()).toString(Charsets.UTF_8))
        json.getJSONObject("scene").getJSONArray("constraints").getJSONObject(0).put("denominator", 0)
        var acknowledged = false
        assertThrows(IllegalArgumentException::class.java) {
            constructionApplyBeforeAcknowledging(json.toString().toByteArray(),
                apply = { fail("Invalid fraction reached apply"); true }, acknowledge = { acknowledged = true })
        }
        assertFalse(acknowledged)
    }

    @Test fun missingParentOrChangedAuthenticatedRouteRetainsCompletePacket() {
        var acknowledged = false
        assertFalse(constructionApplyBeforeAcknowledging(ConstructionSyncCodec.encode(packet()),
            apply = { false }, acknowledge = { acknowledged = true }))
        assertFalse(acknowledged)
    }

    @Test fun durableSaveExceptionCannotProduceTransportAcknowledgement() {
        var acknowledged = false
        assertThrows(IOException::class.java) {
            constructionApplyBeforeAcknowledging(ConstructionSyncCodec.encode(packet()),
                apply = { throw IOException("Storage unavailable") }, acknowledge = { acknowledged = true })
        }
        assertFalse(acknowledged)
    }

    @Test fun successfulApplicationPrecedesTransportAcknowledgementAndKeepsExactFraction() {
        val events = mutableListOf<String>()
        val original = packet()
        assertTrue(constructionApplyBeforeAcknowledging(ConstructionSyncCodec.encode(original), apply = {
            assertEquals(original, it)
            assertEquals(1, it.scene!!.constraints.single().numerator)
            assertEquals(3, it.scene!!.constraints.single().denominator)
            events += "durable-apply"
            true
        }, acknowledge = {
            assertEquals(listOf("durable-apply"), events)
            events += "transport-ack"
        }))
        assertEquals(listOf("durable-apply", "transport-ack"), events)
    }

    private fun packet(): ConstructionSyncPacket {
        val scene = ConstructionScene(
            points = listOf(GeometryPoint("a", 0.0, 0.0), GeometryPoint("b", 12.0, 0.0), GeometryPoint("p", 4.0, 0.0)),
            segments = listOf(GeometrySegment("ab", "a", "b")),
            constraints = listOf(GeometryConstraint("third", ConstraintType.POINT_FRACTION, listOf("p", "ab"), numerator = 1, denominator = 3)),
        )
        return ConstructionSyncPacket(ConstructionPacketKind.PUBLISH,
            "22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111", 0, 1,
            expectedStudent = ConstructionVersion(1, 1, ConstructionSyncCodec.sceneDigest(ConstructionScene())), scene = scene)
    }
}
