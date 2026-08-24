package com.studyink.monitor.telegram

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Durable inbox. A Telegram update offset is committed only after [offer] has fsynced its PUT. */
class TelegramPeerDocumentInbox(
    private val journal: File,
    private val ownedDirectory: File,
    private val maxPendingEntries: Int = 48,
) {
    private val pending = linkedMapOf<Long, PendingTelegramPeerDocument>()
    private val completedTransfers = linkedMapOf<String, Long>()
    private val listeners = linkedSetOf<(PendingTelegramPeerDocument) -> Unit>()
    private var appendedRecords = 0

    init {
        require(maxPendingEntries > 0)
        require(journal.parentFile?.mkdirs() == true || journal.parentFile?.isDirectory == true)
        require(ownedDirectory.mkdirs() || ownedDirectory.isDirectory)
        replay()
        cleanupOrphans()
    }

    @Synchronized
    fun offer(entry: PendingTelegramPeerDocument): Boolean {
        validate(entry)
        if (entry.updateId in pending || entry.transferId in completedTransfers ||
            pending.values.any { it.transferId == entry.transferId }
        ) {
            deleteOwned(entry.file)
            return false
        }
        check(pending.size < maxPendingEntries) { "Remote-review inbox is full." }
        append(encodePut(entry))
        pending[entry.updateId] = entry
        compactIfNeeded()
        listeners.toList().forEach { it(entry) }
        return true
    }

    @Synchronized fun hasSeen(updateId: Long, transferId: String): Boolean =
        updateId in pending || transferId in completedTransfers || pending.values.any { it.transferId == transferId }

    @Synchronized fun isCompleted(transferId: String): Boolean = transferId in completedTransfers

    @Synchronized fun pending(): List<PendingTelegramPeerDocument> = pending.values.toList()

    @Synchronized
    fun acknowledge(updateId: Long, acknowledgedAtEpochMs: Long): PendingTelegramPeerDocument? {
        val entry = pending[updateId] ?: return null
        require(acknowledgedAtEpochMs >= 0L)
        append(encodeDone(entry, acknowledgedAtEpochMs))
        pending.remove(updateId)
        rememberCompleted(entry.transferId, acknowledgedAtEpochMs)
        deleteOwned(entry.file)
        compactIfNeeded()
        return entry
    }

    fun subscribe(
        emitPending: Boolean = true,
        listener: (PendingTelegramPeerDocument) -> Unit,
    ): RemoteMonitorStatusSubscription {
        val initial = synchronized(this) {
            listeners += listener
            if (emitPending) pending.values.toList() else emptyList()
        }
        initial.forEach(listener)
        return RemoteMonitorStatusSubscription { synchronized(this) { listeners -= listener } }
    }

    @Synchronized fun clear() {
        pending.values.forEach { deleteOwned(it.file) }
        pending.clear()
        completedTransfers.clear()
        journal.delete()
        appendedRecords = 0
        ownedDirectory.listFiles().orEmpty().filter(File::isFile).forEach { it.delete() }
    }

    private fun replay() {
        if (!journal.isFile) return
        journal.forEachLine(StandardCharsets.UTF_8) { line ->
            when (val record = decode(line)) {
                is Record.Put -> if (runCatching { validate(record.entry); true }.getOrDefault(false)) {
                    pending[record.entry.updateId] = record.entry
                }
                is Record.Done -> {
                    pending.remove(record.updateId)?.let { deleteOwned(it.file) }
                    rememberCompleted(record.transferId, record.atEpochMs)
                }
                null -> Unit
            }
            appendedRecords++
        }
    }

    private fun cleanupOrphans() {
        val referenced = pending.values.mapNotNull { runCatching { it.file.canonicalPath }.getOrNull() }.toSet()
        ownedDirectory.listFiles().orEmpty().filter(File::isFile).forEach { file ->
            if (file.name.endsWith(".part") || runCatching { file.canonicalPath }.getOrNull() !in referenced) {
                file.delete()
            }
        }
    }

    private fun validate(entry: PendingTelegramPeerDocument) {
        require(entry.updateId >= 0L && entry.telegramMessageId > 0L && entry.senderBotId > 0L)
        require(entry.senderUsername == normalizeTelegramUsername(entry.senderUsername))
        require(PEER_IDENTIFIER.matches(entry.transferId))
        require(entry.payloadType.matches(Regex("^[A-Z][A-Z0-9_]{0,39}$")))
        require(entry.byteCount in 0..TelegramPeerPayloadCipher.MAX_PLAINTEXT_BYTES)
        require(entry.file.isFile && entry.file.length() == entry.byteCount)
        require(isOwned(entry.file))
    }

    private fun append(value: String) {
        FileOutputStream(journal, true).use { output ->
            output.writer(StandardCharsets.UTF_8).use { writer ->
                writer.append(value).append('\n')
                writer.flush()
                output.fd.sync()
            }
        }
        appendedRecords++
    }

    private fun compactIfNeeded() {
        val live = pending.size + completedTransfers.size
        if (appendedRecords < 256 || appendedRecords < live * 2) return
        val text = buildString {
            pending.values.forEach { append(encodePut(it)).append('\n') }
            completedTransfers.forEach { (transferId, at) ->
                append(listOf(VERSION, "DONE", "-1", encode(transferId), at.toString()).joinToString("\t"))
                    .append('\n')
            }
        }
        AtomicDiskFile.writeText(journal, text)
        appendedRecords = live
    }

    private fun encodePut(entry: PendingTelegramPeerDocument): String = listOf(
        VERSION, "PUT", entry.updateId.toString(), entry.telegramMessageId.toString(),
        entry.senderBotId.toString(), encode(entry.senderUsername), encode(entry.transferId),
        encode(entry.payloadType), encode(entry.fileUniqueId), encode(entry.originalFileName.orEmpty()),
        encode(entry.mimeType.orEmpty()), entry.byteCount.toString(), encode(entry.localFilePath),
        entry.receivedAtEpochMs.toString(), entry.replyToMessageId?.toString().orEmpty(),
    ).joinToString("\t")

    private fun encodeDone(entry: PendingTelegramPeerDocument, at: Long): String = listOf(
        VERSION, "DONE", entry.updateId.toString(), encode(entry.transferId), at.toString(),
    ).joinToString("\t")

    private fun decode(line: String): Record? = runCatching {
        val f = line.split('\t')
        if (f.firstOrNull() != VERSION) return null
        when (f.getOrNull(1)) {
            "PUT" -> {
                if (f.size != 15) return null
                Record.Put(
                    PendingTelegramPeerDocument(
                        updateId = f[2].toLong(), telegramMessageId = f[3].toLong(),
                        senderBotId = f[4].toLong(), senderUsername = decodeString(f[5]),
                        transferId = decodeString(f[6]), payloadType = decodeString(f[7]),
                        fileUniqueId = decodeString(f[8]), originalFileName = decodeString(f[9]).ifBlank { null },
                        mimeType = decodeString(f[10]).ifBlank { null }, byteCount = f[11].toLong(),
                        localFilePath = decodeString(f[12]), receivedAtEpochMs = f[13].toLong(),
                        replyToMessageId = f[14].takeIf(String::isNotBlank)?.toLong(),
                    ),
                )
            }
            "DONE" -> {
                if (f.size != 5) return null
                Record.Done(f[2].toLong(), decodeString(f[3]), f[4].toLong())
            }
            else -> null
        }
    }.getOrNull()

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decodeString(value: String) = Base64.getUrlDecoder().decode(value).toString(StandardCharsets.UTF_8)
    private fun isOwned(file: File): Boolean = runCatching {
        file.canonicalFile.toPath().startsWith(ownedDirectory.canonicalFile.toPath())
    }.getOrDefault(false)
    private fun deleteOwned(file: File) { if (isOwned(file)) runCatching { file.delete() } }
    private fun rememberCompleted(transferId: String, at: Long) {
        completedTransfers.remove(transferId)
        completedTransfers[transferId] = at
        while (completedTransfers.size > MAX_COMPLETED) completedTransfers.remove(completedTransfers.keys.first())
    }

    private sealed interface Record {
        data class Put(val entry: PendingTelegramPeerDocument) : Record
        data class Done(val updateId: Long, val transferId: String, val atEpochMs: Long) : Record
    }
    private companion object { const val VERSION = "MNPI1"; const val MAX_COMPLETED = 4_096 }
}

