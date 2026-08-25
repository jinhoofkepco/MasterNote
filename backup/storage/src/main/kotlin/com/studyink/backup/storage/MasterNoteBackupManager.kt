package com.studyink.backup.storage

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.core.model.MasterNoteDataRootBus
import com.studyink.library.data.LibraryRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class BackupInspection(
    val createdAtEpochMillis: Long,
    val sourcePackageName: String,
    val sourceVersionName: String,
    val sourceVersionCode: Long,
    val sourceGeneration: Long,
    val sourceDeviceId: String,
    val identityMatchesThisDevice: Boolean,
    val fileCount: Int,
    val totalBytes: Long,
)

sealed interface BackupResult {
    data class Success(
        val uri: Uri,
        val displayName: String,
        val inspection: BackupInspection,
    ) : BackupResult

    data class Failure(
        val message: String,
        val cause: Throwable,
    ) : BackupResult
}

sealed interface RestoreResult {
    data class Success(
        val inspection: BackupInspection,
        val deviceIdentityRestored: Boolean,
    ) : RestoreResult

    data class Failure(
        val message: String,
        val cause: Throwable,
    ) : RestoreResult
}

/**
 * Creates uninstall-safe backups in Downloads/MasterNote Backups and restores a selected backup.
 *
 * All methods perform disk I/O and must be called from a worker thread. Backup and restore are
 * serialized process-wide so an automatic backup cannot overlap a manual backup or restore.
 */
class MasterNoteBackupManager private constructor(context: Context) {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    init {
        synchronized(OPERATION_LOCK) { recoverInterruptedRestore() }
    }

    /**
     * Captures a stable snapshot and publishes it through MediaStore only after re-validation.
     * [sourceGeneration] is caller-owned metadata useful for deciding whether another backup is
     * needed; it does not affect archive validation. When [protectedUri] is supplied, retention
     * deletion is skipped for this run because SAF and MediaStore may represent one file with
     * different URIs. This lets restore create a safety backup without deleting its source.
     */
    fun createPublicBackup(
        sourceGeneration: Long = 0L,
        protectedUri: Uri? = null,
    ): BackupResult = synchronized(OPERATION_LOCK) {
        require(sourceGeneration >= 0L) { "sourceGeneration must not be negative" }
        var work: File? = null
        var mediaUri: Uri? = null
        try {
            val currentWork = newWorkDirectory("create").also { work = it }
            val archiveRoot = File(currentWork, "archive").apply { check(mkdirs()) }
            val dataRoot = File(archiveRoot, "data")
            val library = LibraryRepository.get(context)
            val annotationStore = PageOperationLogStore.get(context)
            val sourceDeviceId = library.withStableDataRoot { liveRoot ->
                annotationStore.withStableDataRoot {
                    copyStableDataRoot(liveRoot, dataRoot)
                    library.deviceId
                }
            }
            BackupArchive.populateMissingCatalogHashes(dataRoot)
            val identity = ArchiveIdentity(sourceDeviceId, currentAndroidIdFingerprint())
            BackupArchive.writeIdentity(File(archiveRoot, IDENTITY_PATH), identity)
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val createdAt = System.currentTimeMillis()
            BackupArchive.writeManifest(
                archiveRoot = archiveRoot,
                createdAtEpochMillis = createdAt,
                sourcePackageName = context.packageName,
                sourceVersionName = packageInfo.versionName.orEmpty(),
                sourceVersionCode = packageInfo.longVersionCode,
                sourceGeneration = sourceGeneration,
            )

            val displayName = backupDisplayName(createdAt)
            mediaUri = createPendingDownload(displayName)
            resolver.openOutputStream(mediaUri, "w").use { output ->
                requireNotNull(output) { "백업 파일을 열 수 없습니다." }
                BackupArchive.writeZip(archiveRoot, output)
            }

            val validated = resolver.openInputStream(mediaUri).use { input ->
                requireNotNull(input) { "작성한 백업을 다시 읽을 수 없습니다." }
                BackupArchive.validate(input)
            }
            publishDownload(mediaUri)
            runCatching { rememberAndPruneOwnedBackups(mediaUri, protectedUri) }
            val inspection = validated.toInspection()
            BackupResult.Success(mediaUri, displayName, inspection)
        } catch (error: Throwable) {
            mediaUri?.let { runCatching { resolver.delete(it, null, null) } }
            BackupResult.Failure(error.userMessage("백업을 만들지 못했습니다."), error)
        } finally {
            work?.let(::deleteWorkDirectory)
        }
    }

