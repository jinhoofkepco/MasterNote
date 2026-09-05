package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID

/** Transport-independent, bounded versioned wire envelope. Payload identities are not credentials. */
object ConstructionSyncCodec {
    const val MAX_PACKET_BYTES = 4 * 1024 * 1024

    fun encode(packet: ConstructionSyncPacket): ByteArray {
        validate(packet)
        return toJson(packet).toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_PACKET_BYTES) { "Construction sync packet is too large" }
        }
    }

    fun decode(bytes: ByteArray): ConstructionSyncPacket {
        require(bytes.size <= MAX_PACKET_BYTES) { "Construction sync packet is too large" }
        return fromJson(JSONObject(bytes.toString(Charsets.UTF_8))).also(::validate)
    }

    fun sceneDigest(scene: ConstructionScene, deleted: Boolean = false, attached: Boolean = true): String {
        val frozen = ConstructionJsonCodec.immutableScene(scene)
        val json = JSONObject().put("scene", ConstructionJsonCodec.encodeScene(frozen))
            .put("deleted", deleted).put("attached", attached)
        return sha256(canonical(json).toByteArray(Charsets.UTF_8))
    }

    internal fun packetDigest(packet: ConstructionSyncPacket): String =
        sha256(canonical(toJson(packet)).toByteArray(Charsets.UTF_8))

    internal fun toJson(packet: ConstructionSyncPacket): JSONObject = JSONObject()
        .put("formatVersion", 1).put("kind", packet.kind.name).put("requestId", packet.requestId)
        .put("memoId", packet.memoId).put("pageNumber", packet.pageNumber).put("attemptNo", packet.attemptNo)
        .put("student", packet.student?.let(::remoteJson) ?: JSONObject.NULL)
        .put("expectedStudent", packet.expectedStudent?.let(::versionJson) ?: JSONObject.NULL)
        .put("scene", packet.scene?.let(ConstructionJsonCodec::encodeScene) ?: JSONObject.NULL)
        .put("result", packet.result?.name ?: JSONObject.NULL)

    internal fun fromJson(json: JSONObject): ConstructionSyncPacket {
        require(json.exactLong("formatVersion") == 1L) { "Unsupported construction sync format" }
        return ConstructionSyncPacket(
            ConstructionPacketKind.valueOf(json.getString("kind")), json.getString("requestId"),
            json.getString("memoId"), json.exactInt("pageNumber"), json.exactInt("attemptNo"),
            if (json.isNull("student")) null else remote(json.getJSONObject("student")),
            if (json.isNull("expectedStudent")) null else version(json.getJSONObject("expectedStudent")),
            if (json.isNull("scene")) null else ConstructionJsonCodec.decodeScene(json.getJSONObject("scene")),
            if (json.isNull("result")) null else ConstructionPublishResult.valueOf(json.getString("result")),
        ).also(::validate)
    }

    internal fun remoteJson(remote: ConstructionRemoteScene): JSONObject = JSONObject()
        .put("version", versionJson(remote.version)).put("deleted", remote.deleted).put("attached", remote.attached)
        .put("scene", ConstructionJsonCodec.encodeScene(remote.scene))

    internal fun remote(json: JSONObject): ConstructionRemoteScene = ConstructionRemoteScene(
        version(json.getJSONObject("version")), ConstructionJsonCodec.decodeScene(json.getJSONObject("scene")),
        json.getBoolean("deleted"), json.getBoolean("attached"),
    ).also(::validateRemote)

    internal fun versionJson(value: ConstructionVersion) = JSONObject()
        .put("generation", value.generation).put("revision", value.revision).put("digestSha256", value.digestSha256)

    internal fun version(json: JSONObject) = ConstructionVersion(
        json.exactLong("generation"), json.exactLong("revision"), json.getString("digestSha256"),
    )

    internal fun validateRemote(remote: ConstructionRemoteScene) {
        require(!remote.deleted || !remote.attached && remote.scene == ConstructionScene()) { "Deleted geometry must be empty" }
        require(remote.attached || remote.scene == ConstructionScene()) { "Detached geometry must be empty" }
        require(remote.version.digestSha256 == sceneDigest(remote.scene, remote.deleted, remote.attached)) {
            "Construction sync scene checksum mismatch"
        }
    }

    private fun validate(packet: ConstructionSyncPacket) {
        requireUuid(packet.requestId); requireUuid(packet.memoId)
        require(packet.pageNumber in 0..1_000_000 && packet.attemptNo in 0..1_000_000)
        packet.student?.let(::validateRemote)
        packet.scene?.let(ConstructionJsonCodec::immutableScene)
        when (packet.kind) {
            ConstructionPacketKind.REQUEST_STATE -> require(packet.student == null && packet.expectedStudent == null && packet.scene == null && packet.result == null)
            ConstructionPacketKind.STUDENT_SNAPSHOT -> require(packet.student != null && packet.expectedStudent == null && packet.scene == null && packet.result == null)
            ConstructionPacketKind.PUBLISH -> require(packet.student == null && packet.expectedStudent != null && packet.scene != null && packet.result == null)
            ConstructionPacketKind.RESULT -> require(packet.student != null && packet.expectedStudent == null && packet.scene == null && packet.result != null)
        }
    }

    internal fun requireUuid(value: String) {
        require(value.length == 36 && UUID.fromString(value).toString() == value.lowercase()) { "Invalid UUID" }
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    // JSONObject ordering differs on Android and the host JVM. Canonicalize keys and numeric spelling.
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is Boolean -> value.toString()
        is String -> JSONObject.quote(value)
        else -> error("Unsupported JSON value")
    }
}

internal fun JSONObject.exactLong(key: String): Long {
    val value = get(key)
    require(value is Number) { "Invalid $key" }
    return requireNotNull(value.toString().toLongOrNull()) { "Invalid $key" }
}

internal fun JSONObject.exactInt(key: String): Int = exactLong(key).also {
    require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
}.toInt()
