package com.studyink.construction.storage

import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.SceneValidator
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

internal data class StoredConstructionDocument(
    val target: ConstructionTarget,
    val revision: Long,
    val commitId: String,
    val scene: ConstructionScene,
)

/** A separate, explicitly versioned envelope; no existing annotation or memo bytes are rewritten. */
internal object ConstructionJsonCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val SCHEMA_VERSION = 1
    private const val MAX_LABEL_LENGTH = 256

    fun encode(document: StoredConstructionDocument): ByteArray {
        require(document.revision > 0)
        require(UUID.fromString(document.commitId).toString() == document.commitId)
        val scene = immutableScene(document.scene)
        val payload = encodeScene(scene).toString()
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("target", encodeTarget(document.target))
            .put("revision", document.revision)
            .put("commitId", document.commitId)
            // Keep the exact payload string: JSON object key order differs between Android/JVM.
            .put("sceneJson", payload)
            .put("sceneSha256", sha256(payload.toByteArray(Charsets.UTF_8)))
            .toString().toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_BYTES) { "Construction document is too large" } }
    }

    fun decode(bytes: ByteArray): StoredConstructionDocument {
        require(bytes.size <= MAX_BYTES) { "Construction document is too large" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.exactLong("schemaVersion") == SCHEMA_VERSION.toLong()) {
            "Unsupported construction document version"
        }
        val revision = root.exactLong("revision")
        require(revision > 0)
        val commitId = root.getString("commitId")
        require(UUID.fromString(commitId).toString() == commitId)
        val targetJson = root.getJSONObject("target")
        val target = ConstructionTarget(
            targetJson.getString("bookId"),
            targetJson.exactInt("pageNumber"),
            targetJson.exactInt("attemptNo"),
            targetJson.getString("memoId"),
            targetJson.getString("ownerScope"),
        )
        val payload = root.getString("sceneJson")
        require(sha256(payload.toByteArray(Charsets.UTF_8)) == root.getString("sceneSha256")) {
            "Construction document checksum mismatch"
        }
        return StoredConstructionDocument(target, revision, commitId, decodeScene(JSONObject(payload)))
    }

    fun immutableScene(scene: ConstructionScene): ConstructionScene {
        val result = scene.copy(
            points = Collections.unmodifiableList(scene.points.toList()),
            segments = Collections.unmodifiableList(scene.segments.toList()),
            circles = Collections.unmodifiableList(scene.circles.toList()),
            constraints = Collections.unmodifiableList(scene.constraints.map {
                it.copy(entityIds = Collections.unmodifiableList(it.entityIds.toList()))
            }),
        )
        val issues = SceneValidator.validate(result)
        require(issues.isEmpty()) { issues.joinToString(" ") }
        require((result.points.map { it.label } + result.segments.map { it.label } +
            result.circles.map { it.label }).all { it.length <= MAX_LABEL_LENGTH }) {
            "Construction label is too long"
        }
        result.constraints.forEach {
            require(listOfNotNull(it.value, it.targetX, it.targetY).all(Double::isFinite)) {
                "Non-finite construction condition value"
            }
        }
        return result
    }

    private fun encodeTarget(target: ConstructionTarget) = JSONObject()
        .put("bookId", target.bookId).put("pageNumber", target.pageNumber)
        .put("attemptNo", target.attemptNo).put("memoId", target.memoId)
        .put("ownerScope", target.ownerScope)

    private fun encodeScene(scene: ConstructionScene) = JSONObject()
        .put("points", JSONArray(scene.points.map {
            JSONObject().put("id", it.id).put("x", it.x).put("y", it.y).put("label", it.label)
        }))
        .put("segments", JSONArray(scene.segments.map {
            JSONObject().put("id", it.id).put("startPointId", it.startPointId)
                .put("endPointId", it.endPointId).put("label", it.label)
        }))
        .put("circles", JSONArray(scene.circles.map {
            JSONObject().put("id", it.id).put("centerPointId", it.centerPointId)
                .put("radius", it.radius).put("label", it.label)
        }))
        .put("constraints", JSONArray(scene.constraints.map {
            JSONObject().put("id", it.id).put("type", it.type.name)
                .put("entityIds", JSONArray(it.entityIds)).put("enabled", it.enabled)
                .put("value", it.value ?: JSONObject.NULL)
                .put("targetX", it.targetX ?: JSONObject.NULL)
                .put("targetY", it.targetY ?: JSONObject.NULL)
        }))

    private fun decodeScene(json: JSONObject): ConstructionScene = immutableScene(ConstructionScene(
        points = json.boundedArray("points", SceneValidator.MAX_POINTS).objects().map {
            GeometryPoint(it.getString("id"), it.getDouble("x"), it.getDouble("y"), it.getString("label"))
        },
        segments = json.boundedArray("segments", SceneValidator.MAX_ENTITIES).objects().map {
            GeometrySegment(it.getString("id"), it.getString("startPointId"), it.getString("endPointId"), it.getString("label"))
        },
        circles = json.boundedArray("circles", SceneValidator.MAX_ENTITIES).objects().map {
            GeometryCircle(it.getString("id"), it.getString("centerPointId"), it.getDouble("radius"), it.getString("label"))
        },
        constraints = json.boundedArray("constraints", SceneValidator.MAX_CONSTRAINTS).objects().map {
            val refs = it.boundedArray("entityIds", 2)
            GeometryConstraint(
                it.getString("id"), ConstraintType.valueOf(it.getString("type")),
                (0 until refs.length()).map(refs::getString),
                it.nullableDouble("value"), it.nullableDouble("targetX"), it.nullableDouble("targetY"),
                it.getBoolean("enabled"),
            )
        },
    ))

    private fun JSONObject.boundedArray(key: String, maximum: Int): JSONArray =
        getJSONArray(key).also { require(it.length() <= maximum) { "Too many $key" } }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONObject.nullableDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.exactLong(key: String): Long {
        val value = get(key)
        require(value is Number) { "Invalid $key" }
        return requireNotNull(value.toString().toLongOrNull()) { "Invalid $key" }
    }
    private fun JSONObject.exactInt(key: String): Int = exactLong(key).also {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    }.toInt()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
