package com.studyink.library.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.studyink.backup.storage.BackupInspection
import com.studyink.backup.storage.BackupResult
import com.studyink.backup.storage.MasterNoteBackupManager
import com.studyink.backup.storage.RestoreResult
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.monitor.core.RemoteMonitorMaintenanceBus
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MasterNoteBackupStatus(
    val dirty: Boolean,
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val lastBackupAtEpochMillis: Long? = null,
    val lastBackupName: String? = null,
    val message: String? = null,
    val error: String? = null,
    val restoreRevision: Long = 0L,
) {
    val busy: Boolean get() = isBackingUp || isRestoring
}

/**
 * Application-scoped bridge between durable data commits, automatic background backups, and the
 * manual controls in [LibraryActivity]. Backup and restore calls are kept in one coroutine mutex;
 * the storage layer also owns a process-wide lock as a second line of defence.
 */
object MasterNoteBackupCoordinator {
    private val initializeLock = Any()
    private val statusLock = Any()
    private val listeners = CopyOnWriteArraySet<(MasterNoteBackupStatus) -> Unit>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var appInForeground = false

    @Volatile
    private var pendingAutomaticBackup: Job? = null

    @Volatile
    private var latestObservedGeneration = 0L

    private lateinit var appContext: Context
    @SuppressLint("StaticFieldLeak") // The manager normalizes its constructor input to applicationContext.
    private lateinit var manager: MasterNoteBackupManager
    private lateinit var preferences: android.content.SharedPreferences
    private var commitSubscription: AutoCloseable? = null
    private var status = MasterNoteBackupStatus(dirty = true)

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(initializeLock) {
            if (initialized) return
            appContext = context.applicationContext
            // This runs from Application.onCreate, before LibraryActivity can initialize the
            // catalog. Any interrupted atomic restore is therefore resolved against raw files.
            MasterNoteBackupManager.recoverInterruptedRestore(appContext)
            manager = MasterNoteBackupManager.get(appContext)
            preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            latestObservedGeneration = MasterNoteDataCommitBus.currentGeneration()
            status = MasterNoteBackupStatus(
                dirty = preferences.getBoolean(KEY_DIRTY, true),
                lastBackupAtEpochMillis = preferences.getLong(KEY_LAST_BACKUP_AT, 0L).takeIf { it > 0L },
                lastBackupName = preferences.getString(KEY_LAST_BACKUP_NAME, null),
            )
            commitSubscription = MasterNoteDataCommitBus.addListener(::onDurableCommit)
            initialized = true

            // SharedPreferences is the primary crash-safe dirty marker. The timestamp scan also
            // catches the very small window where the process dies before apply() reaches disk.
            scope.launch { detectChangesNewerThanLastBackup() }
        }
    }

    fun onAppForeground() {
        appInForeground = true
        pendingAutomaticBackup?.cancel()
        pendingAutomaticBackup = null
    }

    fun onAppBackground() {
        appInForeground = false
        scheduleAutomaticBackupIfNeeded()
    }

    fun currentStatus(): MasterNoteBackupStatus = synchronized(statusLock) { status }

    fun addListener(listener: (MasterNoteBackupStatus) -> Unit): AutoCloseable {
        listeners += listener
        mainHandler.post { if (listeners.contains(listener)) listener(currentStatus()) }
        return AutoCloseable { listeners -= listener }
    }

    suspend fun createManualBackup(protectedUri: Uri? = null): BackupResult {
        check(initialized) { "MasterNoteBackupCoordinator is not initialized." }
        pendingAutomaticBackup?.cancel()
        pendingAutomaticBackup = null
        // The operation belongs to the Application scope. Rotating or closing an Activity may
        // cancel its await, but can never interrupt archive publication half way through.
        return scope.async {
            operationMutex.withLock { performBackup(automatic = false, protectedUri = protectedUri) }
        }.await()
    }

    suspend fun inspect(uri: Uri): BackupInspection {
        check(initialized) { "MasterNoteBackupCoordinator is not initialized." }
        return scope.async {
            operationMutex.withLock { manager.inspect(uri) }
        }.await()
    }

    suspend fun restoreReplace(uri: Uri): RestoreResult {
        check(initialized) { "MasterNoteBackupCoordinator is not initialized." }
        pendingAutomaticBackup?.cancel()
        pendingAutomaticBackup = null
        return scope.async {
            operationMutex.withLock {
                val capturedGeneration = synchronized(statusLock) {
                    max(latestObservedGeneration, MasterNoteDataCommitBus.currentGeneration())
                }
                updateStatus { it.copy(isRestoring = true, message = "백업을 복원하는 중…", error = null) }
                val result = manager.restoreReplace(uri)
                when (result) {
                    is RestoreResult.Success -> {
                        // The LibraryActivity has already paused both remote render workers. Drop
                        // pre-restore page bindings before either worker is allowed to resume.
                        RemoteMonitorMaintenanceBus.onDataRootReplaced()
                        val snapshot = synchronized(statusLock) {
                            val newestGeneration = max(
                                latestObservedGeneration,
                                MasterNoteDataCommitBus.currentGeneration(),
                            )
                            val stillDirty = newestGeneration > capturedGeneration
                            preferences.edit()
                                .putBoolean(KEY_DIRTY, stillDirty)
                                .putLong(KEY_LAST_BACKED_GENERATION, capturedGeneration)
                                .apply()
                            status.copy(
                                dirty = stillDirty,
                                isRestoring = false,
                                message = "복원이 끝났습니다. 화면을 다시 불러옵니다.",
                                error = null,
                                restoreRevision = status.restoreRevision + 1L,
                            ).also { status = it }
                        }
                        publishStatus(snapshot)
                    }

                    is RestoreResult.Failure -> updateStatus {
                        it.copy(
                            isRestoring = false,
                            message = null,
                            error = result.message,
                        )
                    }
                }
                result
            }
        }.await()
    }

    private fun onDurableCommit(generation: Long) {
        val snapshot = synchronized(statusLock) {
            latestObservedGeneration = max(latestObservedGeneration, generation)
            preferences.edit()
                .putBoolean(KEY_DIRTY, true)
                .putLong(KEY_LAST_OBSERVED_GENERATION, latestObservedGeneration)
                .apply()
            status.copy(dirty = true, message = null).also { status = it }
        }
        publishStatus(snapshot)
        if (!appInForeground) scheduleAutomaticBackupIfNeeded()
    }

    private fun scheduleAutomaticBackupIfNeeded() {
        if (!initialized || appInForeground || !currentStatus().dirty) return
        pendingAutomaticBackup?.cancel()
        pendingAutomaticBackup = scope.launch {
            delay(AUTOMATIC_BACKUP_QUIET_MILLIS)
            if (appInForeground || !currentStatus().dirty) return@launch
            // From this point the archive publication is an in-flight durable operation, not a
            // cancelable debounce. Foregrounding the app may cancel only a future delay job.
            pendingAutomaticBackup = null
            operationMutex.withLock {
                if (!appInForeground && currentStatus().dirty) {
                    performBackup(automatic = true, protectedUri = null)
                }
            }
        }
    }

    private suspend fun performBackup(automatic: Boolean, protectedUri: Uri?): BackupResult {
        val capturedGeneration = synchronized(statusLock) {
            max(latestObservedGeneration, MasterNoteDataCommitBus.currentGeneration())
        }
        updateStatus {
            it.copy(
                isBackingUp = true,
                message = if (automatic) "변경 내용을 자동 백업하는 중…" else "백업하는 중…",
                error = null,
            )
        }
        val result = withContext(Dispatchers.IO) {
            manager.createPublicBackup(capturedGeneration, protectedUri)
        }
        when (result) {
            is BackupResult.Success -> {
                val snapshot = synchronized(statusLock) {
                    // The generation read, dirty decision, preferences write, and status update
                    // share the commit-listener lock. A commit can therefore land either wholly
                    // before this decision (remaining dirty) or wholly after it (marking dirty).
                    val newestGeneration = max(
                        latestObservedGeneration,
                        MasterNoteDataCommitBus.currentGeneration(),
                    )
                    val stillDirty = newestGeneration > capturedGeneration
                    preferences.edit()
                        .putBoolean(KEY_DIRTY, stillDirty)
                        .putLong(KEY_LAST_BACKUP_AT, result.inspection.createdAtEpochMillis)
                        .putString(KEY_LAST_BACKUP_NAME, result.displayName)
                        .putLong(KEY_LAST_BACKED_GENERATION, capturedGeneration)
                        .apply()
                    status.copy(
                        dirty = stillDirty,
                        isBackingUp = false,
                        lastBackupAtEpochMillis = result.inspection.createdAtEpochMillis,
                        lastBackupName = result.displayName,
                        message = if (automatic) "변경 내용이 자동 백업되었습니다." else "백업이 완료되었습니다.",
                        error = null,
                    ).also { status = it }
                }
                publishStatus(snapshot)
                val stillDirty = snapshot.dirty
                if (stillDirty && !appInForeground) scheduleAutomaticBackupIfNeeded()
            }

            is BackupResult.Failure -> updateStatus {
                it.copy(
                    dirty = true,
                    isBackingUp = false,
                    message = null,
                    error = result.message,
                )
            }
        }
        return result
    }

    private suspend fun detectChangesNewerThanLastBackup() {
        val lastBackupAt = currentStatus().lastBackupAtEpochMillis ?: 0L
        val liveRoot = File(appContext.filesDir, DATA_DIRECTORY)
        val latestWrite = runCatching {
            if (!liveRoot.exists()) 0L else liveRoot.walkTopDown()
                .filter(File::isFile)
                .maxOfOrNull(File::lastModified) ?: 0L
        }.getOrDefault(0L)
        val snapshot = synchronized(statusLock) {
            val currentLastBackup = status.lastBackupAtEpochMillis ?: 0L
            if (currentLastBackup <= lastBackupAt && (lastBackupAt == 0L || latestWrite > currentLastBackup)) {
                preferences.edit().putBoolean(KEY_DIRTY, true).apply()
                status.copy(dirty = true).also { status = it }
            } else {
                null
            }
        }
        if (snapshot != null) {
            publishStatus(snapshot)
            if (!appInForeground) scheduleAutomaticBackupIfNeeded()
        }
    }

    private fun updateStatus(transform: (MasterNoteBackupStatus) -> MasterNoteBackupStatus) {
        val snapshot = synchronized(statusLock) {
            transform(status).also { status = it }
        }
        publishStatus(snapshot)
    }

    private fun publishStatus(snapshot: MasterNoteBackupStatus) {
        mainHandler.post {
            listeners.forEach { listener -> runCatching { listener(snapshot) } }
        }
    }

    private const val AUTOMATIC_BACKUP_QUIET_MILLIS = 2_500L
    private const val DATA_DIRECTORY = "masternote"
    private const val PREFERENCES_NAME = "masternote-backup-coordinator"
    private const val KEY_DIRTY = "dirty"
    private const val KEY_LAST_BACKUP_AT = "lastBackupAt"
    private const val KEY_LAST_BACKUP_NAME = "lastBackupName"
    private const val KEY_LAST_OBSERVED_GENERATION = "lastObservedGeneration"
    private const val KEY_LAST_BACKED_GENERATION = "lastBackedGeneration"
}
