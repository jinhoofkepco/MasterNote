package com.studyink.sync.lan

import com.studyink.core.model.Attempt
import org.json.JSONObject

internal object AttemptWireCodec {
    fun encode(attempt: Attempt): JSONObject = JSONObject()
        .put("attemptNo", attempt.attemptNo)
        .put("locked", attempt.locked)
        .put("startedAt", attempt.startedAtEpochMillis)
        .put("lockedAt", attempt.lockedAtEpochMillis ?: JSONObject.NULL)

    /** The service maps the peer's document identity onto the verified local book copy. */
    fun decode(payload: JSONObject, localBookId: String, pageNumber: Int): Attempt = Attempt(
        bookId = localBookId,
        pageNumber = pageNumber,
        attemptNo = payload.getInt("attemptNo"),
        locked = payload.getBoolean("locked"),
        startedAtEpochMillis = payload.getLong("startedAt"),
        lockedAtEpochMillis = if (payload.isNull("lockedAt")) null else payload.getLong("lockedAt"),
    )
}
