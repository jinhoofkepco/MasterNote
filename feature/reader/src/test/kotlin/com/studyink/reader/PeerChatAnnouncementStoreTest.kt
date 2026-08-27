package com.studyink.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeerChatAnnouncementStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun claimSurvivesStoreAndProcessStateRecreation() {
        val journal = temporary.newFolder("restart").resolve("announcements.v1")
        val original = PeerChatAnnouncementStore(journal)

        assertTrue(original.claim("pair-a", "message-1"))

        // A new store has no shared in-memory claim state and must recover it from disk.
        val recreated = PeerChatAnnouncementStore(journal)
        assertFalse(recreated.claim("pair-a", "message-1"))
        assertTrue(recreated.claim("pair-a", "message-2"))
    }

    @Test
    fun recreatedActivityGateCannotReplayADurablyClaimedAnnouncement() {
        val journal = temporary.newFolder("activity-restart").resolve("announcements.v1")
        var visibleOrAudibleSideEffects = 0

        fun deliver(gate: PeerChatOverlayDeliveryGate, store: PeerChatAnnouncementStore) {
            gate.offer("pair-a", "message-1", "확인해줘", canDisplayNow = true)?.let { delivery ->
                if (store.claim(delivery.pairId, delivery.messageId)) {
                    visibleOrAudibleSideEffects++
                }
            }
        }

        deliver(PeerChatOverlayDeliveryGate(), PeerChatAnnouncementStore(journal))
        deliver(PeerChatOverlayDeliveryGate(), PeerChatAnnouncementStore(journal))

        assertEquals(1, visibleOrAudibleSideEffects)
    }

    @Test
    fun concurrentStoresGrantExactlyOneClaim() {
        val journal = temporary.newFolder("concurrent").resolve("announcements.v1")
        val stores = List(12) { PeerChatAnnouncementStore(journal) }
        val ready = CountDownLatch(stores.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(stores.size)
        try {
            val claims = stores.map { store ->
                executor.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    store.claim("pair-a", "message-1")
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            assertEquals(1, claims.count { it.get(10, TimeUnit.SECONDS) })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun identicalMessageIdsInDifferentPairsAreIndependent() {
        val journal = temporary.newFolder("pairs").resolve("announcements.v1")
        val store = PeerChatAnnouncementStore(journal)

        assertTrue(store.claim("pair-a", "message-1"))
        assertTrue(store.claim("pair-b", "message-1"))
        assertFalse(store.claim("pair-a", "message-1"))
        assertFalse(store.claim("pair-b", "message-1"))
    }
}
