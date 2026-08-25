package com.studyink.core.model

/**
 * One shared size calculation for the immutable teacher-review bundle used by both transports.
 * Keeping it beside the model prevents the durable artifact, LAN codec, and Telegram codec from
 * silently accepting three different maximum payloads.
 */
object TeacherReviewPublicationLimits {
    /** Leaves protocol framing headroom inside the two-MiB encrypted Telegram plaintext. */
    const val MAX_WIRE_PAYLOAD_BYTES: Int = 2 * 1024 * 1024 - 32 * 1024
    const val ROOT_FIXED_BYTES: Int = 24

    /** Exact byte count produced by the v1 binary review codec, or [Int.MAX_VALUE] on overflow. */
    fun encodedSize(checkpointSizeBytes: Int, markGroups: List<MarkGroup>): Int {
        if (checkpointSizeBytes < 0) return Int.MAX_VALUE
        var size = ROOT_FIXED_BYTES.toLong() + checkpointSizeBytes.toLong()
        markGroups.forEach { group ->
            size += LENGTH_BYTES + group.id.toByteArray(Charsets.UTF_8).size
            size += LENGTH_BYTES + group.bookId.toByteArray(Charsets.UTF_8).size
            size += GROUP_FIXED_BYTES
            if (group.hiddenAtEpochMillis != null) size += LONG_BYTES
            size += LENGTH_BYTES + group.lastModifiedByDeviceId.toByteArray(Charsets.UTF_8).size
            group.marks.forEach { mark ->
                size += MARK_FIXED_BYTES
                if (mark.hiddenAtEpochMillis != null) size += LONG_BYTES
            }
            if (size > Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return size.toInt()
    }

    fun fits(checkpointSizeBytes: Int, markGroups: List<MarkGroup>): Boolean =
        encodedSize(checkpointSizeBytes, markGroups) <= MAX_WIRE_PAYLOAD_BYTES

    private const val LENGTH_BYTES: Int = Int.SIZE_BYTES
    private const val LONG_BYTES: Int = Long.SIZE_BYTES
    private const val GROUP_FIXED_BYTES: Int =
        Int.SIZE_BYTES + (3 * Float.SIZE_BYTES) + Long.SIZE_BYTES + 1 + Long.SIZE_BYTES + Int.SIZE_BYTES
    private const val MARK_FIXED_BYTES: Int = Int.SIZE_BYTES + 1 + Long.SIZE_BYTES + 1
}
