package com.studyink.reader

import android.content.Context
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.MasterNoteDataRootBus
import com.studyink.core.model.PagePoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val TEACHER_GRADE_DRAFT_FORMAT_VERSION = 1
private const val TEACHER_GRADE_DRAFT_FILE = "teacher-grade-drafts-v1.json"
private const val DRAFT_DEVICE_ID = "teacher-grade-draft"

data class TeacherGradeDraftTarget(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
)

/**
 * One immutable version of a pending grade mutation.
 *
 * [groupId] remains stable for UI hit-testing. [draftId] changes on every mutation, so clearing an
 * older successfully committed version can never delete a newer edit made while it was in flight.
 */
data class TeacherGradeDraft(
    val draftId: String,
    val groupId: String,
    val target: TeacherGradeDraftTarget,
    val anchor: PagePoint,
    val color: MarkColor,
    val hidden: Boolean,
    /** True for a new grade tap; false when only editing/moving an already committed mark. */
    val appendOnCommit: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun toMarkGroup(): MarkGroup {
        val hiddenAt = updatedAtEpochMillis.takeIf { hidden }
        return MarkGroup(
            id = groupId,
            bookId = target.bookId,
            pageNumber = target.pageNumber,
            anchor = anchor,
            marks = listOf(
                Mark(
                    attemptNo = target.attemptNo,
                    color = color,
                    gradedAtEpochMillis = updatedAtEpochMillis,
                    hiddenAtEpochMillis = hiddenAt,
                ),
            ),
            createdAtEpochMillis = createdAtEpochMillis,
            hiddenAtEpochMillis = hiddenAt,
            syncRevision = 0L,
            lastModifiedByDeviceId = DRAFT_DEVICE_ID,
        )
    }
}

internal data class TeacherGradeDraftLimits(
    val maxDraftsTotal: Int = 512,
    val maxDraftsPerTarget: Int = 128,
    val maxFileBytes: Int = 2 * 1024 * 1024,
) {
    init {
        require(maxDraftsTotal > 0)
        require(maxDraftsPerTarget in 1..maxDraftsTotal)
        require(maxFileBytes > 0)
    }
}

/**
 * Durable teacher-grade drafts isolated from handwriting and the library catalog.
 *
 * The only production file is `filesDir/masternote/teacher-grade-drafts-v1.json`. Every mutation
 * writes a complete bounded JSON document to a sibling temporary file, fsyncs it, then atomically
 * replaces the prior version. A malformed file is moved aside and treated as an empty draft list.
 */
