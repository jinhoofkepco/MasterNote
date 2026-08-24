package com.studyink.monitor.telegram

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

enum class VoiceMessageRecorderState { IDLE, RECORDING }

data class RecordedVoiceMessage(
    val file: File,
    /** Monotonic duration; successful recordings are always at least 1 ms and at most 60 s. */
    val durationMs: Long,
)

internal data class VoiceMessagePaths(val stagingFile: File, val committedFile: File)

internal object VoiceMessageFilePolicy {
    private val safeId = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

    fun resolve(outputDir: File, messageId: String): Result<VoiceMessagePaths> = runCatching {
        require(safeId.matches(messageId)) {
            "messageId must be 1-64 ASCII letters, digits, '_' or '-', starting alphanumeric."
        }
        val root = outputDir.canonicalFile
        val staging = root.resolve("$messageId.part").canonicalFile
        val committed = root.resolve("$messageId.m4a").canonicalFile
        require(staging.parentFile == root && committed.parentFile == root)
        VoiceMessagePaths(staging, committed)
    }
}

internal class VoiceMessageRecorderStateMachine {
    var state = VoiceMessageRecorderState.IDLE
        private set
    var closed = false
        private set

    fun begin(): Result<Unit> = runCatching {
        check(!closed) { "VoiceMessageRecorder is closed." }
        check(state == VoiceMessageRecorderState.IDLE) { "A recording is already active." }
        state = VoiceMessageRecorderState.RECORDING
    }

    fun requireRecording(): Result<Unit> = runCatching {
        check(!closed) { "VoiceMessageRecorder is closed." }
        check(state == VoiceMessageRecorderState.RECORDING) { "No recording is active." }
    }

    fun idle() { state = VoiceMessageRecorderState.IDLE }
    fun close() { state = VoiceMessageRecorderState.IDLE; closed = true }
}

/**
 * M4A/AAC recorder adapted from FocusMonitor2 VoiceMessageRecorder at commit e5809ebc.
 *
 * Public calls belong on the Android main thread. Wake-word recognition must release the physical
 * microphone before [start]. A process-wide lease prevents two recorder instances from overlapping.
 * Data is committed from `.part` to `.m4a` only after MediaRecorder.stop succeeds.
 */