class TelegramPeerReceiptStore(private val journal: File) {
    private val receipts = linkedMapOf<String, TelegramDeliveryReceipt>()
    private var appendedRecords = 0

    init {
        require(journal.parentFile?.mkdirs() == true || journal.parentFile?.isDirectory == true)
        replay()
    }

    @Synchronized
    fun recordSent(
        transferId: String,
        outboxKey: String,
        telegramMessageId: Long?,
        sentAtEpochMs: Long,
    ): TelegramDeliveryReceipt {
        receipts[transferId]?.let { return it }
        val receipt = TelegramDeliveryReceipt(transferId, outboxKey, telegramMessageId, sentAtEpochMs)
        append(listOf(VERSION, "SENT", encode(transferId), encode(outboxKey), telegramMessageId?.toString().orEmpty(), sentAtEpochMs.toString()).joinToString("\t"))
        receipts[transferId] = receipt
        trim()
        compactIfNeeded()
        return receipt
    }

    @Synchronized
    fun recordAcknowledged(transferId: String, messageId: Long?, acknowledgedAtEpochMs: Long): Boolean {
        val current = receipts[transferId] ?: return false
        if (current.acknowledgedAtEpochMs != null) return true
        append(listOf(VERSION, "ACK", encode(transferId), messageId?.toString().orEmpty(), acknowledgedAtEpochMs.toString()).joinToString("\t"))
        receipts[transferId] = current.copy(
            acknowledgedAtEpochMs = acknowledgedAtEpochMs,
            acknowledgementMessageId = messageId,
        )
        compactIfNeeded()
        return true
    }

