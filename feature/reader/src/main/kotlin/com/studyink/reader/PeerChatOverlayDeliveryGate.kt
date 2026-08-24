package com.studyink.reader

/** A chat overlay is ephemeral, so retain a pre-resume delivery until it can actually be shown. */
internal data class PeerChatOverlayDelivery(
    val pairId: String,
    val messageId: String,
    val text: String,
)

/**
 * Main-thread delivery gate for ReaderActivity.
 *
 * The process bus is sticky and Activity.onStart precedes onResume. Remembering delivered keys
 * separately from the pending item prevents both pre-resume loss and sticky replay duplicates.
 */
internal class PeerChatOverlayDeliveryGate(
    private val maxRememberedDeliveries: Int = 32,
) {
    private var pending: PeerChatOverlayDelivery? = null
    private val deliveredKeys = linkedSetOf<Pair<String, String>>()

    init {
        require(maxRememberedDeliveries > 0)
    }

    fun offer(
        pairId: String,
        messageId: String,
        text: String,
        canDisplayNow: Boolean,
    ): PeerChatOverlayDelivery? {
        val candidate = PeerChatOverlayDelivery(pairId, messageId, text)
        val key = candidate.key
        if (key in deliveredKeys) {
            if (pending?.key == key) pending = null
            return null
        }
        if (!canDisplayNow) {
            // Only the newest instruction needs the five-second overlay. Full history remains in
            // the durable chat journal and can be opened from the Telegram cell.
            pending = candidate
            return null
        }
        pending = null
        rememberDelivered(key)
        return candidate
    }

    fun resume(activePairId: String?): PeerChatOverlayDelivery? {
        val candidate = pending ?: return null
        pending = null
        if (activePairId == null || candidate.pairId != activePairId) return null
        val key = candidate.key
        if (key in deliveredKeys) return null
        rememberDelivered(key)
        return candidate
    }

    fun clearPending() {
        pending = null
    }

    private fun rememberDelivered(key: Pair<String, String>) {
        deliveredKeys += key
        while (deliveredKeys.size > maxRememberedDeliveries) {
            deliveredKeys.remove(deliveredKeys.first())
        }
    }

    private val PeerChatOverlayDelivery.key: Pair<String, String>
        get() = pairId to messageId
}
