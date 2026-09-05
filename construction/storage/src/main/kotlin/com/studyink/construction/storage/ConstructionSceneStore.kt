package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.MasterNoteDataRootBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ConcurrentModificationException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Durable geometry sidecar under filesDir/masternote, covered by the ordinary whole-root backup.
 * Pass File(context.filesDir, "masternote") as dataRoot. No stroke or memo metadata is changed.
 * Each save is one atomic full-scene transaction; stale editors must reload explicitly.
 */
class ConstructionSceneStore(private val dataRoot: File) {
    private val rootIdentity = dataRoot.toPath().toAbsolutePath().normalize().toString()
    private val featureRoot = File(dataRoot, FEATURE_DIRECTORY)

    init { RestoreEpoch.current() }

    fun load(target: ConstructionTarget): ConstructionSceneSnapshot = locked {
        snapshot(target, read(target))
    }

    /** The expected snapshot carries revision, commit identity and restore epoch for CAS. */
    fun save(expected: ConstructionSceneSnapshot, scene: ConstructionScene): ConstructionSceneSnapshot {
        val frozen = ConstructionJsonCodec.immutableScene(scene)
        val result = locked {
            if (expected.rootIdentity != rootIdentity || expected.rootEpoch != RestoreEpoch.current()) {
                throw ConcurrentModificationException("Construction data was restored; reload the memo before saving")
            }
            val current = read(expected.target)
            if ((current?.revision ?: 0L) != expected.revision || current?.commitId != expected.commitId) {
                throw ConcurrentModificationException("Construction was changed by another editor; reload before saving")
            }
            check(expected.revision < Long.MAX_VALUE) { "Construction revision is exhausted" }
            val next = StoredConstructionDocument(
                expected.target, expected.revision + 1L, UUID.randomUUID().toString(), frozen,
            )
            file(expected.target).write(ConstructionJsonCodec.encode(next))
            snapshot(expected.target, next)
        }
        MasterNoteDataCommitBus.recordDurableCommit()
        return result
    }

    /** Invoked synchronously on the restore caller's thread; UI listeners must dispatch to main. */
    fun addRestoreListener(listener: () -> Unit): AutoCloseable = MasterNoteDataRootBus.addListener(listener)

    private fun read(target: ConstructionTarget): StoredConstructionDocument? = try {
        file(target).readOrNull()?.let(ConstructionJsonCodec::decode)?.also {
            require(it.target == target) { "Construction target identity mismatch" }
        }
    } catch (error: Exception) {
        throw ConstructionDataException(
            "저장된 작도 데이터를 읽지 못했습니다. 기존 파일은 보존됩니다.", error,
        )
    }

    private fun snapshot(target: ConstructionTarget, document: StoredConstructionDocument?) =
        ConstructionSceneSnapshot(
            target, document?.revision ?: 0L,
            document?.scene ?: ConstructionJsonCodec.immutableScene(ConstructionScene()),
            document?.commitId, RestoreEpoch.current(), rootIdentity,
        )

    private fun file(target: ConstructionTarget): AtomicConstructionFile =
        AtomicConstructionFile(targetFileForTest(target), ConstructionJsonCodec.MAX_BYTES)

    internal fun targetFileForTest(target: ConstructionTarget): File {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(target.ownerScope, target.bookId, target.pageNumber.toString(),
            target.attemptNo.toString(), target.memoId).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val name = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(featureRoot, "${name.take(2)}/$name.json")
    }

    private fun <T> locked(block: () -> T): T =
        MasterNoteOptionalDataRootGuard.withStableDataRoot(dataRoot, block)

    companion object { const val FEATURE_DIRECTORY = "construction-scenes-v1" }
}

/** Registered once for the whole process, including stores opened after a previous restore. */
private object RestoreEpoch {
    private val epoch = AtomicLong(0L)
    @Suppress("unused")
    private val subscription = MasterNoteDataRootBus.addListener { epoch.incrementAndGet() }
    fun current(): Long = epoch.get()
}
