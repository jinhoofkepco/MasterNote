package com.studyink.reader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import java.util.UUID

enum class StudentVoiceState {
    OFF,
    LISTENING_FOR_WAKE,
    WAITING_FOR_MESSAGE,
    DICTATING,
    SENDING,
}

data class StudentVoiceTextMessage(
    val idempotencyKey: String,
    val text: String,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(text.isNotBlank())
    }
}

/**
 * Activity-bound, single-microphone state machine for: "아빠" -> beep -> speech-to-text message.
 *
 * Wake recognition and dictation deliberately use separate SpeechRecognizer sessions. Android may
 * still deliver callbacks after cancel/destroy, so every listener carries a generation token and a
 * stale wake callback can never consume or reset the following dictation session.
 */
class StudentVoiceMessageController(
    context: Context,
    private val onTextReady: (StudentVoiceTextMessage) -> Unit,
    private val onStateChanged: (StudentVoiceState) -> Unit = {},
    private val onError: (String) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val recognitionGate = StudentVoiceRecognitionGate()
    private val deliveryGate = StudentVoiceDeliveryGate()
    private var enabled = false
    private var resumed = false
    private var suspended = false
    private var closed = false
    private var recognizer: SpeechRecognizer? = null
    private var readyTone: ToneGenerator? = null
    private var dictationTimeout: Runnable? = null
    private var state = StudentVoiceState.OFF

    private val restartWakeListening = Runnable { startWakeListeningIfPossible() }
    private val beginDictation = Runnable { startDictationIfPossible() }
    private val releaseReadyTone = Runnable { releaseReadyTone() }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) startWakeListeningIfPossible() else stopAll()
    }

    fun onResume() {
        resumed = true
        startWakeListeningIfPossible()
    }

    fun onPause() {
        resumed = false
        stopAll()
    }

    fun setSuspended(value: Boolean) {
        if (suspended == value) return
        suspended = value
        if (value) {
            handler.removeCallbacks(beginDictation)
            if (state != StudentVoiceState.SENDING) {
                stopRecognizer()
                transition(StudentVoiceState.OFF)
            }
        } else if (!deliveryGate.hasPending) {
            handler.postDelayed(restartWakeListening, AFTER_SPEAKER_DELAY_MILLIS)
        }
    }

    val isDictating: Boolean get() = state == StudentVoiceState.DICTATING

    /** A late completion from an Activity's older message must not reset a newer recognition run. */
    fun markUploadFinished(idempotencyKey: String) {
        if (!deliveryGate.finish(idempotencyKey)) return
        if (state == StudentVoiceState.SENDING) transition(StudentVoiceState.OFF)
        handler.postDelayed(restartWakeListening, AFTER_SEND_DELAY_MILLIS)
    }

    private fun startWakeListeningIfPossible() {
        handler.removeCallbacks(restartWakeListening)
        if (!canRecognize() || deliveryGate.hasPending) {
            if (state != StudentVoiceState.SENDING) transition(StudentVoiceState.OFF)
            return
        }
        startRecognizer(StudentVoiceRecognitionMode.WAKE)
    }

    private fun startDictationIfPossible() {
        handler.removeCallbacks(beginDictation)
        if (!canRecognize() || deliveryGate.hasPending) {
            transition(StudentVoiceState.OFF)
            scheduleWakeRestart()
            return
        }
        startRecognizer(StudentVoiceRecognitionMode.DICTATION)
    }

    private fun canRecognize(): Boolean =
        !closed && enabled && resumed && !suspended &&
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecognizer(mode: StudentVoiceRecognitionMode) {
        stopRecognizer()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("이 기기에서 음성 호출을 사용할 수 없습니다.")
            transition(StudentVoiceState.OFF)
            return
        }
        val session = recognitionGate.begin(mode)
        val next = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }
            .getOrElse {
                recognitionGate.invalidate()
                onError("음성 인식을 시작하지 못했습니다.")
                transition(StudentVoiceState.OFF)
                scheduleWakeRestart()
                return
            }
        recognizer = next
        next.setRecognitionListener(recognitionListener(next, session))
        val intent = recognitionIntent(mode)
        transition(
            if (mode == StudentVoiceRecognitionMode.WAKE) {
                StudentVoiceState.LISTENING_FOR_WAKE
            } else {
                StudentVoiceState.DICTATING
            },
        )
        if (mode == StudentVoiceRecognitionMode.DICTATION) scheduleDictationTimeout(session)
        runCatching { next.startListening(intent) }.onFailure {
            if (!isCurrent(next, session)) return@onFailure
            stopRecognizer()
            if (mode == StudentVoiceRecognitionMode.DICTATION) {
                onError("메시지 받아쓰기를 시작하지 못했습니다. ‘아빠’라고 다시 불러 주세요.")
            }
            transition(StudentVoiceState.OFF)
            scheduleWakeRestart()
        }
    }

    private fun recognitionIntent(mode: StudentVoiceRecognitionMode) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, if (mode == StudentVoiceRecognitionMode.WAKE) 3 else 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Wake recognition is short and repetitive, so prefer the on-device model. Dictation
            // lets the installed recognizer choose its best available Korean model for accuracy.
            if (mode == StudentVoiceRecognitionMode.WAKE) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun recognitionListener(
        source: SpeechRecognizer,
        session: StudentVoiceRecognitionSession,
    ) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            if (!isCurrent(source, session)) return
            val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            when (session.mode) {
                StudentVoiceRecognitionMode.WAKE -> handleWakeResults(source, session, phrases)
                StudentVoiceRecognitionMode.DICTATION -> handleDictationResults(source, session, phrases)
            }
        }

        override fun onError(error: Int) {
            if (!isCurrent(source, session)) return
            stopRecognizer()
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                this@StudentVoiceMessageController.onError(
                    "말을 글로 보내려면 마이크 권한이 필요합니다.",
                )
                transition(StudentVoiceState.OFF)
                return
            }
            if (session.mode == StudentVoiceRecognitionMode.DICTATION) {
                this@StudentVoiceMessageController.onError(
                    "메시지를 알아듣지 못했습니다. ‘아빠’라고 다시 불러 주세요.",
                )
            }
            transition(StudentVoiceState.OFF)
            scheduleWakeRestart()
        }
    }

    private fun handleWakeResults(
        source: SpeechRecognizer,
        session: StudentVoiceRecognitionSession,
        phrases: List<String>,
    ) {
        if (!isCurrent(source, session)) return
        stopRecognizer()
        if (phrases.any(::isKoreanDadWakePhrase)) {
            transition(StudentVoiceState.WAITING_FOR_MESSAGE)
            playReadyTone()
            handler.postDelayed(beginDictation, MIC_HANDOFF_MILLIS)
        } else {
            transition(StudentVoiceState.OFF)
            scheduleWakeRestart()
        }
    }

    private fun handleDictationResults(
        source: SpeechRecognizer,
        session: StudentVoiceRecognitionSession,
        phrases: List<String>,
    ) {
        if (!isCurrent(source, session)) return
        stopRecognizer()
        val text = phrases.asSequence().map(::normalizeStudentDictation).firstOrNull(String::isNotEmpty)
        if (text == null) {
            onError("메시지를 알아듣지 못했습니다. ‘아빠’라고 다시 불러 주세요.")
            transition(StudentVoiceState.OFF)
            scheduleWakeRestart()
            return
        }
        val message = StudentVoiceTextMessage(
            idempotencyKey = "student-dictation:${UUID.randomUUID()}",
            text = text,
        )
        if (!deliveryGate.begin(message.idempotencyKey)) return
        transition(StudentVoiceState.SENDING)
        runCatching { onTextReady(message) }.onFailure {
            deliveryGate.finish(message.idempotencyKey)
            onError("메시지를 전송 대기열에 저장하지 못했습니다. 다시 시도해 주세요.")
            transition(StudentVoiceState.OFF)
            scheduleWakeRestart()
        }
    }

    private fun scheduleDictationTimeout(session: StudentVoiceRecognitionSession) {
        clearDictationTimeout()
        val timeout = Runnable {
            if (!recognitionGate.isCurrent(session) || session.mode != StudentVoiceRecognitionMode.DICTATION) {
                return@Runnable
            }
            stopRecognizer()
            onError("메시지를 듣지 못했습니다. ‘아빠’라고 다시 불러 주세요.")
            transition(StudentVoiceState.OFF)
            scheduleWakeRestart()
        }
        dictationTimeout = timeout
        handler.postDelayed(timeout, DICTATION_TIMEOUT_MILLIS)
    }

    private fun clearDictationTimeout() {
        dictationTimeout?.let(handler::removeCallbacks)
        dictationTimeout = null
    }

    private fun scheduleWakeRestart() {
        if (!closed && enabled && resumed && !suspended && !deliveryGate.hasPending) {
            handler.removeCallbacks(restartWakeListening)
            handler.postDelayed(restartWakeListening, RECOGNIZER_RETRY_MILLIS)
        }
    }

    private fun isCurrent(
        source: SpeechRecognizer,
        session: StudentVoiceRecognitionSession,
    ): Boolean = recognizer === source && recognitionGate.isCurrent(session)

    private fun stopRecognizer() {
        clearDictationTimeout()
        recognitionGate.invalidate()
        val active = recognizer.also { recognizer = null }
        runCatching { active?.cancel() }
        runCatching { active?.destroy() }
    }

    private fun stopAll() {
        handler.removeCallbacks(restartWakeListening)
        handler.removeCallbacks(beginDictation)
        clearDictationTimeout()
        stopRecognizer()
        releaseReadyTone()
        deliveryGate.clear()
        transition(StudentVoiceState.OFF)
    }

    private fun playReadyTone() {
        releaseReadyTone()
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55).also { tone ->
                readyTone = tone
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, READY_TONE_MILLIS.toInt())
                handler.postDelayed(releaseReadyTone, READY_TONE_RELEASE_MILLIS)
            }
        }
    }

    private fun releaseReadyTone() {
        handler.removeCallbacks(releaseReadyTone)
        val active = readyTone.also { readyTone = null }
        runCatching { active?.release() }
    }

    private fun transition(next: StudentVoiceState) {
        if (state == next) return
        state = next
        onStateChanged(next)
    }

    override fun close() {
        if (closed) return
        closed = true
        stopAll()
    }

    private companion object {
        const val MIC_HANDOFF_MILLIS = 350L
        const val READY_TONE_MILLIS = 100L
        const val READY_TONE_RELEASE_MILLIS = 180L
        const val DICTATION_TIMEOUT_MILLIS = 20_000L
        const val AFTER_SEND_DELAY_MILLIS = 650L
        const val AFTER_SPEAKER_DELAY_MILLIS = 700L
        const val RECOGNIZER_RETRY_MILLIS = 900L
    }
}