class VoiceMessageRecorder(
    context: Context,
    private val listener: Listener? = null,
) : AutoCloseable {
    interface Listener {
        fun onAutoStopped(message: RecordedVoiceMessage)
        fun onRecordingError(error: Throwable)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMachine = VoiceMessageRecorderStateMachine()
    private var recorder: MediaRecorder? = null
    private var paths: VoiceMessagePaths? = null
    private var startedAtElapsedMs = 0L
    private var autoStopRunnable: Runnable? = null
    private var ownsMicrophone = false

    val state: VoiceMessageRecorderState
        get() { requireMainThread(); return stateMachine.state }

    fun start(outputDir: File, messageId: String): Result<Unit> {
        mainThreadFailure()?.let { return Result.failure(it) }
        stateMachine.begin().exceptionOrNull()?.let { return Result.failure(it) }
        if (!processMicrophoneLease.compareAndSet(false, true)) {
            stateMachine.idle()
            return Result.failure(IllegalStateException("Another MasterNote voice recording owns the microphone."))
        }
        ownsMicrophone = true

        var preparingRecorder: MediaRecorder? = null
        var preparingPaths: VoiceMessagePaths? = null
        return runCatching {
            require(outputDir.mkdirs() || outputDir.isDirectory)
            require(outputDir.canWrite())
            val resolved = VoiceMessageFilePolicy.resolve(outputDir, messageId).getOrThrow()
            preparingPaths = resolved
            require(!resolved.committedFile.exists()) { "A committed message already exists." }
            if (resolved.stagingFile.exists()) check(resolved.stagingFile.delete())

            val nextRecorder = MediaRecorder(appContext)
            preparingRecorder = nextRecorder
            configure(nextRecorder, resolved.stagingFile)
            nextRecorder.prepare()
            nextRecorder.start()
            recorder = nextRecorder
            paths = resolved
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            preparingRecorder = null
            preparingPaths = null
            scheduleAutoStop()
        }.onFailure {
            runCatching { preparingRecorder?.reset() }
            runCatching { preparingRecorder?.release() }
            preparingPaths?.stagingFile?.delete()
            stateMachine.idle()
            releaseMicrophone()
        }
    }

    fun stop(): Result<RecordedVoiceMessage> {
        mainThreadFailure()?.let { return Result.failure(it) }
        return stopInternal()
    }

    fun cancel() {
        requireMainThread()
        if (stateMachine.state == VoiceMessageRecorderState.RECORDING) cancelInternal()
    }

    override fun close() {
        requireMainThread()
        if (stateMachine.closed) return
        if (stateMachine.state == VoiceMessageRecorderState.RECORDING) cancelInternal()
        cancelAutoStop()
        releaseMicrophone()
        stateMachine.close()
    }

    private fun configure(target: MediaRecorder, staging: File) {
        target.setAudioSource(MediaRecorder.AudioSource.MIC)
        target.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        target.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        target.setAudioChannels(1)
        target.setAudioSamplingRate(44_100)
        target.setAudioEncodingBitRate(64_000)
        target.setMaxDuration(MAX_DURATION_MS.toInt())
        target.setOutputFile(staging.absolutePath)
        target.setOnInfoListener { source, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                onMain { autoStopIfCurrent(source) }
            }
        }
        target.setOnErrorListener { source, what, extra ->
            onMain { errorIfCurrent(source, what, extra) }
        }
    }

    private fun stopInternal(): Result<RecordedVoiceMessage> {
        stateMachine.requireRecording().exceptionOrNull()?.let { return Result.failure(it) }
        cancelAutoStop()
        val activeRecorder = recorder ?: return failMissing("MediaRecorder is missing.")
        val activePaths = paths ?: return failMissing("Output paths are missing.")
        val duration = max(1L, SystemClock.elapsedRealtime() - startedAtElapsedMs)
            .coerceAtMost(MAX_DURATION_MS)
        recorder = null
        paths = null
        startedAtElapsedMs = 0L

        return runCatching {
            activeRecorder.stop()
            activeRecorder.release()
            check(activePaths.stagingFile.isFile) { "MediaRecorder did not create its staging file." }
            check(!activePaths.committedFile.exists())
            runCatching {
                Files.move(
                    activePaths.stagingFile.toPath(),
                    activePaths.committedFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(activePaths.stagingFile.toPath(), activePaths.committedFile.toPath())
            }
            RecordedVoiceMessage(activePaths.committedFile, duration)
        }.onFailure {
            runCatching { activeRecorder.reset() }
            runCatching { activeRecorder.release() }
            activePaths.stagingFile.delete()
        }.also {
            stateMachine.idle()
            releaseMicrophone()
        }
    }

    private fun failMissing(reason: String): Result<RecordedVoiceMessage> {
        paths?.stagingFile?.delete()
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        paths = null
        startedAtElapsedMs = 0L
        cancelAutoStop()
        releaseMicrophone()
        stateMachine.idle()
        return Result.failure(IllegalStateException(reason))
    }

    private fun cancelInternal() {
        cancelAutoStop()
        val activeRecorder = recorder
        val activePaths = paths
        recorder = null
        paths = null
        startedAtElapsedMs = 0L
        runCatching { activeRecorder?.reset() }
        runCatching { activeRecorder?.release() }
        activePaths?.stagingFile?.delete()
        stateMachine.idle()
        releaseMicrophone()
    }

    private fun scheduleAutoStop() {
        cancelAutoStop()
        val scheduled = recorder
        autoStopRunnable = Runnable { autoStopIfCurrent(scheduled) }.also {
            mainHandler.postDelayed(it, MAX_DURATION_MS)
        }
    }

    private fun cancelAutoStop() {
        autoStopRunnable?.let(mainHandler::removeCallbacks)
        autoStopRunnable = null
    }

    private fun autoStopIfCurrent(source: MediaRecorder?) {
        if (source == null || source !== recorder || stateMachine.state != VoiceMessageRecorderState.RECORDING) return
        stopInternal().fold(
            onSuccess = { listener?.onAutoStopped(it) },
            onFailure = { listener?.onRecordingError(it) },
        )
    }

    private fun errorIfCurrent(source: MediaRecorder, what: Int, extra: Int) {
        if (source !== recorder || stateMachine.state != VoiceMessageRecorderState.RECORDING) return
        cancelInternal()
        listener?.onRecordingError(IllegalStateException("MediaRecorder failed (what=$what, extra=$extra)."))
    }

    private fun releaseMicrophone() {
        if (!ownsMicrophone) return
        ownsMicrophone = false
        processMicrophoneLease.set(false)
    }

    private fun mainThreadFailure(): Throwable? = if (Looper.myLooper() == Looper.getMainLooper()) null
    else IllegalStateException("VoiceMessageRecorder must be called on the Android main thread.")

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "VoiceMessageRecorder must be called on the Android main thread."
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    companion object {
        const val MAX_DURATION_MS = 60_000L
        private val processMicrophoneLease = AtomicBoolean(false)
    }
}
