package com.studyink.assistant.core

import com.studyink.core.model.PageBounds
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object AssistantJsonCodec {
    private const val PROMPT_FORMAT_VERSION = 1
    private const val TEACHER_PAGE_FORMAT_VERSION = 1
    private const val STUDENT_LAYER_FORMAT_VERSION = 1
    private const val PENDING_PUBLICATIONS_FORMAT_VERSION = 1

    fun encodePrompts(slots: List<AssistantPromptSlot>): ByteArray = JSONObject()
        .put("formatVersion", PROMPT_FORMAT_VERSION)
        .put("slots", JSONArray().apply { slots.forEach { put(promptToJson(it)) } })
        .utf8()

    fun decodePrompts(bytes: ByteArray, limits: AssistantStorageLimits): List<AssistantPromptSlot> {
        val root = parseObject(bytes)
        require(root.getInt("formatVersion") == PROMPT_FORMAT_VERSION)
        val values = root.getJSONArray("slots")
        require(values.length() == DefaultAssistantPrompts.SLOT_COUNT) {
            "Prompt storage must contain exactly four slots"
        }
        val result = (0 until values.length()).map { index ->
            values.getJSONObject(index).let { value ->
                AssistantPromptSlot(
                    slotNumber = value.getInt("slotNumber"),
                    title = value.getString("title"),
                    body = value.getString("body"),
                    revision = value.getLong("revision"),
                    updatedAtEpochMillis = value.getLong("updatedAtEpochMillis"),
                )
            }
        }
        require(result.map(AssistantPromptSlot::slotNumber) == (1..DefaultAssistantPrompts.SLOT_COUNT).toList()) {
            "Prompt slots are missing, duplicated, or reordered"
        }
        result.forEach { AssistantValidation.prompt(it, limits) }
        return result
    }

    fun encodeTeacherPage(
        page: AssistantPageKey,
        resources: List<TeacherGptResource>,
    ): ByteArray = JSONObject()
        .put("formatVersion", TEACHER_PAGE_FORMAT_VERSION)
        .put("bookId", page.bookId)
        .put("pageNumber", page.pageNumber)
        .put("resources", JSONArray().apply { resources.forEach { put(resourceToJson(it)) } })
        .utf8()

    fun decodeTeacherPage(
        bytes: ByteArray,
        expectedPage: AssistantPageKey,
        limits: AssistantStorageLimits,
    ): List<TeacherGptResource> {
        val root = parseObject(bytes)
        require(root.getInt("formatVersion") == TEACHER_PAGE_FORMAT_VERSION)
        val page = AssistantPageKey(root.getString("bookId"), root.getInt("pageNumber"))
        require(page == expectedPage) { "Teacher GPT page file identity mismatch" }
        val values = root.getJSONArray("resources")
        require(values.length() <= limits.maxTeacherResourcesPerPage)
        val resources = (0 until values.length()).map { index ->
            resourceFromJson(values.getJSONObject(index), page, limits)
        }
        AssistantValidation.teacherPage(page, resources, limits)
        return resources
    }

    fun encodeStudentLayer(layer: StudentExplanationLayer): ByteArray = JSONObject()
        .put("formatVersion", STUDENT_LAYER_FORMAT_VERSION)
        .put("bookId", layer.target.page.bookId)
        .put("pageNumber", layer.target.page.pageNumber)
        .put("attemptNo", layer.target.attemptNo)
        .put("revision", layer.revision)
        .put("digestSha256", layer.digestSha256)
        .put("authorityEpoch", layer.authorityEpoch)
        .put(
            "retiredAuthorityEpochs",
            JSONArray().apply { layer.retiredAuthorityEpochs.sorted().forEach(::put) },
        )
        .put("cards", JSONArray().apply { layer.cards.forEach { put(cardToJson(it)) } })
        .utf8()

    fun decodeStudentLayer(
        bytes: ByteArray,
        limits: AssistantStorageLimits,
    ): StudentExplanationLayer {
        val root = parseObject(bytes)
        require(root.getInt("formatVersion") == STUDENT_LAYER_FORMAT_VERSION)
        val target = StudentExplanationTarget(
            page = AssistantPageKey(root.getString("bookId"), root.getInt("pageNumber")),
            attemptNo = root.getInt("attemptNo"),
        )
        val values = root.getJSONArray("cards")
        require(values.length() <= limits.maxCardsPerLayer)
        val retiredValues = root.optJSONArray("retiredAuthorityEpochs") ?: JSONArray()
        require(retiredValues.length() <= AssistantValidation.MAX_RETIRED_AUTHORITY_EPOCHS)
        val layer = StudentExplanationLayer(
            target = target,
            revision = root.getLong("revision"),
            digestSha256 = root.getString("digestSha256"),
            cards = (0 until values.length()).map { cardFromJson(values.getJSONObject(it)) },
            authorityEpoch = root.optString("authorityEpoch", LOCAL_EXPLANATION_AUTHORITY),
            retiredAuthorityEpochs = (0 until retiredValues.length())
                .map { retiredValues.getString(it) }
                .toSet(),
        )
        AssistantValidation.studentLayer(layer, limits)
        return layer
    }

    fun encodePendingPublications(values: List<PendingStudentExplanationPublication>): ByteArray =
        JSONObject()
            .put("formatVersion", PENDING_PUBLICATIONS_FORMAT_VERSION)
            .put("publications", JSONArray().apply { values.forEach { put(pendingToJson(it)) } })
            .utf8()

    fun decodePendingPublications(
        bytes: ByteArray,
        limits: AssistantStorageLimits,
    ): List<PendingStudentExplanationPublication> {
        val root = parseObject(bytes)
        require(root.getInt("formatVersion") == PENDING_PUBLICATIONS_FORMAT_VERSION)
        val array = root.getJSONArray("publications")
        require(array.length() <= limits.maxPendingPublications)
        val values = (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            PendingStudentExplanationPublication(
                publicationId = value.getString("publicationId"),
                target = StudentExplanationTarget(
                    AssistantPageKey(value.getString("bookId"), value.getInt("pageNumber")),
                    value.getInt("attemptNo"),
                ),
                revision = value.getLong("revision"),
                digestSha256 = value.getString("digestSha256"),
                queuedAtEpochMillis = value.getLong("queuedAtEpochMillis"),
                deliveryAttempt = value.optLong("deliveryAttempt", 0L),
                resolvedAtEpochMillis = if (!value.has("resolvedAtEpochMillis") ||
                    value.isNull("resolvedAtEpochMillis")
                ) {
                    null
                } else {
                    value.getLong("resolvedAtEpochMillis")
                },
            ).also(AssistantValidation::pendingPublication)
        }
        require(values.map(PendingStudentExplanationPublication::publicationId).distinct().size == values.size)
        require(values.map(PendingStudentExplanationPublication::target).distinct().size == values.size)
        return values
    }

    private fun pendingToJson(value: PendingStudentExplanationPublication): JSONObject = JSONObject()
        .put("publicationId", value.publicationId)
        .put("bookId", value.target.page.bookId)
        .put("pageNumber", value.target.page.pageNumber)
        .put("attemptNo", value.target.attemptNo)
        .put("revision", value.revision)
        .put("digestSha256", value.digestSha256)
        .put("queuedAtEpochMillis", value.queuedAtEpochMillis)
        .put("deliveryAttempt", value.deliveryAttempt)
        .put("resolvedAtEpochMillis", value.resolvedAtEpochMillis ?: JSONObject.NULL)

    private fun promptToJson(value: AssistantPromptSlot): JSONObject = JSONObject()
        .put("slotNumber", value.slotNumber)
        .put("title", value.title)
        .put("body", value.body)
        .put("revision", value.revision)
        .put("updatedAtEpochMillis", value.updatedAtEpochMillis)

    private fun resourceToJson(value: TeacherGptResource): JSONObject = JSONObject()
        .put("resourceId", value.resourceId)
        .put("title", value.title)
        .put("createdAtEpochMillis", value.createdAtEpochMillis)
        .put("currentRevisionId", value.currentRevisionId)
        .put("revisions", JSONArray().apply { value.revisions.forEach { put(revisionToJson(it)) } })

    private fun revisionToJson(value: TeacherGptResourceRevision): JSONObject = JSONObject()
        .put("revisionId", value.revisionId)
        .put("revisionNumber", value.revisionNumber)
        .put("promptSlotNumber", value.promptSlotNumber)
        .put("promptTitle", value.promptTitle)
        .put("promptBody", value.promptBody)
        .put("selectionBounds", boundsToJson(value.selectionBounds))
        .put("answerText", value.answerText)
        .put("answerHtml", value.answerHtml ?: JSONObject.NULL)
        .put("providerName", value.providerName ?: JSONObject.NULL)
        .put("createdAtEpochMillis", value.createdAtEpochMillis)

    private fun resourceFromJson(
        value: JSONObject,
        page: AssistantPageKey,
        limits: AssistantStorageLimits,
    ): TeacherGptResource {
        val revisionsJson = value.getJSONArray("revisions")
        require(revisionsJson.length() in 1..limits.maxRevisionsPerResource)
        return TeacherGptResource(
            resourceId = value.getString("resourceId"),
            page = page,
            title = value.getString("title"),
            createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
            currentRevisionId = value.getString("currentRevisionId"),
            revisions = (0 until revisionsJson.length()).map { index ->
                revisionFromJson(revisionsJson.getJSONObject(index))
            },
        )
    }

    private fun revisionFromJson(value: JSONObject): TeacherGptResourceRevision =
        TeacherGptResourceRevision(
            revisionId = value.getString("revisionId"),
            revisionNumber = value.getLong("revisionNumber"),
            promptSlotNumber = value.getInt("promptSlotNumber"),
            promptTitle = value.getString("promptTitle"),
            promptBody = value.getString("promptBody"),
            selectionBounds = boundsFromJson(value.getJSONObject("selectionBounds")),
            answerText = value.getString("answerText"),
            answerHtml = value.nullableString("answerHtml"),
            providerName = value.nullableString("providerName"),
            createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
        )

    private fun cardToJson(value: StudentExplanationCard): JSONObject = JSONObject()
        .put("cardId", value.cardId)
        .put("sourceResourceId", value.sourceResourceId)
        .put("sourceResourceRevisionId", value.sourceResourceRevisionId)
        .put("title", value.title)
        .put("text", value.text)
        .put("anchorBounds", boundsToJson(value.anchorBounds))
        .put("createdAtEpochMillis", value.createdAtEpochMillis)
        .put("updatedAtEpochMillis", value.updatedAtEpochMillis)

    private fun cardFromJson(value: JSONObject): StudentExplanationCard = StudentExplanationCard(
        cardId = value.getString("cardId"),
        sourceResourceId = value.getString("sourceResourceId"),
        sourceResourceRevisionId = value.getString("sourceResourceRevisionId"),
        title = value.getString("title"),
        text = value.getString("text"),
        anchorBounds = boundsFromJson(value.getJSONObject("anchorBounds")),
        createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
        updatedAtEpochMillis = value.getLong("updatedAtEpochMillis"),
    )

    private fun boundsToJson(value: PageBounds): JSONObject = JSONObject()
        .put("left", value.left.toDouble())
        .put("top", value.top.toDouble())
        .put("right", value.right.toDouble())
        .put("bottom", value.bottom.toDouble())

    private fun boundsFromJson(value: JSONObject): PageBounds = PageBounds(
        left = value.getDouble("left").toFloat(),
        top = value.getDouble("top").toFloat(),
        right = value.getDouble("right").toFloat(),
        bottom = value.getDouble("bottom").toFloat(),
    )

    private fun parseObject(bytes: ByteArray): JSONObject {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        val tokener = JSONTokener(text)
        val value = tokener.nextValue()
        require(value is JSONObject) { "Assistant storage root is not an object" }
        require(tokener.nextClean() == '\u0000') { "Trailing content in assistant storage" }
        return value
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun JSONObject.utf8(): ByteArray = toString().toByteArray(StandardCharsets.UTF_8)
}