    /** Fully validates paths, limits, structure, file sizes, and SHA-256 hashes without restoring. */
    fun inspect(uri: Uri): BackupInspection = synchronized(OPERATION_LOCK) {
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "선택한 백업을 읽을 수 없습니다." }
            BackupArchive.validate(input).toInspection()
        }
    }

    /**
     * Validates into an isolated staging directory, then atomically swaps the entire data root.
     * The previous root is retained as a rollback directory until the swap and identity commit
     * both succeed. The caller must stop LAN sync and recreate its activities after success.
     */
    fun restoreReplace(uri: Uri): RestoreResult = synchronized(OPERATION_LOCK) {
        val token = UUID.randomUUID().toString()
        val restoreStagingRoot = File(context.filesDir, "masternote.restore-stage-$token")
        val incomingRoot = File(restoreStagingRoot, "data")
        val rollbackRoot = File(context.filesDir, "masternote.restore-rollback-$token")
        var oldRootMoved = false
        var newRootInstalled = false
        val preferences = context.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE)
        val previousDeviceId = preferences.getString(DEVICE_ID_KEY, null)
        try {
            val preflight = resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "선택한 백업을 읽을 수 없습니다." }
                BackupArchive.validate(input)
            }
            ensureRestoreSpace(preflight.totalBytes)
            val validated = resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "선택한 백업을 읽을 수 없습니다." }
                BackupArchive.validate(input, restoreStagingRoot, maximumTotalBytes = preflight.totalBytes)
            }
            if (validated.manifest != preflight.manifest || validated.identity != preflight.identity) {
                throw BackupValidationException("검사하는 동안 선택한 백업 파일이 변경되었습니다.")
            }
            check(incomingRoot.isDirectory) { "백업에 MasterNote 데이터가 없습니다." }
            check(!rollbackRoot.exists()) { "복원 임시 경로가 이미 존재합니다." }

            val fingerprintMatches = validated.identity.androidIdSha256 != null &&
                validated.identity.androidIdSha256 == currentAndroidIdFingerprint()
            val inspection = validated.toInspection()
            val library = LibraryRepository.get(context)
            val annotationStore = PageOperationLogStore.get(context)
            library.withStableDataRoot { liveRoot ->
                annotationStore.withStableDataRoot {
                    val expectedRoot = File(context.filesDir, "masternote").canonicalFile
                    check(liveRoot.canonicalFile == expectedRoot) { "예상하지 못한 데이터 경로입니다." }
                    try {
                        check(liveRoot.renameTo(rollbackRoot)) { "기존 데이터를 복원 대기 폴더로 옮기지 못했습니다." }
                        oldRootMoved = true
                        check(incomingRoot.renameTo(liveRoot)) { "복원 데이터를 확정하지 못했습니다." }
                        newRootInstalled = true
                        if (fingerprintMatches) {
                            check(preferences.edit().putString(DEVICE_ID_KEY, validated.identity.deviceId).commit()) {
                                "복원 기기 정보를 저장하지 못했습니다."
                            }
                        }
                        // Drop all in-memory catalogs and annotation indexes before the old locks
                        // are released. New callers will therefore open the newly installed root.
                        PageOperationLogStore.resetForRestore()
                        LibraryRepository.resetForRestore()
                        MasterNoteDataRootBus.dataRootReplaced()
                    } catch (error: Throwable) {
                        if (newRootInstalled && liveRoot.exists()) deleteTreeChecked(liveRoot, context.filesDir)
                        if (oldRootMoved && rollbackRoot.exists()) {
                            check(rollbackRoot.renameTo(liveRoot)) { "복원 실패 후 기존 데이터도 되돌리지 못했습니다." }
                        }
                        restorePreviousDeviceId(preferences, previousDeviceId)
                        PageOperationLogStore.resetForRestore()
                        LibraryRepository.resetForRestore()
                        MasterNoteDataRootBus.dataRootReplaced()
                        throw error
                    }
                }
            }

            // Restoration is already committed. A cleanup failure must not be reported as a
            // restore failure; the uniquely named rollback directory is recoverable old data.
            if (rollbackRoot.exists()) runCatching { deleteTreeChecked(rollbackRoot, context.filesDir) }
            RestoreResult.Success(inspection, deviceIdentityRestored = fingerprintMatches)
        } catch (error: Throwable) {
            RestoreResult.Failure(error.userMessage("백업을 복원하지 못했습니다."), error)
        } finally {
            if (restoreStagingRoot.exists()) {
                runCatching { deleteTreeChecked(restoreStagingRoot, context.filesDir) }
            }
        }
    }

    private fun ValidatedArchive.toInspection() = BackupInspection(
        createdAtEpochMillis = manifest.createdAtEpochMillis,
        sourcePackageName = manifest.sourcePackageName,
        sourceVersionName = manifest.sourceVersionName,
        sourceVersionCode = manifest.sourceVersionCode,
        sourceGeneration = manifest.sourceGeneration,
        sourceDeviceId = identity.deviceId,
        identityMatchesThisDevice = identity.androidIdSha256 != null &&
            identity.androidIdSha256 == currentAndroidIdFingerprint(),
        fileCount = manifest.files.size,
        totalBytes = totalBytes,
    )

    private fun createPendingDownload(displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, BACKUP_MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_DIRECTORY")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "다운로드 폴더에 백업 파일을 만들 수 없습니다."
        }
    }

    private fun publishDownload(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        check(resolver.update(uri, values, null, null) == 1) { "백업 파일을 공개하지 못했습니다." }
    }

    /** Retention is intentionally limited to rows recorded by this exact installation. */
    @SuppressLint("ApplySharedPref") // Retention ownership must survive a process death immediately after publish.
    private fun rememberAndPruneOwnedBackups(newUri: Uri, protectedUri: Uri?) {
        val preferences = context.getSharedPreferences(RETENTION_PREFERENCES, Context.MODE_PRIVATE)
        val tracked = preferences.getStringSet(RETAINED_URI_KEY, emptySet()).orEmpty().toMutableSet()
        tracked += newUri.toString()
        // SAF and MediaStore can expose the same Downloads row through different URI shapes.
        // During a pre-restore safety backup, skip deletion altogether; a later ordinary backup
        // will safely converge the retained set to three files.
        if (protectedUri != null) {
            preferences.edit().putStringSet(RETAINED_URI_KEY, tracked).commit()
            return
        }
        val verified = tracked.mapNotNull { rawUri -> queryTrackedBackup(Uri.parse(rawUri)) }
        val newestOthers = verified
            .filterNot { it.uri == newUri }
            .sortedWith(compareByDescending<OwnedBackupRow> { it.dateAddedSeconds }.thenByDescending { it.uri.toString() })
            .take(MAX_RETAINED_BACKUPS - 1)
        val keep = (listOfNotNull(verified.firstOrNull { it.uri == newUri }) + newestOthers)
            .mapTo(mutableSetOf()) { it.uri.toString() }

        verified.filter { it.uri.toString() !in keep }.forEach { old ->
            if (resolver.delete(old.uri, null, null) != 1) keep += old.uri.toString()
        }
        preferences.edit().putStringSet(RETAINED_URI_KEY, keep).commit()
    }

    private fun queryTrackedBackup(uri: Uri): OwnedBackupRow? {
        if (uri.scheme != "content") return null
        val projection = arrayOf(
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.DATE_ADDED,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
            val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH))
            val expectedPath = "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_DIRECTORY"
            if (!displayName.startsWith("MasterNote_") || !displayName.endsWith(".mnbak.zip") ||
                relativePath.trimEnd('/') != expectedPath
            ) return@use null
            OwnedBackupRow(
                uri = uri,
                dateAddedSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)),
            )
        }
    }

    @SuppressLint("HardwareIds") // Only a one-way hash is archived, to prevent cross-device ID cloning.
    private fun currentAndroidIdFingerprint(): String? {
        val raw = Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)
            ?.takeIf(String::isNotBlank) ?: return null
        return BackupArchive.sha256(raw.toByteArray(Charsets.UTF_8))
    }

    private fun copyStableDataRoot(sourceRoot: File, destinationRoot: File) {
        val canonicalSource = sourceRoot.canonicalFile
        check(canonicalSource.isDirectory) { "MasterNote 데이터 폴더가 없습니다." }
        copyDirectory(
            source = canonicalSource,
            destination = destinationRoot,
            sourceBoundary = canonicalSource,
            excludeTemporaryFiles = true,
        )
    }

    private fun copyDirectory(
        source: File,
        destination: File,
        sourceBoundary: File,
        excludeTemporaryFiles: Boolean,
    ) {
        if (Files.isSymbolicLink(source.toPath())) error("심볼릭 링크는 백업할 수 없습니다: ${source.name}")
        val canonical = source.canonicalFile
        val boundaryPrefix = sourceBoundary.path + File.separator
        check(canonical == sourceBoundary || canonical.path.startsWith(boundaryPrefix)) {
            "데이터 경로가 MasterNote 폴더를 벗어났습니다."
        }
        if (excludeTemporaryFiles && shouldExclude(source.name)) return
        if (source.isDirectory) {
            check(destination.mkdirs() || destination.isDirectory) { "백업 임시 폴더를 만들 수 없습니다." }
            source.listFiles()?.sortedBy(File::getName)?.forEach { child ->
                copyDirectory(child, File(destination, child.name), sourceBoundary, excludeTemporaryFiles)
            } ?: error("데이터 폴더를 읽을 수 없습니다: ${source.name}")
        } else {
            check(source.isFile) { "지원하지 않는 데이터 항목입니다: ${source.name}" }
            destination.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (source.lastModified() > 0L) destination.setLastModified(source.lastModified())
        }
    }

    private fun shouldExclude(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".staging") || lower.endsWith(".tmp") || lower.endsWith(".new") ||
            lower.endsWith(".bak") || lower.contains(".corrupt") || lower == "staging" ||
            lower == "tmp" || lower == "corrupt"
    }

    private fun ensureRestoreSpace(uncompressedBytes: Long) {
        val safetyMargin = maxOf(MINIMUM_RESTORE_MARGIN_BYTES, uncompressedBytes / 20L)
        val required = if (Long.MAX_VALUE - uncompressedBytes < safetyMargin) {
            Long.MAX_VALUE
        } else {
            uncompressedBytes + safetyMargin
        }
        val storageManager = context.getSystemService(StorageManager::class.java)
        val storageUuid = storageManager.getUuidForPath(context.filesDir)
        if (storageManager.getAllocatableBytes(storageUuid) < required) {
            throw BackupValidationException("복원 공간이 부족합니다. 최소 ${required / (1024L * 1024L)}MB의 여유 공간이 필요합니다.")
        }
        storageManager.allocateBytes(storageUuid, required)
    }

    /**
     * Resolves the only non-atomic gap (old-root rename followed by new-root rename) after a
     * process/device crash. If the new root was not installed, the old root always wins.
     */
    private fun recoverInterruptedRestore() {
        val filesRoot = context.filesDir
        val liveRoot = File(filesRoot, "masternote")
        val rollbackRoots = filesRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(RESTORE_ROLLBACK_PREFIX) }
            .sortedByDescending(File::lastModified)

        if (!liveRoot.exists() && rollbackRoots.isNotEmpty()) {
            val rollback = rollbackRoots.first()
            check(rollback.renameTo(liveRoot)) { "중단된 복원에서 기존 데이터를 되돌리지 못했습니다." }
            val token = rollback.name.removePrefix(RESTORE_ROLLBACK_PREFIX)
            File(filesRoot, "$RESTORE_STAGE_PREFIX$token").takeIf(File::exists)?.let {
                runCatching { deleteTreeChecked(it, filesRoot) }
            }
            File(filesRoot, "$LEGACY_RESTORE_NEW_PREFIX$token").takeIf(File::exists)?.let {
                runCatching { deleteTreeChecked(it, filesRoot) }
            }
            PageOperationLogStore.resetForRestore()
            LibraryRepository.resetForRestore()
            MasterNoteDataRootBus.dataRootReplaced()
            return
        }

        if (!liveRoot.isDirectory) return
        rollbackRoots.forEach { rollback ->
            val token = rollback.name.removePrefix(RESTORE_ROLLBACK_PREFIX)
            val stage = File(filesRoot, "$RESTORE_STAGE_PREFIX$token")
            // The validated stage/data directory disappears only when it has been renamed into
            // the live location, so this is an interrupted successful commit.
            if (stage.isDirectory && !File(stage, "data").exists()) {
                restoreIdentityFromCommittedStage(stage)
                runCatching { deleteTreeChecked(rollback, filesRoot) }
                runCatching { deleteTreeChecked(stage, filesRoot) }
                PageOperationLogStore.resetForRestore()
                LibraryRepository.resetForRestore()
                MasterNoteDataRootBus.dataRootReplaced()
            }
        }
        // A stage without a rollback never crossed the destructive rename boundary.
        filesRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(RESTORE_STAGE_PREFIX) }
            .filter { stage ->
                val token = stage.name.removePrefix(RESTORE_STAGE_PREFIX)
                !File(filesRoot, "$RESTORE_ROLLBACK_PREFIX$token").exists()
            }
            .forEach { stage -> runCatching { deleteTreeChecked(stage, filesRoot) } }
        filesRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(LEGACY_RESTORE_NEW_PREFIX) }
            .forEach { stale -> runCatching { deleteTreeChecked(stale, filesRoot) } }
    }

    @SuppressLint("ApplySharedPref") // Crash recovery must durably commit identity before deleting rollback data.
    private fun restoreIdentityFromCommittedStage(stage: File) {
        val identity = runCatching { BackupArchive.readIdentity(File(stage, IDENTITY_PATH)) }.getOrNull() ?: return
        if (identity.androidIdSha256 == null || identity.androidIdSha256 != currentAndroidIdFingerprint()) return
        context.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(DEVICE_ID_KEY, identity.deviceId)
            .commit()
    }

    private fun newWorkDirectory(label: String): File {
        val parent = File(context.cacheDir, WORK_DIRECTORY).apply { check(mkdirs() || isDirectory) }
        return File(parent, "$label-${UUID.randomUUID()}").apply { check(mkdir()) }
    }

    private fun deleteWorkDirectory(directory: File) {
        if (!directory.exists()) return
        runCatching { deleteTreeChecked(directory, File(context.cacheDir, WORK_DIRECTORY)) }
    }

    private fun deleteTreeChecked(target: File, allowedParent: File) {
        val canonicalTarget = target.canonicalFile
        val canonicalParent = allowedParent.canonicalFile
        check(canonicalTarget != canonicalParent && canonicalTarget.path.startsWith(canonicalParent.path + File.separator)) {
            "삭제 경로가 허용된 임시 폴더를 벗어났습니다."
        }
        check(canonicalTarget.deleteRecursively()) { "임시 데이터를 정리하지 못했습니다: ${canonicalTarget.name}" }
    }

    private fun restorePreviousDeviceId(
        preferences: android.content.SharedPreferences,
        previousDeviceId: String?,
    ) {
        val editor = preferences.edit()
        if (previousDeviceId == null) editor.remove(DEVICE_ID_KEY) else editor.putString(DEVICE_ID_KEY, previousDeviceId)
        check(editor.commit()) { "기존 기기 정보를 되돌리지 못했습니다." }
    }

    private fun backupDisplayName(createdAt: Long): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        return "MasterNote_${formatter.format(Date(createdAt))}.mnbak.zip"
    }

    private fun Throwable.userMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank) ?: fallback

    companion object {
        @SuppressLint("StaticFieldLeak") // The manager stores only context.applicationContext.
        @Volatile
        private var instance: MasterNoteBackupManager? = null
        private val OPERATION_LOCK = Any()
        private const val COPY_BUFFER_BYTES = 128 * 1024
        private const val WORK_DIRECTORY = "masternote-backup-work"
        private const val BACKUP_DIRECTORY = "MasterNote Backups"
        private const val BACKUP_MIME_TYPE = "application/zip"
        private const val DEVICE_PREFERENCES = "masternote-device"
        private const val DEVICE_ID_KEY = "deviceId"
        private const val RETENTION_PREFERENCES = "masternote-backup-retention"
        private const val RETAINED_URI_KEY = "publishedUris"
        private const val MAX_RETAINED_BACKUPS = 3
        private const val MINIMUM_RESTORE_MARGIN_BYTES = 64L * 1024L * 1024L
        private const val RESTORE_STAGE_PREFIX = "masternote.restore-stage-"
        private const val RESTORE_ROLLBACK_PREFIX = "masternote.restore-rollback-"
        private const val LEGACY_RESTORE_NEW_PREFIX = "masternote.restore-new-"

        fun get(context: Context): MasterNoteBackupManager = instance ?: synchronized(this) {
            instance ?: MasterNoteBackupManager(context.applicationContext).also { instance = it }
        }

        /** Call from Application.onCreate before any LibraryRepository access. */
        fun recoverInterruptedRestore(context: Context) {
            get(context) // Construction performs serialized recovery before publishing the singleton.
        }
    }
}

private data class OwnedBackupRow(
    val uri: Uri,
    val dateAddedSeconds: Long,
)
