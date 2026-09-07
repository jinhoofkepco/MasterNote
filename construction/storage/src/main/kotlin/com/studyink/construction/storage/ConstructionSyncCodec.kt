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
    const val MAX_MEMO_BYTES = 1600 * 1024

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
        .put("formatVersion", if (usesMemoExtension(packet)) 2 else 1).put("kind", packet.kind.name).put("requestId", packet.requestId)
        .put("memoId", packet.memoId).put("pageNumber", packet.pageNumber).put("attemptNo", packet.attemptNo)
        .put("student", packet.student?.let(::remoteJson) ?: JSONObject.NULL)
        .put("expectedStudent", packet.expectedStudent?.let(::versionJson) ?: JSONObject.NULL)
        .put("scene", packet.scene?.let(ConstructionJsonCodec::encodeScene) ?: JSONObject.NULL)
        .put("result", packet.result?.name ?: JSONObject.NULL)
        .also { json ->
            // Omit defaults, retaining the exact existing v1 envelope and request fingerprints.
            if (packet.includeMemo) json.put("includeMemo", true)
            packet.memoJson?.let { json.put("memoJson", it) }
            packet.expectedMemoDigest?.let { json.put("expectedMemoDigest", it) }
        }

    internal fun fromJson(json: JSONObject): ConstructionSyncPacket {
        val format = json.exactLong("formatVersion")
        require(format == 1L || format == 2L) { "Unsupported construction sync format" }
        if (format == 1L) {
            require(listOf("includeMemo", "memoJson", "expectedMemoDigest").none(json::has) &&
                (json.isNull("student") || listOf("memoStateKnown", "memoJson").none(json.getJSONObject("student")::has))) {
                "Parent memo fields require construction sync format 2"
            }
        }
        return ConstructionSyncPacket(
            ConstructionPacketKind.valueOf(json.getString("kind")), json.getString("requestId"),
            json.getString("memoId"), json.exactInt("pageNumber"), json.exactInt("attemptNo"),
            if (json.isNull("student")) null else remote(json.getJSONObject("student")),
            if (json.isNull("expectedStudent")) null else version(json.getJSONObject("expectedStudent")),
            if (json.isNull("scene")) null else ConstructionJsonCodec.decodeScene(json.getJSONObject("scene")),
            if (json.isNull("result")) null else ConstructionPublishResult.valueOf(json.getString("result")),
            optionalBoolean(json, "includeMemo"), optionalText(json, "memoJson"), optionalText(json, "expectedMemoDigest"),
        ).also {
            require((format == 2L) == usesMemoExtension(it)) { "Inconsistent construction sync format" }
            validate(it)
        }
    }

    internal fun remoteJson(remote: ConstructionRemoteScene): JSONObject = JSONObject()
        .put("version", versionJson(remote.version)).put("deleted", remote.deleted).put("attached", remote.attached)
        .put("scene", ConstructionJsonCodec.encodeScene(remote.scene))
        .also { json ->
            if (remote.memoStateKnown) json.put("memoStateKnown", true).put("memoJson", remote.memoJson ?: JSONObject.NULL)
        }

    internal fun remote(json: JSONObject): ConstructionRemoteScene = ConstructionRemoteScene(
        version(json.getJSONObject("version")), ConstructionJsonCodec.decodeScene(json.getJSONObject("scene")),
        json.getBoolean("deleted"), json.getBoolean("attached"),
        optionalBoolean(json, "memoStateKnown"), optionalText(json, "memoJson"),
    ).also(::validateRemote)

    internal fun versionJson(value: ConstructionVersion) = JSONObject()
        .put("generation", value.generation).put("revision", value.revision).put("digestSha256", value.digestSha256)

    internal fun version(json: JSONObject) = ConstructionVersion(
        json.exactLong("generation"), json.exactLong("revision"), json.getString("digestSha256"),
    )

    internal fun validateRemote(remote: ConstructionRemoteScene) {
        require(remote.memoStateKnown || remote.memoJson == null) { "Parent memo content requires a known state" }
        remote.memoJson?.let(::validateMemoText)
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
        packet.memoJson?.let(::validateMemoText)
        require(!packet.includeMemo || packet.kind == ConstructionPacketKind.REQUEST_STATE)
        require(packet.memoJson == null || packet.kind == ConstructionPacketKind.PUBLISH)
        packet.expectedMemoDigest?.let {
            require(packet.kind == ConstructionPacketKind.PUBLISH && packet.memoJson != null && Regex("[0-9a-f]{64}").matches(it)) {
                "Invalid parent memo comparison digest"
            }
        }
        when (packet.kind) {
            ConstructionPacketKind.REQUEST_STATE -> require(packet.student == null && packet.expectedStudent == null && packet.scene == null && packet.result == null)
            ConstructionPacketKind.STUDENT_SNAPSHOT -> require(packet.student != null && packet.expectedStudent == null && packet.scene == null && packet.result == null)
            ConstructionPacketKind.PUBLISH -> require(packet.student == null && packet.expectedStudent != null && packet.scene != null && packet.result == null)
            ConstructionPacketKind.RESULT -> require(packet.student != null && packet.expectedStudent == null && packet.scene == null && packet.result != null)
        }
    }

    internal fun validateMemoText(value: String) {
        require(value.length <= MAX_MEMO_BYTES && value.toByteArray(Charsets.UTF_8).size <= MAX_MEMO_BYTES) {
            "Parent memo is too large"
        }
    }

    private fun usesMemoExtension(packet: ConstructionSyncPacket) = packet.includeMemo || packet.memoJson != null ||
        packet.expectedMemoDigest != null || packet.student?.memoStateKnown == true

    private fun optionalBoolean(json: JSONObject, key: String): Boolean =
        if (!json.has(key)) false else json.get(key).let { require(it is Boolean) { "Invalid $key" }; it }

    private fun optionalText(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.get(key).let { require(it is String) { "Invalid $key" }; it }

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
