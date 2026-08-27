package com.studyink.app

import android.app.Application
import android.os.SystemClock
import com.studyink.core.model.Attempt
import com.studyink.library.data.LibraryAttemptBus
import com.studyink.library.data.LibraryRepository
import com.studyink.monitor.core.RemoteMonitorMaintenanceBus
import com.studyink.monitor.render.MasterNotePageRenderer
import com.studyink.monitor.render.PageRenderRequest
import com.studyink.monitor.telegram.PendingScreenRequest
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.RemoteReviewRole
import com.studyink.monitor.telegram.TelegramEnqueueResult
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Connects durable MasterNote data to the transport-only Telegram module. */
object MasterNoteRemoteMonitorCoordinator : LibraryAttemptBus.Listener {
    private val initialized = AtomicBoolean(false)
    private lateinit var application: Application
    private lateinit var gateway: RemoteMonitorGateway
    private lateinit var renderer: MasterNotePageRenderer
    private val taskSequence = AtomicLong(0L)
    private val workGeneration = AtomicLong(1L)
    private val worker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(),
        { task -> Thread(task, "MasterNote-remote-render").apply { isDaemon = true } },
    )
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "MasterNote-remote-render-retry").apply { isDaemon = true }
    }
    private val scheduledKeys = ConcurrentHashMap.newKeySet<String>()
    private val submissionScanGenerations = ConcurrentHashMap.newKeySet<Long>()
    private val submissionRetryGenerations = ConcurrentHashMap.newKeySet<Long>()
    private val screenRetryGenerations = ConcurrentHashMap.newKeySet<Long>()
    private val queuedSubmissionCount = AtomicInteger(0)
    private val submissionCapacityBlocked = AtomicBoolean(false)
    private var commandSubscription: AutoCloseable? = null
    private var preferenceSubscription: AutoCloseable? = null
    private var maintenanceSubscription: AutoCloseable? = null
    private var baselineStore: SubmissionBaselineStore? = null
    private val monitoringEnabled = AtomicBoolean(false)
    /** Prevents repeated worker interruption while the same teacher-role gate stays active. */
    private val remoteTeacherGateActive = AtomicBoolean(false)
    private val remoteTeacherCleanupScheduled = AtomicBoolean(false)
    private val maintenanceLock = Any()
    private var pausedForMaintenance = false

    fun initialize(app: Application) {
        if (!initialized.compareAndSet(false, true)) return
        application = app
        gateway = RemoteMonitorGateway.get(app)
        renderer = MasterNotePageRenderer.get(app)
        monitoringEnabled.set(gateway.preferences().monitoringEnabled)
        baselineStore = SubmissionBaselineStore(File(app.noBackupFilesDir, "remote-monitor-submission-baseline"))
        enforceRemoteTeacherParentGate()
        LibraryAttemptBus.addListener(this)
        commandSubscription = gateway.subscribePendingScreenRequests(listener = ::queueCurrentPage)
        maintenanceSubscription = RemoteMonitorMaintenanceBus.install(
            object : RemoteMonitorMaintenanceBus.Handler {
                override fun pauseAndAwait(timeoutMillis: Long): Boolean {
                    val startedAt = SystemClock.elapsedRealtime()
                    if (!this@MasterNoteRemoteMonitorCoordinator.pauseAndAwait(timeoutMillis)) {
                        return false
                    }
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val remaining = (timeoutMillis - elapsed).coerceAtLeast(0L)
                    return MasterNoteRemoteReviewCoordinator.pauseAndAwait(remaining)
                }

                override fun onDataRootReplaced() {
                    this@MasterNoteRemoteMonitorCoordinator.resetSubmissionBaseline()
                    MasterNoteRemoteReviewCoordinator.onDataRootReplaced()
                }

                override fun resume() {
                    MasterNoteRemoteReviewCoordinator.resume()
                    this@MasterNoteRemoteMonitorCoordinator.resume()
                }
            },
        )
        preferenceSubscription = gateway.subscribePreferences { preferences ->
            if (enforceRemoteTeacherParentGate()) return@subscribePreferences
            if (preferences.monitoringEnabled) {
                if (!monitoringEnabled.getAndSet(true)) {
                    // Enabling again begins a new parent-facing session. Submissions made while
                    // monitoring was off must never be disclosed retroactively.
                    baselineStore?.reset(System.currentTimeMillis())
                    advanceWorkGeneration()
                } else {
                    baselineStore?.ensureBaseline(System.currentTimeMillis())
                }
                gateway.pendingScreenRequests().forEach(::queueCurrentPage)
                scanMissedSubmissions()
            } else {
                monitoringEnabled.set(false)
                advanceWorkGeneration()
                gateway.pendingParentMessage()?.let { gateway.acknowledgeParentMessage(it.updateId) }
                gateway.pendingScreenRequests().forEach { gateway.acknowledgeScreenRequest(it.updateId) }
            }
        }
    }

    override fun onLocalAttemptChanged(attempt: Attempt) {
        if (enforceRemoteTeacherParentGate()) return
        val lockedAt = attempt.lockedAtEpochMillis ?: return
        val session = currentRenderSession() ?: return
        if (!attempt.locked || lockedAt < session.baselineEpochMillis) return
        queueSubmission(attempt, session)
    }

    private fun pauseAndAwait(timeoutMillis: Long): Boolean {
        val barrier = CompletableFuture<Unit>()
        synchronized(maintenanceLock) {
            pausedForMaintenance = true
            advanceWorkGeneration()
            worker.execute(
                PrioritizedRenderTask(PRIORITY_MAINTENANCE, taskSequence.getAndIncrement()) {
                    barrier.complete(Unit)
                },
            )
        }
        return runCatching {
            barrier.get(timeoutMillis, TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    private fun resume() {
        synchronized(maintenanceLock) {
            pausedForMaintenance = false
            advanceWorkGeneration()
        }
        if (enforceRemoteTeacherParentGate()) return
        if (gateway.preferences().monitoringEnabled) {
            gateway.pendingScreenRequests().forEach(::queueCurrentPage)
            scanMissedSubmissions()
        }
    }

    fun resetSubmissionBaseline() {
        baselineStore?.reset(System.currentTimeMillis())
        advanceWorkGeneration()
    }

    private fun queueCurrentPage(command: PendingScreenRequest) {
        if (consumeScreenRequestForRemoteTeacher(command)) return
        val session = currentRenderSession() ?: run {
            // The remote-review role can change between the first policy read and session lookup.
            consumeScreenRequestForRemoteTeacher(command)
            return
        }
        if (command.chatId != session.chatId) return
        val scheduledKey = "${session.generation}:${command.requestId}"
        if (screenRequestAccountedFor(command) || !scheduledKeys.add(scheduledKey)) {
            if (screenRequestAccountedFor(command)) {
                gateway.acknowledgeScreenRequest(command.updateId)
            }
            return
        }
        val accepted = schedule(PRIORITY_CURRENT_PAGE) {
            try {
                if (!isRenderSessionCurrent(session)) {
                    consumeScreenRequestForRemoteTeacher(command)
                    return@schedule
                }
                if (screenRequestAccountedFor(command)) {
                    gateway.acknowledgeScreenRequest(command.updateId)
                    return@schedule
                }
                val bookId = command.bookId
                val pageNumber = command.pageNumber
                val result = if (
                    !command.active || bookId.isNullOrBlank() || pageNumber == null
                ) {
                    gateway.enqueueText(
                        idempotencyKey = "${command.requestId}:unavailable",
                        text = "현재 열린 문제집 화면이 없습니다. 학생이 문제집을 연 뒤 /화면을 다시 보내주세요.",
                        expectedChatId = command.chatId,
                    )
                } else {
                    renderAndEnqueue(
                        request = PageRenderRequest.currentPage(
                            requestId = command.requestId,
                            bookId = bookId,
                            pageNumber = pageNumber - 1,
                            attemptNo = command.attemptNo,
                        ),
                        idempotencyKey = command.requestId,
                        expectedChatId = command.chatId,
                        captionPrefix = "현재 화면",
                        session = session,
                    )
                }
                if (screenRequestAccountedFor(command)) {
                    gateway.acknowledgeScreenRequest(command.updateId)
                } else if (consumeScreenRequestForRemoteTeacher(command)) {
                    Unit
                } else if (result == TelegramEnqueueResult.QUEUE_FULL) {
                    scheduleScreenRetry(session)
                }
            } finally {
                scheduledKeys.remove(scheduledKey)
            }
        }
        if (!accepted) scheduledKeys.remove(scheduledKey)
    }

    /** Teacher-side bot-to-bot mode must never retain or execute human-parent `/화면` work. */
    private fun consumeScreenRequestForRemoteTeacher(command: PendingScreenRequest): Boolean {
        if (!enforceRemoteTeacherParentGate()) return false
        gateway.acknowledgeScreenRequest(command.updateId)
        return true
    }

    /**
     * Parent monitoring and teacher remote review share one durable Telegram outbox. Entering the
     * teacher role invalidates queued render work and consumes replayable parent commands exactly
     * once; every hot-path render check below still re-evaluates the role for race safety.
     */
    private fun enforceRemoteTeacherParentGate(): Boolean {
        val gate = parentMonitorRoleGate(gateway.remoteReviewPeerStatus())
        if (gate.allowsRendering) {
            if (remoteTeacherGateActive.getAndSet(false)) {
                // Never disclose attempts made while this installation was acting as the teacher
                // if parent monitoring is explicitly enabled again after a role change.
                baselineStore?.reset(System.currentTimeMillis())
                advanceWorkGeneration()
            }
            remoteTeacherCleanupScheduled.set(false)
            return false
        }
        if (remoteTeacherGateActive.compareAndSet(false, true)) {
            advanceWorkGeneration()
            baselineStore?.reset(System.currentTimeMillis())
            gateway.pendingParentMessage()?.let { gateway.acknowledgeParentMessage(it.updateId) }
            gateway.pendingScreenRequests().forEach { gateway.acknowledgeScreenRequest(it.updateId) }
        }
        scheduleRemoteTeacherCleanup()
        return true
    }

    /** Outbox cancellation can stop/join transport workers, so never perform it on a UI callback. */
    private fun scheduleRemoteTeacherCleanup() {
        if (!remoteTeacherCleanupScheduled.compareAndSet(false, true)) return
        retryScheduler.execute {
            runCatching { gateway.cancelParentRenderTrafficForRemoteTeacher() }
                .onFailure { remoteTeacherCleanupScheduled.set(false) }
        }
    }

    private fun screenRequestAccountedFor(command: PendingScreenRequest): Boolean =
        gateway.hasSeen(command.requestId) ||
            gateway.hasSeen("${command.requestId}:unavailable") ||
            gateway.hasSeen("${command.requestId}:render-error")

    private fun queueSubmission(attempt: Attempt, session: RenderSession): Boolean {
        if (!isRenderSessionCurrent(session)) return false
        if (submissionCapacityBlocked.get()) {
            scheduleSubmissionScanRetry(session)
            return false
        }
        val lockedAt = attempt.lockedAtEpochMillis ?: return true
        val requestId = submissionKey(attempt)
        if (lockedAt < session.baselineEpochMillis) return true
        val scheduledKey = "${session.generation}:$requestId"
        if (submissionAccountedFor(requestId) || !scheduledKeys.add(scheduledKey)) return true
        if (queuedSubmissionCount.incrementAndGet() > MAX_QUEUED_SUBMISSIONS) {
            queuedSubmissionCount.decrementAndGet()
            scheduledKeys.remove(scheduledKey)
            scheduleSubmissionScanRetry(session)
            return false
        }
        val accepted = schedule(PRIORITY_SUBMISSION) {
            try {
                if (!isRenderSessionCurrent(session) || submissionAccountedFor(requestId)) return@schedule
                val currentAttempt = LibraryRepository.get(application)
                    .attempts(attempt.bookId, attempt.pageNumber)
                    .firstOrNull {
                        it.attemptNo == attempt.attemptNo &&
                            it.locked &&
                            it.lockedAtEpochMillis == lockedAt
                    } ?: return@schedule
                if (!isRenderSessionCurrent(session) ||
                    (currentAttempt.lockedAtEpochMillis ?: Long.MIN_VALUE) < session.baselineEpochMillis
                ) return@schedule
                val request = PageRenderRequest.lockedSubmission(
                    requestId = requestId,
                    bookId = attempt.bookId,
                    pageNumber = attempt.pageNumber,
                    attemptNo = attempt.attemptNo,
                    lockedAtEpochMillis = lockedAt,
                )
                val result = renderAndEnqueue(
                    request = request,
                    idempotencyKey = requestId,
                    expectedChatId = session.chatId,
                    captionPrefix = "제출",
                    session = session,
                )
                if (result == TelegramEnqueueResult.QUEUE_FULL) {
                    submissionCapacityBlocked.set(true)
                    scheduleSubmissionScanRetry(session)
                }
            } finally {
                queuedSubmissionCount.decrementAndGet()
                scheduledKeys.remove(scheduledKey)
            }
        }
        if (!accepted) {
            queuedSubmissionCount.decrementAndGet()
            scheduledKeys.remove(scheduledKey)
        }
        return accepted
    }

    private fun renderAndEnqueue(
        request: PageRenderRequest,
        idempotencyKey: String,
        expectedChatId: Long?,
        captionPrefix: String,
        session: RenderSession,
    ): TelegramEnqueueResult {
        if (!isRenderSessionCurrent(session)) return TelegramEnqueueResult.NOT_CONFIGURED
        var renderedFile: File? = null
        return runCatching {
            val rendered = renderer.render(request, gateway.mediaDirectory)
            renderedFile = rendered.file
            if (!isRenderSessionCurrent(session)) {
                rendered.file.delete()
                return@runCatching TelegramEnqueueResult.NOT_CONFIGURED
            }
            val attemptLabel = request.attemptNo?.let { " · ${it}회" }.orEmpty()
            val caption = "$captionPrefix · ${rendered.studentDisplayName} · " +
                "${rendered.bookTitle} · ${request.pageNumber + 1}쪽$attemptLabel"
            gateway.enqueueDocument(
                idempotencyKey = idempotencyKey,
                document = rendered.file,
                caption = caption,
                mimeType = rendered.mimeType,
                displayName = rendered.displayFileName,
                expectedChatId = expectedChatId,
                deleteAfterSend = true,
            )
        }.fold(
            onSuccess = { result ->
                if (result != TelegramEnqueueResult.ENQUEUED) renderedFile?.delete()
                result
            },
            onFailure = {
                renderedFile?.delete()
                if (!isRenderSessionCurrent(session)) {
                    TelegramEnqueueResult.NOT_CONFIGURED
                } else gateway.enqueueText(
                    idempotencyKey = "$idempotencyKey:render-error",
                    text = if (request.purpose.name == "TELEGRAM_CURRENT_PAGE") {
                        "현재 시험지 화면을 만들지 못했습니다. 잠시 후 /화면을 다시 보내주세요."
                    } else {
                        "제출된 시험지 화면을 만들지 못했습니다. 태블릿에서 문제집 파일을 확인해 주세요."
                    },
                    expectedChatId = expectedChatId,
                )
            },
        )
    }

    private fun scanMissedSubmissions() {
        val session = currentRenderSession() ?: return
        if (!submissionScanGenerations.add(session.generation)) return
        val accepted = schedule(PRIORITY_SCAN) {
            try {
                if (!isRenderSessionCurrent(session)) return@schedule
                val repository = LibraryRepository.get(application)
                scan@ for (book in repository.state.books) {
                    if (!isRenderSessionCurrent(session)) break
                    for (attempt in repository.attemptsForSync(book.id)) {
                        val lockedAt = attempt.lockedAtEpochMillis
                        if (attempt.locked && lockedAt != null && lockedAt >= session.baselineEpochMillis &&
                            !queueSubmission(attempt, session)
                        ) {
                            break@scan
                        }
                    }
                }
            } finally {
                submissionScanGenerations.remove(session.generation)
            }
        }
        if (!accepted) submissionScanGenerations.remove(session.generation)
    }

    private fun scheduleScreenRetry(session: RenderSession) {
        if (!isRenderSessionCurrent(session) || !screenRetryGenerations.add(session.generation)) return
        retryScheduler.schedule(
            retry@{
                screenRetryGenerations.remove(session.generation)
                if (!isRenderSessionCurrent(session)) return@retry
                if (isRenderSessionCurrent(session)) {
                    gateway.pendingScreenRequests().forEach(::queueCurrentPage)
                }
            },
            QUEUE_FULL_RETRY_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleSubmissionScanRetry(session: RenderSession) {
        if (
            !isRenderSessionCurrent(session) ||
            !submissionRetryGenerations.add(session.generation)
        ) return
        retryScheduler.schedule(
            retry@{
                submissionRetryGenerations.remove(session.generation)
                if (!isRenderSessionCurrent(session)) return@retry
                submissionCapacityBlocked.set(false)
                if (isRenderSessionCurrent(session)) scanMissedSubmissions()
            },
            QUEUE_FULL_RETRY_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun submissionKey(attempt: Attempt): String =
        "submission:${attempt.bookId}:${attempt.pageNumber}:${attempt.attemptNo}:${attempt.lockedAtEpochMillis}"

    private fun submissionAccountedFor(requestId: String): Boolean =
        gateway.hasSeen(requestId) || gateway.hasSeen("$requestId:render-error")

    private fun schedule(priority: Int, block: () -> Unit): Boolean {
        synchronized(maintenanceLock) {
            if (pausedForMaintenance) return false
            worker.execute(PrioritizedRenderTask(priority, taskSequence.getAndIncrement(), block))
            return true
        }
    }

    private fun currentRenderSession(): RenderSession? {
        repeat(3) {
            val generationBefore = workGeneration.get()
            if (!monitoringEnabled.get() || !gateway.preferences().monitoringEnabled) return null
            if (enforceRemoteTeacherParentGate()) return null
            if (synchronized(maintenanceLock) { pausedForMaintenance }) return null
            val chatId = gateway.configuredChatId() ?: return null
            val baseline = baselineStore?.baseline() ?: return null
            if (generationBefore == workGeneration.get()) {
                return RenderSession(generationBefore, chatId, baseline)
            }
        }
        return null
    }

    private fun isRenderSessionCurrent(session: RenderSession): Boolean =
        session.generation == workGeneration.get() &&
            monitoringEnabled.get() &&
            gateway.preferences().monitoringEnabled &&
            parentMonitorRoleGate(gateway.remoteReviewPeerStatus()).allowsRendering &&
            gateway.configuredChatId() == session.chatId &&
            baselineStore?.baseline() == session.baselineEpochMillis &&
            !synchronized(maintenanceLock) { pausedForMaintenance }

    private fun advanceWorkGeneration() {
        workGeneration.incrementAndGet()
        submissionCapacityBlocked.set(false)
    }

    // Maintenance is deliberately last: pausing rejects new work, then this FIFO barrier runs
    // only after every already-accepted render has released repository/PDF resources.
    private const val PRIORITY_MAINTENANCE = 100
    private const val PRIORITY_CURRENT_PAGE = 0
    private const val PRIORITY_SCAN = 10
    private const val PRIORITY_SUBMISSION = 20
    private const val QUEUE_FULL_RETRY_MILLIS = 30_000L
    private const val MAX_QUEUED_SUBMISSIONS = 8
}

internal enum class ParentMonitorRoleGate(
    val allowsRendering: Boolean,
    val consumePendingScreenRequests: Boolean,
) {
    ALLOW(allowsRendering = true, consumePendingScreenRequests = false),
    BLOCK_REMOTE_TEACHER(allowsRendering = false, consumePendingScreenRequests = true),
}

/**
 * Parent monitoring and bot-to-bot teacher review share one Telegram gateway, but only the student
 * device may produce parent-facing submission/screen renders. WaitingForStudentAck is also a
 * teacher-owned state, so work is blocked before the pairing handshake finishes.
 */
internal fun parentMonitorRoleGate(status: RemoteReviewPeerStatus): ParentMonitorRoleGate = when (status) {
    is RemoteReviewPeerStatus.WaitingForStudentAck -> ParentMonitorRoleGate.BLOCK_REMOTE_TEACHER
    is RemoteReviewPeerStatus.Connected -> if (status.role == RemoteReviewRole.TEACHER) {
        ParentMonitorRoleGate.BLOCK_REMOTE_TEACHER
    } else {
        ParentMonitorRoleGate.ALLOW
    }
    else -> ParentMonitorRoleGate.ALLOW
}

private data class RenderSession(
    val generation: Long,
    val chatId: Long,
    val baselineEpochMillis: Long,
)

private class PrioritizedRenderTask(
    private val priority: Int,
    private val sequence: Long,
    private val action: () -> Unit,
) : Runnable, Comparable<PrioritizedRenderTask> {
    override fun run() = action()

    override fun compareTo(other: PrioritizedRenderTask): Int =
        compareValuesBy(this, other, PrioritizedRenderTask::priority, PrioritizedRenderTask::sequence)
}

private class SubmissionBaselineStore(private val file: File) {
    private val lock = Any()

    fun ensureBaseline(nowEpochMillis: Long): Long = synchronized(lock) {
        baseline() ?: nowEpochMillis.also(::write)
    }

    fun baseline(): Long? = synchronized(lock) {
        runCatching { file.readText().trim().toLong() }.getOrNull()?.takeIf { it >= 0L }
    }

    fun reset(nowEpochMillis: Long) = synchronized(lock) { write(nowEpochMillis) }

    private fun write(value: Long) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { output ->
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) error("Cannot replace submission baseline")
        if (!temporary.renameTo(file)) error("Cannot commit submission baseline")
    }
}