    @Synchronized fun receipt(transferId: String): TelegramDeliveryReceipt? = receipts[transferId]
    @Synchronized fun clear() { receipts.clear(); journal.delete(); appendedRecords = 0 }

    private fun replay() {
        if (!journal.isFile) return
        journal.forEachLine(StandardCharsets.UTF_8) { line ->
            runCatching {
                val f = line.split('\t')
                if (f.firstOrNull() != VERSION) return@runCatching
                when (f.getOrNull(1)) {
                    "SENT" -> if (f.size == 6) {
                        val transfer = decode(f[2])
                        receipts[transfer] = TelegramDeliveryReceipt(
                            transfer, decode(f[3]), f[4].takeIf(String::isNotBlank)?.toLong(), f[5].toLong(),
                        )
                    }
                    "ACK" -> if (f.size == 5) {
                        val transfer = decode(f[2])
                        receipts[transfer]?.let { current ->
                            receipts[transfer] = current.copy(
                                acknowledgedAtEpochMs = f[4].toLong(),
                                acknowledgementMessageId = f[3].takeIf(String::isNotBlank)?.toLong(),
                            )
                        }
                    }
                }
            }
            appendedRecords++
        }
        trim()
    }

    private fun append(value: String) {
        FileOutputStream(journal, true).use { output ->
            output.writer(StandardCharsets.UTF_8).use { writer ->
                writer.append(value).append('\n'); writer.flush(); output.fd.sync()
            }
        }
        appendedRecords++
    }
    private fun trim() { while (receipts.size > MAX_RECEIPTS) receipts.remove(receipts.keys.first()) }
    private fun compactIfNeeded() {
        if (appendedRecords < 512 || appendedRecords < receipts.size * 2) return
        val text = buildString {
            receipts.values.forEach { receipt ->
                append(
                    listOf(
                        VERSION,
                        "SENT",
                        encode(receipt.transferId),
                        encode(receipt.outboxKey),
                        receipt.telegramMessageId?.toString().orEmpty(),
                        receipt.sentAtEpochMs.toString(),
                    ).joinToString("\t"),
                ).append('\n')
                receipt.acknowledgedAtEpochMs?.let { at ->
                    append(
                        listOf(
                            VERSION,
                            "ACK",
                            encode(receipt.transferId),
                            receipt.acknowledgementMessageId?.toString().orEmpty(),
                            at.toString(),
                        ).joinToString("\t"),
                    ).append('\n')
                }
            }
        }
        AtomicDiskFile.writeText(journal, text)
        appendedRecords = receipts.size + receipts.values.count { it.acknowledgedAtEpochMs != null }
    }
    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun decode(value: String) = Base64.getUrlDecoder().decode(value).toString(StandardCharsets.UTF_8)
    private companion object { const val VERSION = "MNPR1"; const val MAX_RECEIPTS = 10_000 }
}
