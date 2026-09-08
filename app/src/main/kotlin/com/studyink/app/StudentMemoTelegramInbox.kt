package com.studyink.app

import android.util.AtomicFile
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import com.studyink.monitor.core.StudentMemoEnvelope
import com.studyink.monitor.core.studentMemoPayloadSha256Hex
import org.json.JSONObject
import java.io.File
import java.io.DataInputStream

/** Tiny durable completion proof. Fragments themselves remain in the gateway inbox until applied. */
internal class StudentMemoTelegramReceipts(private val root: File) {
    fun contains(owner: String, envelope: StudentMemoEnvelope): Boolean =
        MasterNoteOptionalDataRootGuard.withStableDataRoot(root) {
            val key = fingerprint(owner, envelope)
            val file = target(key)
            if (!file.exists() && !File(file.path + ".bak").exists()) return@withStableDataRoot false
            runCatching {
                val atomic = AtomicFile(file)
                atomic.openRead().use { input ->
                    val bytes = ByteArray(key.length)
                    DataInputStream(input).readFully(bytes)
                    String(bytes, Charsets.US_ASCII) == key && input.read() == -1
                }
            }.getOrDefault(false)
        }

    fun record(owner: String, envelope: StudentMemoEnvelope) =
        MasterNoteOptionalDataRootGuard.withStableDataRoot(root) {
            val key = fingerprint(owner, envelope)
            val file = target(key)
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            val atomic = AtomicFile(file)
            val bytes = key.toByteArray(Charsets.US_ASCII)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes); stream.flush(); stream.fd.sync(); atomic.finishWrite(stream)
                check(contains(owner, envelope)) { "Memo completion receipt was not committed" }
            } catch (error: Exception) { atomic.failWrite(stream); throw error }
            MasterNoteDataCommitBus.recordDurableCommit()
        }

    private fun target(key: String) = File(root, "student-memo-chunk-receipts-v3/${key.take(2)}/$key")

    private fun fingerprint(owner: String, e: StudentMemoEnvelope): String {
        val c = requireNotNull(e.chunk)
        // The process-local restore counter is only an in-flight fence, never a durable identity.
        val value = JSONObject().put("owner", owner)
            .put("group", c.groupId).put("total", c.totalBytes).put("sha", c.completeSha256)
            .put("sync", e.syncGeneration).put("page", e.pageToken).put("workbook", e.workbookToken)
            .put("content", e.contentSha256).put("number", e.pageNumber).put("attempt", e.attemptNo)
            .put("memo", e.memoId).put("revision", e.memoRevision).put("digest", e.memoDigestSha256)
            .put("extended", e.extendedCanvas).put("created", e.createdAtEpochMs)
        return studentMemoPayloadSha256Hex(value.toString().toByteArray(Charsets.UTF_8))
    }
}
