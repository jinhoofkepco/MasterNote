package com.studyink.monitor.telegram

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** All secrets, queue state and generated attachments live outside Android Auto Backup. */
class TelegramStoragePaths private constructor(val root: File) {
    val credentialsFile = root.resolve("credentials.bin")
    val offsetFile = root.resolve("update-offset.v1")
    val outboxJournal = root.resolve("outbox-journal.v1")
    val connectionStateFile = root.resolve("connection-state.v1")
    val retryGateFile = root.resolve("retry-gate.v1")
    val priorityRetryGateFile = root.resolve("priority-retry-gate.v1")
    val preferencesFile = root.resolve("preferences.v1")
    val parentMessageInboxFile = root.resolve("parent-message-inbox.v1")
    val screenRequestInboxFile = root.resolve("screen-request-inbox.v1")
    val peerInboxJournal = root.resolve("peer-inbox-journal.v1")
    val peerReceiptJournal = root.resolve("peer-receipts.v1")
    val peerHandshakeFile = root.resolve("peer-handshake.v1")
    val peerLinkStateFile = root.resolve("peer-link-state.v1")
    val peerInboxDirectory = root.resolve("peer-inbox")
    val mediaDirectory = root.resolve("media")
    val voiceDirectory = mediaDirectory.resolve("voice")
    val peerOutboxDirectory = mediaDirectory.resolve("peer-outbox")

    init {
        require(root.mkdirs() || root.isDirectory) { "Unable to create Telegram no-backup directory." }
        require(mediaDirectory.mkdirs() || mediaDirectory.isDirectory)
        require(voiceDirectory.mkdirs() || voiceDirectory.isDirectory)
        require(peerInboxDirectory.mkdirs() || peerInboxDirectory.isDirectory)
        require(peerOutboxDirectory.mkdirs() || peerOutboxDirectory.isDirectory)
    }

    companion object {
        fun from(context: Context): TelegramStoragePaths = TelegramStoragePaths(
            context.applicationContext.noBackupFilesDir.resolve(ROOT_DIRECTORY),
        )

        internal fun forTests(root: File): TelegramStoragePaths = TelegramStoragePaths(root)

        private const val ROOT_DIRECTORY = "master-note-telegram-v1"
    }
}

internal object AtomicDiskFile {
    fun writeText(target: File, value: String) = writeBytes(
        target,
        value.toByteArray(StandardCharsets.UTF_8),
    )

    fun writeBytes(target: File, value: ByteArray) {
        val parent = requireNotNull(target.parentFile)
        require(parent.mkdirs() || parent.isDirectory)
        val temporary = parent.resolve(target.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(value)
            output.flush()
            output.fd.sync()
        }
        replace(temporary, target)
    }

    fun replace(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

class TelegramUpdateOffsetStore(private val file: File) {
    @Synchronized
    fun load(botFingerprint: String): Long {
        val fields = runCatching { file.readText(StandardCharsets.UTF_8).trim().split('\t') }
            .getOrNull() ?: return 0L
        if (fields.size != 3 || fields[0] != VERSION || fields[1] != botFingerprint) return 0L
        return fields[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    @Synchronized
    fun commit(botFingerprint: String, nextOffset: Long) {
        require(nextOffset >= 0L)
        val current = load(botFingerprint)
        if (nextOffset <= current) return
        AtomicDiskFile.writeText(file, "$VERSION\t$botFingerprint\t$nextOffset\n")
    }

    @Synchronized fun reset() {
        file.delete()
    }

    private companion object { const val VERSION = "V1" }
}

class TelegramRetryGate(private val file: File) {
    @Synchronized fun nextAllowedEpochMs(): Long = file.takeIf(File::isFile)
        ?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
        ?.coerceAtLeast(0L) ?: 0L

    @Synchronized fun deferUntil(epochMs: Long) {
        val target = maxOf(epochMs, nextAllowedEpochMs())
        AtomicDiskFile.writeText(file, target.toString())
    }

    @Synchronized fun clearIfElapsed(nowEpochMs: Long) {
        if (nextAllowedEpochMs() <= nowEpochMs) file.delete()
    }
}

internal fun botFingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(StandardCharsets.UTF_8))
    .take(12)
    .joinToString("") { "%02x".format(it) }
