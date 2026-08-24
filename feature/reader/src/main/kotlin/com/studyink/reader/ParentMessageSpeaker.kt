package com.studyink.reader

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Lazily owns one Korean TTS instance while the Reader is alive. */
class ParentMessageSpeaker(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initializing = false
    private var pendingText: String? = null
    private var closed = false
    private val utteranceGate = CurrentUtteranceGate()

    fun speak(text: String) {
        val normalized = text.trim().take(MAX_SPOKEN_CHARS)
        if (closed || normalized.isEmpty()) return
        pendingText = normalized
        val existing = engine
        if (existing != null) {
            speakReady(existing, normalized)
            return
        }
        if (initializing) return
        initializing = true
        TextToSpeech(appContext) { status ->
            initializing = false
            val ready = itEngine
            if (closed || status != TextToSpeech.SUCCESS || ready == null) {
                pendingText = null
                ready?.shutdown()
                itEngine = null
                onSpeakingChanged(false)
                return@TextToSpeech
            }
            engine = ready
            ready.language = Locale.KOREAN
            ready.setSpeechRate(0.96f)
            ready.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceGate.isCurrent(utteranceId)) notifySpeaking(true)
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceGate.finishIfCurrent(utteranceId)) notifySpeaking(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceGate.finishIfCurrent(utteranceId)) notifySpeaking(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceGate.finishIfCurrent(utteranceId)) notifySpeaking(false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceGate.finishIfCurrent(utteranceId)) notifySpeaking(false)
                }
            })
            pendingText?.also { pending -> speakReady(ready, pending) }
        }.also { created -> itEngine = created }
    }

    private var itEngine: TextToSpeech? = null

    private fun speakReady(tts: TextToSpeech, text: String) {
        pendingText = null
        val utteranceId = "parent-${UUID.randomUUID()}"
        utteranceGate.begin(utteranceId)
        onSpeakingChanged(true)
        val result = tts.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            utteranceId,
        )
        if (result == TextToSpeech.ERROR && utteranceGate.finishIfCurrent(utteranceId)) {
            onSpeakingChanged(false)
        }
    }

    fun stop() {
        pendingText = null
        utteranceGate.clear()
        engine?.stop()
        onSpeakingChanged(false)
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingText = null
        utteranceGate.clear()
        engine?.stop()
        engine?.shutdown()
        if (itEngine !== engine) itEngine?.shutdown()
        engine = null
        itEngine = null
        onSpeakingChanged(false)
    }

    private fun notifySpeaking(value: Boolean) {
        mainHandler.post { if (!closed) onSpeakingChanged(value) }
    }

    private companion object { const val MAX_SPOKEN_CHARS = 500 }
}

/** Thread-safe because Android TTS progress callbacks are not guaranteed to use the main thread. */
internal class CurrentUtteranceGate {
    private val lock = Any()
    private var currentId: String? = null

    fun begin(utteranceId: String) = synchronized(lock) {
        require(utteranceId.isNotBlank())
        currentId = utteranceId
    }

    fun isCurrent(utteranceId: String?): Boolean = synchronized(lock) {
        utteranceId != null && utteranceId == currentId
    }

    fun finishIfCurrent(utteranceId: String?): Boolean = synchronized(lock) {
        if (utteranceId == null || utteranceId != currentId) return false
        currentId = null
        true
    }

    fun clear() = synchronized(lock) { currentId = null }
}
