package com.studyink.remote.sync

import com.studyink.remote.protocol.RemoteDurableOperation
import com.studyink.remote.protocol.RemoteStrokeAsset
import com.studyink.remote.protocol.RemoteStrokePoint
import com.studyink.remote.storage.RemoteReplicaPage
import com.studyink.remote.storage.RemoteReplicaStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckpointSyncTest {
    @Test fun incompleteSnapshotNeverReplacesExistingReplica() = runTest {
        val store = MemoryReplicaStore(existingPage())
        val chunks = CheckpointProducer.create("new", "page", 2, manyStrokes())
        assertTrue(chunks.size > 1)
        val assembler = CheckpointAssembler("session", store, { 3 }, { 2 })

        chunks.dropLast(1).forEach { assembler.receive(it) }

        assertEquals(1L, store.page("session", "page")?.layerRevision)
        assertEquals(listOf("old"), store.page("session", "page")?.strokes?.map { it.strokeId })
    }

    @Test fun outOfOrderCompleteSnapshotAtomicallyReplacesPage() = runTest {
        val store = MemoryReplicaStore(existingPage())
        val strokes = manyStrokes()
        val chunks = CheckpointProducer.create("new", "page", 9, strokes)
        val assembler = CheckpointAssembler("session", store, { 3 }, { 4 })

        val results = chunks.reversed().map { assembler.receive(it) }

        assertTrue(results.last() is CheckpointReceiveResult.Applied)
        assertEquals(9L, store.page("session", "page")?.layerRevision)
        assertEquals(strokes.map { it.strokeId }, store.page("session", "page")?.strokes?.map { it.strokeId })
    }

    @Test fun corruptFinalChunkKeepsOldReplica() = runTest {
        val store = MemoryReplicaStore(existingPage())
        val chunks = CheckpointProducer.create("new", "page", 2, manyStrokes()).toMutableList()
        val last = chunks.last()
        chunks[chunks.lastIndex] = last.copy(strokeAssets = last.strokeAssets.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        val assembler = CheckpointAssembler("session", store, { 3 }, { 2 })

        val result = chunks.map { assembler.receive(it) }.last()

        assertTrue(result is CheckpointReceiveResult.Rejected)
        assertEquals(1L, store.page("session", "page")?.layerRevision)
    }

    @Test fun equalDigestsAvoidCheckpoint() {
        val strokes = manyStrokes().take(2)
        assertTrue(pageDigest("page", 5, strokes).matches(pageDigest("page", 5, strokes.reversed())))
    }

    private fun manyStrokes() = List(240) { index ->
        RemoteStrokeAsset("stroke-$index", 3, 0, -1, 4f, List(24) { point ->
            RemoteStrokePoint(point.toFloat(), index.toFloat(), .5f, point.toLong())
        })
    }
    private fun existingPage() = RemoteReplicaPage("session", "page", 3, 1, 0, 1, listOf(
        RemoteStrokeAsset("old", 3, 0, -1, 4f, listOf(RemoteStrokePoint(0f, 0f, .5f, 1)))
    ))

    private class MemoryReplicaStore(initial: RemoteReplicaPage? = null) : RemoteReplicaStore {
        private val data = mutableMapOf<Pair<String, String>, RemoteReplicaPage>()
        init { initial?.let { data[it.sessionId to it.pageId] = it } }
        override suspend fun page(sessionId: String, pageId: String) = data[sessionId to pageId]
        override suspend fun pages(sessionId: String) = data.values.filter { it.sessionId == sessionId }
        override suspend fun replacePageAtomically(page: RemoteReplicaPage) { data[page.sessionId to page.pageId] = page }
        override suspend fun applyOperationAtomically(sessionId: String, durableSequence: Long, messageId: String, operation: RemoteDurableOperation, appliedAtEpochMillis: Long) = false
        override suspend fun deleteSession(sessionId: String) { data.keys.removeAll { it.first == sessionId } }
    }
}
