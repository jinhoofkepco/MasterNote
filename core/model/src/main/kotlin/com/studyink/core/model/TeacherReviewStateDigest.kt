package com.studyink.core.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Portable identity of the exact teacher review installed for one student attempt.
 *
 * The publication id identifies the explicit press of the publish button. The layer and grade
 * digests additionally prove the installed ink and score state, so a retained receipt cannot hide
 * missing or different review data.
 */
data class TeacherReviewStateEvidence(
    val attemptNo: Int,
    val publicationId: String,
    val resultLayerSha256: String,
    val markGroupsSha256: String,
) {
    init {
        require(attemptNo > 0) { "Teacher review attempt must be one-based" }
        require(publicationId.matches(SHA256_HEX)) { "Teacher review publication id is invalid" }
        require(resultLayerSha256.matches(SHA256_HEX)) { "Teacher review layer digest is invalid" }
        require(markGroupsSha256.matches(SHA256_HEX)) { "Teacher review grade digest is invalid" }
    }
}

/** Portable group-level metadata shared by every attempt that uses the same mark-group id. */
data class TeacherReviewMarkGroupMetadata(
    val groupId: String,
    val anchor: PagePoint,
    val createdAtEpochMillis: Long,
    val hiddenAtEpochMillis: Long?,
    val syncRevision: Long,
    val lastModifiedByDeviceId: String,
) {
    init {
        require(groupId.isNotEmpty() && groupId.toByteArray(Charsets.UTF_8).size <= MAX_ID_UTF8_BYTES) {
            "Teacher review grade group id is invalid"
        }
        require(anchor.x.isFinite() && anchor.x in 0f..1_000f &&
            anchor.y.isFinite() && anchor.y in 0f..1_000_000f &&
            anchor.pressure.isFinite() && anchor.pressure >= 0f
        ) { "Teacher review grade group anchor is invalid" }
        require(createdAtEpochMillis >= 0L && (hiddenAtEpochMillis ?: 0L) >= 0L) {
            "Teacher review grade group time is invalid"
        }
        require(syncRevision >= 0L &&
            lastModifiedByDeviceId.toByteArray(Charsets.UTF_8).size <= MAX_DEVICE_ID_UTF8_BYTES
        ) { "Teacher review grade group sync metadata is invalid" }
        require(syncRevision == 0L || lastModifiedByDeviceId.isNotBlank()) {
            "Teacher review grade group writer is missing"
        }
    }
}

/**
 * Hashes a page's teacher-review state independently of collection iteration order.
 *
 * The canonical v1 stream is domain-separated and length-prefixed, then contains entries ordered
 * by attempt number. Digest values are decoded to their fixed 32-byte representation before being
 * hashed. The empty collection is a real, non-null "no review installed" state.
 */