internal enum class StudentVoiceRecognitionMode { WAKE, DICTATION }

internal data class StudentVoiceRecognitionSession(
    val generation: Long,
    val mode: StudentVoiceRecognitionMode,
)

internal class StudentVoiceRecognitionGate {
    private var generation = 0L
    private var active: StudentVoiceRecognitionSession? = null

    fun begin(mode: StudentVoiceRecognitionMode): StudentVoiceRecognitionSession =
        StudentVoiceRecognitionSession(++generation, mode).also { active = it }

    fun invalidate() {
        generation++
        active = null
    }

    fun isCurrent(session: StudentVoiceRecognitionSession): Boolean = active == session
}

internal class StudentVoiceDeliveryGate {
    private var pendingId: String? = null

    val hasPending: Boolean get() = pendingId != null

    fun begin(idempotencyKey: String): Boolean {
        require(idempotencyKey.isNotBlank())
        if (pendingId != null) return false
        pendingId = idempotencyKey
        return true
    }

    fun finish(idempotencyKey: String): Boolean {
        if (pendingId != idempotencyKey) return false
        pendingId = null
        return true
    }

    fun clear() {
        pendingId = null
    }
}

internal fun isKoreanDadWakePhrase(value: String): Boolean = value
    .lowercase(Locale.KOREAN)
    .replace(Regex("[\\s.,!?~]+"), "") == "아빠"

internal fun normalizeStudentDictation(value: String): String {
    val normalized = value.trim().replace(Regex("\\s+"), " ")
    if (normalized.isEmpty()) return ""
    val codePointCount = normalized.codePointCount(0, normalized.length)
    if (codePointCount <= MAX_STUDENT_DICTATION_CODE_POINTS) return normalized
    val end = normalized.offsetByCodePoints(0, MAX_STUDENT_DICTATION_CODE_POINTS)
    return normalized.substring(0, end).trimEnd()
}

private const val MAX_STUDENT_DICTATION_CODE_POINTS = 3_500
