package com.studyink.monitor.core

/** Transport policy states exposed to the coordinator and the single S23 status cell. */
enum class HybridLinkMode {
    LAN_LIVE,
    LAN_GRACE,
    TELEGRAM_FALLBACK,
    OFFLINE_QUEUEING,
}

/** The only two labels shown by the compact hybrid-transport status cell. */
enum class HybridLinkLabel(val text: String) {
    LAN("실"),
    TELEGRAM("텔"),
}

/** Semantic UI health; the Android layer owns the concrete color resources. */
enum class HybridLinkHealth {
    /** Green: the selected transport is ready for application traffic. */
    READY,

    /** Orange: reconnecting, catching up, or waiting for a recently seen peer. */
    TRANSITIONING,

    /** Gray: the fallback transport has not been configured. */
    INACTIVE,

    /** Red: Telegram is configured but its API is unhealthy. */
    ERROR,
}

enum class HybridLinkTransport { LAN, TELEGRAM }

/**
 * One monotonic observation of both transports. Stale lower-layer flags are harmless: LAN is ready
 * only when socket, authenticated handshake, and current-page catch-up are all complete.
 */
data class HybridLinkSignals(
    val lanSocketConnected: Boolean,
    val lanHandshakeComplete: Boolean,
    val lanPageCatchUpComplete: Boolean,
    val telegramConfigured: Boolean,
    val telegramApiHealthy: Boolean,
    val telegramPeerRecent: Boolean,
    val nowElapsedMs: Long,
    /** A definitive socket/service loss; unlike a page catch-up, this should fall back at once. */
    val lanDefinitelyDisconnected: Boolean = false,
) {
    init {
        require(nowElapsedMs >= 0L)
    }

    val lanReady: Boolean
        get() = lanSocketConnected && lanHandshakeComplete && lanPageCatchUpComplete

    val telegramReady: Boolean
        get() = telegramConfigured && telegramApiHealthy && telegramPeerRecent
}

data class HybridLinkDecision(
    val mode: HybridLinkMode,
    val label: HybridLinkLabel,
    val health: HybridLinkHealth,
    /** Null during grace and offline queueing; neither transport may send application traffic. */
    val activeTransport: HybridLinkTransport?,
    /** True on exactly the transition into each Telegram fallback episode. */
    val enteredTelegramFallback: Boolean,
) {
    val telegramActive: Boolean get() = activeTransport == HybridLinkTransport.TELEGRAM
}

/**
 * Deterministic, Android-free LAN-first routing policy.
 *
 * Time is supplied by the caller, which should use one monotonic elapsed-time source. Telegram is
 * never active while LAN is ready. Losing an established LAN starts one four-second grace period;
 * an initially unready LAN uses an already-ready Telegram connection immediately. Once fallback is
 * active, LAN cannot reclaim traffic until page catch-up is complete as part of [HybridLinkSignals.lanReady].
 */
class HybridLinkStateMachine(
    private val lanReadyLossGraceMs: Long = DEFAULT_LAN_READY_LOSS_GRACE_MS,
) {
    private var mode: HybridLinkMode? = null
    private var graceStartedAtElapsedMs: Long? = null
    private var lastObservedElapsedMs: Long? = null

    init {
        require(lanReadyLossGraceMs > 0L)
    }

    fun update(signals: HybridLinkSignals): HybridLinkDecision {
        val previousTime = lastObservedElapsedMs
        require(previousTime == null || signals.nowElapsedMs >= previousTime) {
            "Hybrid-link time must be monotonic."
        }

        val previousMode = mode
        val nextMode = when {
            signals.lanReady -> HybridLinkMode.LAN_LIVE
            previousMode == HybridLinkMode.LAN_LIVE && !signals.lanDefinitelyDisconnected -> {
                graceStartedAtElapsedMs = signals.nowElapsedMs
                HybridLinkMode.LAN_GRACE
            }
            previousMode == HybridLinkMode.LAN_GRACE &&
                !signals.lanDefinitelyDisconnected &&
                withinGrace(signals.nowElapsedMs) ->
                HybridLinkMode.LAN_GRACE
            signals.telegramReady -> HybridLinkMode.TELEGRAM_FALLBACK
            else -> HybridLinkMode.OFFLINE_QUEUEING
        }

        if (nextMode != HybridLinkMode.LAN_GRACE) graceStartedAtElapsedMs = null
        mode = nextMode
        lastObservedElapsedMs = signals.nowElapsedMs

        return decision(
            mode = nextMode,
            signals = signals,
            enteredTelegramFallback = nextMode == HybridLinkMode.TELEGRAM_FALLBACK &&
                previousMode != HybridLinkMode.TELEGRAM_FALLBACK,
        )
    }

    private fun withinGrace(nowElapsedMs: Long): Boolean {
        val startedAt = graceStartedAtElapsedMs ?: return false
        return nowElapsedMs - startedAt < lanReadyLossGraceMs
    }

    private fun decision(
        mode: HybridLinkMode,
        signals: HybridLinkSignals,
        enteredTelegramFallback: Boolean,
    ): HybridLinkDecision = when (mode) {
        HybridLinkMode.LAN_LIVE -> HybridLinkDecision(
            mode = mode,
            label = HybridLinkLabel.LAN,
            health = HybridLinkHealth.READY,
            activeTransport = HybridLinkTransport.LAN,
            enteredTelegramFallback = false,
        )
        HybridLinkMode.LAN_GRACE -> HybridLinkDecision(
            mode = mode,
            label = HybridLinkLabel.LAN,
            health = HybridLinkHealth.TRANSITIONING,
            activeTransport = null,
            enteredTelegramFallback = false,
        )
        HybridLinkMode.TELEGRAM_FALLBACK -> HybridLinkDecision(
            mode = mode,
            label = HybridLinkLabel.TELEGRAM,
            health = HybridLinkHealth.READY,
            activeTransport = HybridLinkTransport.TELEGRAM,
            enteredTelegramFallback = enteredTelegramFallback,
        )
        HybridLinkMode.OFFLINE_QUEUEING -> HybridLinkDecision(
            mode = mode,
            label = HybridLinkLabel.TELEGRAM,
            health = telegramUnavailableHealth(signals),
            activeTransport = null,
            enteredTelegramFallback = false,
        )
    }

    private fun telegramUnavailableHealth(signals: HybridLinkSignals): HybridLinkHealth = when {
        !signals.telegramConfigured -> HybridLinkHealth.INACTIVE
        !signals.telegramApiHealthy -> HybridLinkHealth.ERROR
        else -> HybridLinkHealth.TRANSITIONING
    }

    companion object {
        const val DEFAULT_LAN_READY_LOSS_GRACE_MS = 4_000L
    }
}