class TeacherGradeDraftStore internal constructor(
    private val file: File,
    private val limits: TeacherGradeDraftLimits = TeacherGradeDraftLimits(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) {
    private val atomicFile = AtomicDraftJsonFile(file, limits.maxFileBytes)
    private var drafts: LinkedHashMap<String, TeacherGradeDraft> = loadDrafts()

    @Synchronized
    fun add(
        target: TeacherGradeDraftTarget,
        anchor: PagePoint,
        color: MarkColor,
        groupId: String? = null,
        appendOnCommit: Boolean = true,
    ): TeacherGradeDraft {
        validateTarget(target)
        validateAnchor(anchor)
        groupId?.let { validateId(it, "groupId") }
        val existing = groupId?.let { findByGroup(target, it) }
        val timestamp = existing?.let(::mutationTimestamp) ?: validNow()
        val nextDraft = if (existing == null) {
            val reservedGroupIds = if (groupId == null) emptySet() else setOf(groupId)
            val draftId = freshDraftId(reservedGroupIds)
            TeacherGradeDraft(
                draftId = draftId,
                groupId = groupId ?: freshGroupId(setOf(draftId)),
                target = target,
                anchor = anchor,
                color = color,
                hidden = false,
                appendOnCommit = appendOnCommit,
                createdAtEpochMillis = timestamp,
                updatedAtEpochMillis = timestamp,
            )
        } else {
            existing.copy(
                draftId = freshDraftId(),
                anchor = anchor,
                color = color,
                hidden = false,
                appendOnCommit = appendOnCommit,
                updatedAtEpochMillis = timestamp,
            )
        }
        return replace(existing, nextDraft)
    }

    @Synchronized
    fun changeColor(
        target: TeacherGradeDraftTarget,
        groupId: String,
        color: MarkColor,
    ): TeacherGradeDraft? = update(target, groupId) { draft, timestamp ->
        if (draft.color == color && !draft.hidden) draft else draft.copy(
            draftId = freshDraftId(),
            color = color,
            hidden = false,
            updatedAtEpochMillis = timestamp,
        )
    }

    @Synchronized
    fun move(
        target: TeacherGradeDraftTarget,
        groupId: String,
        anchor: PagePoint,
    ): TeacherGradeDraft? {
        validateAnchor(anchor)
        return update(target, groupId) { draft, timestamp ->
            if (draft.anchor == anchor) draft else draft.copy(
                draftId = freshDraftId(),
                anchor = anchor,
                updatedAtEpochMillis = timestamp,
            )
        }
    }

    /** Keeps a publishable tombstone that can replace the same committed group during UI merge. */
    @Synchronized
    fun hide(target: TeacherGradeDraftTarget, groupId: String): TeacherGradeDraft? =
        update(target, groupId) { draft, timestamp ->
            if (draft.hidden) draft else draft.copy(
                draftId = freshDraftId(),
                hidden = true,
                updatedAtEpochMillis = timestamp,
            )
        }

    /** Cancels one local draft without implying a committed hide operation. */
    @Synchronized
    fun remove(target: TeacherGradeDraftTarget, groupId: String): Boolean {
        validateTarget(target)
        validateId(groupId, "groupId")
        val existing = findByGroup(target, groupId) ?: return false
        val next = LinkedHashMap(drafts)
        next.remove(existing.draftId)
        persistAndSwap(next)
        return true
    }

    @Synchronized
    fun list(target: TeacherGradeDraftTarget): List<TeacherGradeDraft> {
        validateTarget(target)
        return drafts.values.asSequence()
            .filter { it.target == target }
            .sortedWith(compareBy(TeacherGradeDraft::createdAtEpochMillis, TeacherGradeDraft::groupId))
            .toList()
    }

    @Synchronized
    fun listAll(): List<TeacherGradeDraft> = drafts.values.sortedWith(
        compareBy<TeacherGradeDraft>(
            { it.target.bookId },
            { it.target.pageNumber },
            { it.target.attemptNo },
            TeacherGradeDraft::createdAtEpochMillis,
            TeacherGradeDraft::groupId,
        ),
    )

    /**
     * Draft groups for direct ID-keyed merge over committed groups.
     *
     * Hidden tombstones are intentionally included: existing renderers honor their hidden state,
     * while their stable [MarkGroup.id] prevents a committed version from showing through.
     */
    @Synchronized
    fun markGroups(target: TeacherGradeDraftTarget): List<MarkGroup> =
        list(target).map(TeacherGradeDraft::toMarkGroup)

    /**
     * Removes only versions whose immutable IDs were durably committed.
     *
     * If a group changed after a publisher took its snapshot, that edit has a new ID and survives.
     */
    @Synchronized
    fun clearCommittedIds(committedDraftIds: Set<String>): Int {
        if (committedDraftIds.isEmpty()) return 0
        val matching = drafts.keys.filterTo(linkedSetOf(), committedDraftIds::contains)
        if (matching.isEmpty()) return 0
        val next = LinkedHashMap(drafts)
        matching.forEach(next::remove)
        persistAndSwap(next)
        return matching.size
    }

    private fun replace(
        previous: TeacherGradeDraft?,
        nextDraft: TeacherGradeDraft,
    ): TeacherGradeDraft {
        validateDraft(nextDraft)
        val next = LinkedHashMap(drafts)
        previous?.let { next.remove(it.draftId) }
        check(!next.containsKey(nextDraft.draftId)) { "Duplicate teacher grade draft ID" }
        next[nextDraft.draftId] = nextDraft
        validateCollectionBounds(next.values)
        persistAndSwap(next)
        return nextDraft
    }

    private inline fun update(
        target: TeacherGradeDraftTarget,
        groupId: String,
        block: (TeacherGradeDraft, Long) -> TeacherGradeDraft,
    ): TeacherGradeDraft? {
        validateTarget(target)
        validateId(groupId, "groupId")
        val existing = findByGroup(target, groupId) ?: return null
        val updated = block(existing, mutationTimestamp(existing))
        if (updated == existing) return existing
        return replace(existing, updated)
    }

    private fun findByGroup(target: TeacherGradeDraftTarget, groupId: String): TeacherGradeDraft? =
        drafts.values.firstOrNull { it.target == target && it.groupId == groupId }

    private fun persistAndSwap(next: LinkedHashMap<String, TeacherGradeDraft>) {
        validateCollectionBounds(next.values)
        val bytes = encode(next.values).toString().toByteArray(Charsets.UTF_8)
        atomicFile.write(bytes)
        drafts = next
        MasterNoteDataCommitBus.recordDurableCommit()
    }

    @Synchronized
    internal fun reloadAfterDataRootReplacement() {
        drafts = loadDrafts()
    }

    private fun loadDrafts(): LinkedHashMap<String, TeacherGradeDraft> {
        if (!file.isFile) return linkedMapOf()
        return runCatching {
            val bytes = atomicFile.read()
            decode(JSONObject(bytes.toString(Charsets.UTF_8)))
        }.getOrElse {
            atomicFile.quarantineCorrupt()
            linkedMapOf()
        }
    }

    private fun encode(values: Collection<TeacherGradeDraft>): JSONObject = JSONObject()
        .put("formatVersion", TEACHER_GRADE_DRAFT_FORMAT_VERSION)
        .put("drafts", JSONArray().apply { values.forEach { put(encodeDraft(it)) } })

    private fun encodeDraft(draft: TeacherGradeDraft): JSONObject = JSONObject()
        .put("draftId", draft.draftId)
        .put("groupId", draft.groupId)
        .put("bookId", draft.target.bookId)
        .put("pageNumber", draft.target.pageNumber)
        .put("attemptNo", draft.target.attemptNo)
        .put("anchorX", draft.anchor.x.toDouble())
        .put("anchorY", draft.anchor.y.toDouble())
        .put("anchorPressure", draft.anchor.pressure.toDouble())
        .put("color", draft.color.name)
        .put("hidden", draft.hidden)
        .put("appendOnCommit", draft.appendOnCommit)
        .put("createdAtEpochMillis", draft.createdAtEpochMillis)
        .put("updatedAtEpochMillis", draft.updatedAtEpochMillis)

    private fun decode(root: JSONObject): LinkedHashMap<String, TeacherGradeDraft> {
        require(root.getInt("formatVersion") == TEACHER_GRADE_DRAFT_FORMAT_VERSION)
        val array = root.getJSONArray("drafts")
        require(array.length() <= limits.maxDraftsTotal)
        val result = linkedMapOf<String, TeacherGradeDraft>()
        val targetGroups = hashSetOf<Pair<TeacherGradeDraftTarget, String>>()
        for (index in 0 until array.length()) {
            val draft = decodeDraft(array.getJSONObject(index))
            validateDraft(draft)
            require(result.put(draft.draftId, draft) == null) { "Duplicate draft ID" }
            require(targetGroups.add(draft.target to draft.groupId)) { "Duplicate target/group draft" }
        }
        validateCollectionBounds(result.values)
        return result
    }

    private fun decodeDraft(value: JSONObject): TeacherGradeDraft = TeacherGradeDraft(
        draftId = value.getString("draftId"),
        groupId = value.getString("groupId"),
        target = TeacherGradeDraftTarget(
            bookId = value.getString("bookId"),
            pageNumber = value.getInt("pageNumber"),
            attemptNo = value.getInt("attemptNo"),
        ),
        anchor = PagePoint(
            x = value.getDouble("anchorX").toFloat(),
            y = value.getDouble("anchorY").toFloat(),
            pressure = value.getDouble("anchorPressure").toFloat(),
        ),
        color = MarkColor.valueOf(value.getString("color")),
        hidden = value.getBoolean("hidden"),
        appendOnCommit = value.optBoolean("appendOnCommit", true),
        createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
        updatedAtEpochMillis = value.getLong("updatedAtEpochMillis"),
    )

    private fun validateCollectionBounds(values: Collection<TeacherGradeDraft>) {
        require(values.size <= limits.maxDraftsTotal) { "Too many teacher grade drafts" }
        values.groupingBy(TeacherGradeDraft::target).eachCount().forEach { (_, count) ->
            require(count <= limits.maxDraftsPerTarget) { "Too many drafts for one target" }
        }
    }

    private fun validateDraft(draft: TeacherGradeDraft) {
        validateId(draft.draftId, "draftId")
        validateId(draft.groupId, "groupId")
        validateTarget(draft.target)
        validateAnchor(draft.anchor)
        require(draft.createdAtEpochMillis >= 0L)
        require(draft.updatedAtEpochMillis >= draft.createdAtEpochMillis)
    }

    private fun validateTarget(target: TeacherGradeDraftTarget) {
        require(target.bookId.isNotBlank())
        require(target.bookId.toByteArray(Charsets.UTF_8).size <= MAX_BOOK_ID_UTF8_BYTES)
        require(target.pageNumber in 0..MAX_PAGE_NUMBER)
        require(target.attemptNo in 0..MAX_ATTEMPT_NUMBER)
    }

    private fun validateAnchor(anchor: PagePoint) {
        require(anchor.x.isFinite() && anchor.x in 0f..MAX_CANONICAL_PAGE_X)
        require(anchor.y.isFinite() && anchor.y in 0f..MAX_CANONICAL_PAGE_Y)
        require(anchor.pressure.isFinite() && anchor.pressure in 0f..1f)
    }

    private fun validateId(value: String, label: String) {
        require(value.isNotBlank()) { "$label is blank" }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_ID_UTF8_BYTES) { "$label is too long" }
    }

    private fun validNow(): Long = nowEpochMillis().also { require(it >= 0L) }

    private fun mutationTimestamp(existing: TeacherGradeDraft): Long =
        maxOf(validNow(), existing.updatedAtEpochMillis)

    private fun freshDraftId(reservedIds: Set<String> = emptySet()): String =
        freshUniqueId { candidate ->
            candidate in reservedIds || drafts.containsKey(candidate) ||
                drafts.values.any { it.groupId == candidate }
        }

    private fun freshGroupId(reservedIds: Set<String> = emptySet()): String =
        freshUniqueId { candidate ->
            candidate in reservedIds || drafts.containsKey(candidate) ||
                drafts.values.any { it.groupId == candidate }
        }

    private inline fun freshUniqueId(alreadyUsed: (String) -> Boolean): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = newUuid()
            validateId(candidate, "generated UUID")
            if (!alreadyUsed(candidate)) return candidate
        }
        error("Unable to allocate a unique teacher grade draft ID")
    }

    companion object {
        private const val MAX_BOOK_ID_UTF8_BYTES = 512
        private const val MAX_ID_UTF8_BYTES = 256
        private const val MAX_PAGE_NUMBER = 100_000
        private const val MAX_ATTEMPT_NUMBER = 10_000
        private const val MAX_CANONICAL_PAGE_X = 1_000f
        private const val MAX_CANONICAL_PAGE_Y = 1_000_000f
        private const val MAX_ID_GENERATION_ATTEMPTS = 32

        @Volatile private var instance: TeacherGradeDraftStore? = null

        @Suppress("unused")
        private val dataRootSubscription = MasterNoteDataRootBus.addListener {
            instance?.reloadAfterDataRootReplacement()
        }

        fun get(context: Context): TeacherGradeDraftStore = instance ?: synchronized(this) {
            instance ?: TeacherGradeDraftStore(
                File(context.applicationContext.filesDir, "masternote/$TEACHER_GRADE_DRAFT_FILE"),
            ).also { instance = it }
        }

        fun resetForRestore() {
            instance?.reloadAfterDataRootReplacement()
        }
    }
}

/** Minimal atomic file wrapper that remains executable in local JVM tests. */
private class AtomicDraftJsonFile(
    private val baseFile: File,
    private val maximumBytes: Int,
) {
    fun read(): ByteArray {
        require(baseFile.length() in 0..maximumBytes.toLong()) { "Teacher grade draft file is too large" }
        return baseFile.readBytes().also { require(it.size <= maximumBytes) }
    }

    fun write(bytes: ByteArray) {
        require(bytes.size <= maximumBytes) { "Teacher grade draft file is too large" }
        val parent = requireNotNull(baseFile.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        val temporary = File(parent, "${baseFile.name}.new")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveReplacing(temporary, baseFile)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun quarantineCorrupt(): File? {
        if (!baseFile.exists()) return null
        val parent = requireNotNull(baseFile.parentFile)
        val quarantine = File(
            parent,
            "${baseFile.name}.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID()}",
        )
        return runCatching {
            moveReplacing(baseFile, quarantine)
            quarantine
        }.getOrNull()
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