fun teacherReviewStateSha256(entries: Collection<TeacherReviewStateEvidence>): String {
    require(entries.size <= MAX_TEACHER_REVIEW_STATE_ENTRIES) {
        "Teacher review state has too many attempts"
    }
    val ordered = entries.sortedBy(TeacherReviewStateEvidence::attemptNo)
    var previousAttemptNo = 0
    ordered.forEach { entry ->
        require(entry.attemptNo > previousAttemptNo) {
            "Teacher review state attempts must be unique"
        }
        previousAttemptNo = entry.attemptNo
    }
    val canonical = ByteArrayOutputStream(
        DOMAIN.size + Int.SIZE_BYTES * 2 + ordered.size * TEACHER_REVIEW_STATE_ENTRY_BYTES,
    )
    DataOutputStream(canonical).use { output ->
        output.writeInt(DOMAIN.size)
        output.write(DOMAIN)
        output.writeInt(ordered.size)
        ordered.forEach { entry ->
            output.writeInt(entry.attemptNo)
            output.write(entry.publicationId.hexToBytes())
            output.write(entry.resultLayerSha256.hexToBytes())
            output.write(entry.markGroupsSha256.hexToBytes())
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).toHex()
}

/**
 * Portable digest of an exact-attempt grade snapshot.
 *
 * Only the group identity and the supplied exact-attempt mark history are included. Anchor,
 * group-level tombstones, revision, and writer metadata are shared by every attempt in a group;
 * hashing them per attempt would make a later attempt's ordinary edit invalidate an older attempt
 * forever. Group order is canonicalized by id and mark order is retained as revision history.
 */
fun teacherReviewMarkGroupsSha256(groups: Collection<MarkGroup>): String {
    require(groups.size <= MAX_TEACHER_REVIEW_MARK_GROUPS) {
        "Teacher review grade state has too many groups"
    }
    val ordered = groups.sortedBy(MarkGroup::id)
    var previousGroupId: String? = null
    ordered.forEach { group ->
        require(group.id.isNotEmpty() && group.id.toByteArray(Charsets.UTF_8).size <= MAX_ID_UTF8_BYTES) {
            "Teacher review grade group id is invalid"
        }
        require(previousGroupId != group.id) { "Teacher review grade group ids must be unique" }
        require(group.marks.isNotEmpty() && group.marks.size <= MAX_TEACHER_REVIEW_MARKS_PER_GROUP) {
            "Teacher review grade group has too many marks"
        }
        group.marks.forEach { mark ->
            require(mark.attemptNo >= TEACHER_PAGE_REVIEW_ATTEMPT_NO) {
                "Teacher review mark attempt is invalid"
            }
            require(mark.gradedAtEpochMillis >= 0L && (mark.hiddenAtEpochMillis ?: 0L) >= 0L) {
                "Teacher review mark time is invalid"
            }
        }
        previousGroupId = group.id
    }

    // JSONObject key iteration is not a canonical wire format and differs between some Android
    // and JVM implementations. Use an explicit length-prefixed binary stream so two devices with
    // different OS/runtime versions always produce the same digest.
    val canonical = ByteArrayOutputStream()
    DataOutputStream(canonical).use { output ->
        output.writeInt(GRADE_DOMAIN.size)
        output.write(GRADE_DOMAIN)
        output.writeInt(ordered.size)
        ordered.forEach { group ->
            output.writeUtf8(group.id)
            output.writeInt(group.marks.size)
            group.marks.forEach { mark ->
                output.writeInt(mark.attemptNo)
                output.writeUtf8(mark.color.name)
                output.writeLong(mark.gradedAtEpochMillis)
                output.writeBoolean(mark.hiddenAtEpochMillis != null)
                mark.hiddenAtEpochMillis?.let(output::writeLong)
            }
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).toHex()
}

/**
 * Selects the newest global metadata for every group id across retained attempt publications.
 *
 * The ordering intentionally matches the catalog's full-state merge: revision, writer id, then the
 * deterministic state key. Book/page identity and attempt marks are excluded because the result is
 * transported between devices and the metadata is shared by all attempts in the group.
 */
fun normalizeTeacherReviewMarkGroupMetadata(
    groups: Collection<MarkGroup>,
): List<TeacherReviewMarkGroupMetadata> = normalizeTeacherReviewMarkGroupMetadataValues(
    groups.map(MarkGroup::toTeacherReviewMetadata),
)

/**
 * Established global metadata ordering used to decide whether duplicate repair must reapply.
 * Positive means [left] is newer, negative means [right] is newer, and zero means equivalent.
 */
fun compareTeacherReviewMarkGroupMetadataGlobalOrder(left: MarkGroup, right: MarkGroup): Int {
    require(left.id == right.id) { "Teacher review grade metadata ids must match" }
    return left.toTeacherReviewMetadata().compareGlobalSyncOrder(right.toTeacherReviewMetadata())
}

/** Merges already-captured authority metadata without reopening publication artifacts. */
fun normalizeTeacherReviewMarkGroupMetadataValues(
    metadata: Collection<TeacherReviewMarkGroupMetadata>,
): List<TeacherReviewMarkGroupMetadata> {
    require(metadata.size <= MAX_TEACHER_REVIEW_METADATA_INPUT_GROUPS) {
        "Teacher review grade metadata has too many input groups"
    }
    val latest = linkedMapOf<String, TeacherReviewMarkGroupMetadata>()
    metadata.forEach { candidate ->
        val current = latest[candidate.groupId]
        if (current == null || candidate.compareGlobalSyncOrder(current) > 0) {
            latest[candidate.groupId] = candidate
        }
    }
    return latest.values.sortedBy(TeacherReviewMarkGroupMetadata::groupId)
}

/** Canonical binary hash of already-normalized page-level mark-group metadata. */
fun teacherReviewMarkGroupMetadataSha256(
    metadata: Collection<TeacherReviewMarkGroupMetadata>,
): String {
    require(metadata.size <= MAX_TEACHER_REVIEW_MARK_GROUPS) {
        "Teacher review grade metadata has too many groups"
    }
    val ordered = metadata.sortedBy(TeacherReviewMarkGroupMetadata::groupId)
    var previousGroupId: String? = null
    ordered.forEach { value ->
        require(previousGroupId != value.groupId) {
            "Teacher review grade metadata group ids must be unique"
        }
        previousGroupId = value.groupId
    }
    val canonical = ByteArrayOutputStream()
    DataOutputStream(canonical).use { output ->
        output.writeInt(GRADE_METADATA_DOMAIN.size)
        output.write(GRADE_METADATA_DOMAIN)
        output.writeInt(ordered.size)
        ordered.forEach { value ->
            output.writeUtf8(value.groupId)
            output.writeInt(value.anchor.x.toRawBits())
            output.writeInt(value.anchor.y.toRawBits())
            output.writeInt(value.anchor.pressure.toRawBits())
            output.writeLong(value.createdAtEpochMillis)
            output.writeBoolean(value.hiddenAtEpochMillis != null)
            value.hiddenAtEpochMillis?.let(output::writeLong)
            output.writeLong(value.syncRevision)
            output.writeUtf8(value.lastModifiedByDeviceId)
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).toHex()
}

/**
 * Combines one immutable attempt's mark history with the page's shared normalized metadata.
 * Keeping both component hashes in the ledger avoids reopening large publication artifacts during
 * the five-second manifest lane.
 */
fun teacherReviewGradeStateSha256(
    attemptMarksSha256: String,
    pageMetadataSha256: String,
): String {
    require(attemptMarksSha256.matches(SHA256_HEX)) {
        "Teacher review attempt-mark digest is invalid"
    }
    require(pageMetadataSha256.matches(SHA256_HEX)) {
        "Teacher review grade metadata digest is invalid"
    }
    val canonical = ByteArrayOutputStream(
        GRADE_COMBINED_DOMAIN.size + Int.SIZE_BYTES + SHA256_BYTES * 2,
    )
    DataOutputStream(canonical).use { output ->
        output.writeInt(GRADE_COMBINED_DOMAIN.size)
        output.write(GRADE_COMBINED_DOMAIN)
        output.write(attemptMarksSha256.hexToBytes())
        output.write(pageMetadataSha256.hexToBytes())
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).toHex()
}

private fun TeacherReviewMarkGroupMetadata.compareGlobalSyncOrder(
    other: TeacherReviewMarkGroupMetadata,
): Int {
    syncRevision.compareTo(other.syncRevision).takeIf { it != 0 }?.let { return it }
    lastModifiedByDeviceId.compareTo(other.lastModifiedByDeviceId).takeIf { it != 0 }?.let {
        return it
    }
    return globalSyncStateKey().compareTo(other.globalSyncStateKey())
}

private fun MarkGroup.toTeacherReviewMetadata(): TeacherReviewMarkGroupMetadata =
    TeacherReviewMarkGroupMetadata(
        groupId = id,
        anchor = anchor,
        createdAtEpochMillis = createdAtEpochMillis,
        hiddenAtEpochMillis = hiddenAtEpochMillis,
        syncRevision = syncRevision,
        lastModifiedByDeviceId = lastModifiedByDeviceId,
    )

/** Kept byte-for-byte equivalent to LibraryRepository's established deterministic tie-break. */
private fun TeacherReviewMarkGroupMetadata.globalSyncStateKey(): String = buildString {
    append(createdAtEpochMillis).append('|')
    append(hiddenAtEpochMillis ?: -1L).append('|')
    append(anchor.x.toRawBits()).append(',')
    append(anchor.y.toRawBits()).append(',')
    append(anchor.pressure.toRawBits())
}

private fun DataOutputStream.writeUtf8(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun String.hexToBytes(): ByteArray = ByteArray(SHA256_BYTES) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val MAX_TEACHER_REVIEW_STATE_ENTRIES: Int = 4_096
private const val MAX_TEACHER_REVIEW_MARK_GROUPS: Int = 4_096
private const val MAX_TEACHER_REVIEW_MARKS_PER_GROUP: Int = 4_096
private const val MAX_TEACHER_REVIEW_METADATA_INPUT_GROUPS: Int = 65_536
private const val MAX_ID_UTF8_BYTES: Int = 16 * 1024
private const val MAX_DEVICE_ID_UTF8_BYTES: Int = 16 * 1024
private const val SHA256_BYTES: Int = 32
private const val TEACHER_REVIEW_STATE_ENTRY_BYTES: Int = Int.SIZE_BYTES + SHA256_BYTES * 3
private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val DOMAIN = "MasterNote/TeacherReviewState/v1".toByteArray(Charsets.US_ASCII)
private val GRADE_DOMAIN = "MasterNote/TeacherReviewGradeState/v1".toByteArray(Charsets.US_ASCII)
private val GRADE_METADATA_DOMAIN =
    "MasterNote/TeacherReviewGradeMetadata/v1".toByteArray(Charsets.US_ASCII)
private val GRADE_COMBINED_DOMAIN =
    "MasterNote/TeacherReviewGradeCombined/v1".toByteArray(Charsets.US_ASCII)
