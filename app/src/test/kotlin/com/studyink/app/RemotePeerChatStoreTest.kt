package com.studyink.app

import com.studyink.monitor.core.ChatMessageEnvelope
import com.studyink.monitor.core.RemotePeerChatDirection
import com.studyink.monitor.core.RemotePeerChatScope
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePeerChatStoreTest {
    @Test fun incomingAndOutgoingHistorySurvivesRestartInsideExactPairScope() = withStoreRoot {
        val scope = scope()
        RemotePeerChatStore(it).apply {
            assertEquals(
                RemotePeerChatRecordDisposition.STORED,
                recordIncoming(scope, incoming(1), receivedAtEpochMs = 200L).disposition,
            )
            assertEquals(
                RemotePeerChatRecordDisposition.STORED,
                recordOutgoing(scope, outgoing(2), queuedAtEpochMs = 300L).disposition,
            )
            assertEquals(2, state(scope).retainedMessageCount)
            assertEquals(1, state(scope).unreadCount)
        }

        RemotePeerChatStore(it).apply {
            val messages = messages(scope)
            assertEquals(listOf("message_0001", "message_0002"), messages.map { item -> item.messageId })
            assertEquals(RemotePeerChatDirection.INCOMING, messages.first().direction)
            assertFalse(messages.first().isRead)
            assertEquals(RemotePeerChatDirection.OUTGOING, messages.last().direction)
            assertTrue(messages.last().isRead)
            assertEquals(1, state(scope).unreadCount)
        }
    }

    @Test fun readThroughHighWaterIsDurableAndLaterArrivalStartsUnread() = withStoreRoot {
        val scope = scope()
        RemotePeerChatStore(it).apply {
            recordIncoming(scope, incoming(1), 100L)
            recordIncoming(scope, incoming(2), 200L)

            val partial = markRead(scope, throughMessageId = "message_0001", readAtEpochMs = 300L)
            assertEquals(1, partial.unreadCount)
            assertTrue(messages(scope).first().isRead)
            assertFalse(messages(scope).last().isRead)
        }

        RemotePeerChatStore(it).apply {
            assertEquals(1, state(scope).unreadCount)
            assertEquals(300L, state(scope).lastReadAtEpochMs)
            assertEquals(0, markRead(scope, readAtEpochMs = 400L).unreadCount)
            recordIncoming(scope, incoming(3), 500L)
            assertEquals(1, state(scope).unreadCount)
        }

        assertEquals(1, RemotePeerChatStore(it).state(scope).unreadCount)
    }

    @Test fun exactReplayDeduplicatesButIdOrTransferReuseWithChangedMeaningConflicts() = withStoreRoot {
        val scope = scope()
        val store = RemotePeerChatStore(it)
        val original = incoming(1)
        assertEquals(RemotePeerChatRecordDisposition.STORED, store.recordIncoming(scope, original, 100L).disposition)
        assertEquals(
            RemotePeerChatRecordDisposition.DUPLICATE,
            store.recordIncoming(scope, original, 101L).disposition,
        )
        assertEquals(
            RemotePeerChatRecordDisposition.CONFLICT,
            store.recordIncoming(scope, incoming(1, text = "변조된 내용"), 102L).disposition,
        )
        assertEquals(
            RemotePeerChatRecordDisposition.CONFLICT,
            store.recordIncoming(
                scope,
                incoming(2, transferId = original.transferId),
                103L,
            ).disposition,
        )
        assertEquals(1, store.state(scope).retainedMessageCount)

        assertEquals(
            RemotePeerChatRecordDisposition.DUPLICATE,
            RemotePeerChatStore(it).recordIncoming(scope, original, 200L).disposition,
        )
    }

    @Test fun senderPinAndPairScopePreventCrossPairDisclosureOrInsertion() = withStoreRoot {
        val first = scope(pairId = "pair_scope_0001")
        val second = scope(pairId = "pair_scope_0002")
        val store = RemotePeerChatStore(it)
        store.recordIncoming(first, incoming(1), 100L)

        assertTrue(store.messages(second).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            store.recordIncoming(
                first,
                incoming(2, senderDeviceId = first.localDeviceId),
                200L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.recordOutgoing(
                first,
                outgoing(2, senderDeviceId = first.peerDeviceId),
                200L,
            )
        }
        assertEquals(1, store.messages(first).size)
        assertTrue(store.messages(second).isEmpty())
    }

    @Test fun visibleRetentionIsBoundedWhileRecentEvictionsKeepDurableDedupe() = withStoreRoot {
        val scope = scope()
        val first = incoming(1)
        val store = RemotePeerChatStore(
            root = it,
            maxMessagesPerScope = 2,
            maxSeenPerScope = 4,
            stateMessageLimit = 2,
        )
        repeat(4) { index -> store.recordIncoming(scope, incoming(index + 1), index.toLong()) }

        assertEquals(listOf("message_0003", "message_0004"), store.messages(scope).map { item -> item.messageId })
        val duplicate = store.recordIncoming(scope, first, 99L)
        assertEquals(RemotePeerChatRecordDisposition.DUPLICATE, duplicate.disposition)
        assertNull(duplicate.message)
        assertEquals(2, duplicate.state.retainedMessageCount)

        val restarted = RemotePeerChatStore(
            root = it,
            maxMessagesPerScope = 2,
            maxSeenPerScope = 4,
            stateMessageLimit = 2,
        )
        assertEquals(RemotePeerChatRecordDisposition.DUPLICATE, restarted.recordIncoming(scope, first, 100L).disposition)
        assertEquals(2, restarted.messages(scope).size)
    }

    @Test fun leastRecentlyActiveScopeIsRemovedAndDoesNotReappearAfterRestart() = withStoreRoot {
        fun newStore() = RemotePeerChatStore(
            root = it,
            maxScopes = 2,
            maxMessagesPerScope = 4,
            maxSeenPerScope = 8,
            stateMessageLimit = 4,
        )
        val first = scope("pair_scope_0001")
        val second = scope("pair_scope_0002")
        val third = scope("pair_scope_0003")
        newStore().apply {
            recordIncoming(first, incoming(1), 100L)
            recordIncoming(second, incoming(2), 200L)
            recordIncoming(first, incoming(3), 300L) // first becomes most recently active
            recordIncoming(third, incoming(4), 400L)
            assertEquals(listOf(first, third), retainedScopes())
        }

        newStore().apply {
            assertEquals(listOf(first, third), retainedScopes())
            assertTrue(messages(second).isEmpty())
        }
    }

    @Test fun compactionAndMalformedTailPreserveLiveMessagesReadStateAndDedupe() = withStoreRoot {
        fun newStore() = RemotePeerChatStore(
            root = it,
            maxMessagesPerScope = 2,
            maxSeenPerScope = 4,
            stateMessageLimit = 2,
            compactAfterRecords = 4,
            maximumJournalBytes = 1_000_000L,
        )
        val scope = scope()
        val store = newStore()
        repeat(20) { index -> store.recordIncoming(scope, incoming(index + 1), index.toLong()) }
        store.markRead(scope, readAtEpochMs = 100L)
        val journal = File(it, "peer-chat.v1")
        assertTrue(journal.readLines().size < 20)
        journal.appendText("PC1\tMSG\tpartial", Charsets.UTF_8)

        newStore().apply {
            assertEquals(listOf("message_0019", "message_0020"), messages(scope).map { item -> item.messageId })
            assertEquals(0, state(scope).unreadCount)
            assertEquals(100L, state(scope).lastReadAtEpochMs)
            assertEquals(
                RemotePeerChatRecordDisposition.DUPLICATE,
                recordIncoming(scope, incoming(17), 200L).disposition,
            )
        }
    }

    @Test fun explicitClearIsDurableAndDoesNotTouchOtherScope() = withStoreRoot {
        val first = scope("pair_scope_0001")
        val second = scope("pair_scope_0002")
        RemotePeerChatStore(it).apply {
            recordIncoming(first, incoming(1), 100L)
            recordIncoming(second, incoming(2), 200L)
            assertTrue(clear(first))
            assertFalse(clear(first))
        }

        RemotePeerChatStore(it).apply {
            assertTrue(messages(first).isEmpty())
            assertEquals(1, messages(second).size)
        }
    }

    private fun incoming(
        index: Int,
        transferId: String = "transfer_${index.toString().padStart(4, '0')}",
        senderDeviceId: String = "peer_device_00001",
        text: String = "받은 메시지 $index",
    ) = envelope(index, transferId, senderDeviceId, text)

    private fun outgoing(
        index: Int,
        senderDeviceId: String = "local_device_0001",
    ) = envelope(
        index = index,
        transferId = "transfer_${index.toString().padStart(4, '0')}",
        senderDeviceId = senderDeviceId,
        text = "보낸 메시지 $index",
    )

    private fun envelope(
        index: Int,
        transferId: String,
        senderDeviceId: String,
        text: String,
    ) = ChatMessageEnvelope(
        transferId = transferId,
        createdAtEpochMs = 10_000L + index,
        messageId = "message_${index.toString().padStart(4, '0')}",
        senderDeviceId = senderDeviceId,
        sentAtEpochMs = 9_000L + index,
        text = text,
    )

    private fun scope(pairId: String = "pair_scope_0001") = RemotePeerChatScope(
        pairId = pairId,
        localDeviceId = "local_device_0001",
        peerDeviceId = "peer_device_00001",
    )

    private inline fun withStoreRoot(block: (File) -> Unit) {
        val root = createTempDirectory("peer-chat-store").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
