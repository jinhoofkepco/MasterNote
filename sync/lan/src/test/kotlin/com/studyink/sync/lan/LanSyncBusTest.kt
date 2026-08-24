package com.studyink.sync.lan

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSyncBusTest {
    @Test
    fun activeSessionSnapshotTracksTheServiceSessionAndClearsAfterBothStatesAreIdle() {
        val bookId = "active-book-${UUID.randomUUID()}"
        try {
            LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTED)
            LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.READY)

            assertEquals(
                LanActiveSessionSnapshot(
                    bookId,
                    LanSessionSnapshot(LanConnectionState.CONNECTED, LanSessionPhase.READY),
                ),
                LanSyncBus.activeSessionSnapshot(),
            )

            // closeSession publishes these two changes in order. The intermediate view remains
            // active but is already definitively disconnected, so routing cannot trust READY.
            LanSyncBus.connectionStateChanged(bookId, LanConnectionState.IDLE)
            assertEquals(
                LanActiveSessionSnapshot(
                    bookId,
                    LanSessionSnapshot(LanConnectionState.IDLE, LanSessionPhase.READY),
                ),
                LanSyncBus.activeSessionSnapshot(),
            )
            LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.IDLE)
            assertNull(LanSyncBus.activeSessionSnapshot())
        } finally {
            LanSyncBus.clearConnectionState(bookId)
        }
    }

    @Test
    fun closingCurrentSessionDoesNotReactivateAnOlderStickyReadyBook() {
        val oldBookId = "old-book-${UUID.randomUUID()}"
        val currentBookId = "current-book-${UUID.randomUUID()}"
        try {
            LanSyncBus.connectionStateChanged(oldBookId, LanConnectionState.CONNECTED)
            LanSyncBus.sessionPhaseChanged(oldBookId, LanSessionPhase.READY)
            LanSyncBus.connectionStateChanged(currentBookId, LanConnectionState.CONNECTING)
            LanSyncBus.sessionPhaseChanged(currentBookId, LanSessionPhase.CONNECTING)

            assertEquals(currentBookId, LanSyncBus.activeSessionSnapshot()?.bookId)
            LanSyncBus.clearConnectionState(currentBookId)

            assertNull(LanSyncBus.activeSessionSnapshot())
            assertEquals(
                LanSessionSnapshot(LanConnectionState.CONNECTED, LanSessionPhase.READY),
                LanSyncBus.sessionSnapshot(oldBookId),
            )
        } finally {
            LanSyncBus.clearConnectionState(currentBookId)
            LanSyncBus.clearConnectionState(oldBookId)
        }
    }

    @Test
    fun sessionSnapshotReturnsConnectionAndPhaseFromOneStickyView() {
        val bookId = "snapshot-book"
        try {
            LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTED)
            LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.READY)

            assertEquals(
                LanSessionSnapshot(LanConnectionState.CONNECTED, LanSessionPhase.READY),
                LanSyncBus.sessionSnapshot(bookId),
            )
        } finally {
            LanSyncBus.clearConnectionState(bookId)
        }
    }

    @Test
    fun sessionPhaseIsStickyAndClearedWithTheConnection() {
        val bookId = "book-${UUID.randomUUID()}"
        val received = mutableListOf<LanSessionPhase>()
        val listener = object : LanSyncBus.Listener {
            override fun onSessionPhaseChanged(bookId: String, phase: LanSessionPhase) {
                received += phase
            }
        }
        LanSyncBus.addListener(listener)
        try {
            LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.SOCKET_CONNECTED)
            LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.PAGE_CATCHING_UP)
            LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.READY)

            assertEquals(LanSessionPhase.READY, LanSyncBus.sessionPhase(bookId))
            assertEquals(
                listOf(
                    LanSessionPhase.SOCKET_CONNECTED,
                    LanSessionPhase.PAGE_CATCHING_UP,
                    LanSessionPhase.READY,
                ),
                received,
            )

            LanSyncBus.clearConnectionState(bookId)
            assertEquals(LanSessionPhase.IDLE, LanSyncBus.sessionPhase(bookId))
            assertEquals(LanSessionPhase.IDLE, received.last())
        } finally {
            LanSyncBus.clearConnectionState(bookId)
            LanSyncBus.removeListener(listener)
        }
    }

    @Test
    fun pagePresenceIsStickyPerBookAndKeepsLegacyListenerCompatibility() {
        val firstBook = "book-${UUID.randomUUID()}"
        val secondBook = "book-${UUID.randomUUID()}"
        val received = mutableListOf<Triple<String, Int, Long>>()
        val listener = object : LanSyncBus.Listener {
            override fun onPageChanged(bookId: String, pageNumber: Int, revision: Long) {
                received += Triple(bookId, pageNumber, revision)
            }
        }
        LanSyncBus.addListener(listener)
        try {
            val first = PagePresence(firstBook, 4, attemptNo = 2, revision = 9L, followRemoteStudent = true)
            val second = PagePresence(secondBook, 1, attemptNo = null, revision = 3L)

            LanSyncBus.pageChanged(first)
            LanSyncBus.pageChanged(second)

            assertEquals(first, LanSyncBus.localPagePresence(firstBook))
            assertEquals(second, LanSyncBus.localPagePresence(secondBook))
            assertEquals(
                listOf(Triple(firstBook, 4, 9L), Triple(secondBook, 1, 3L)),
                received,
            )
        } finally {
            LanSyncBus.removeListener(listener)
        }
    }

    @Test
    fun remoteStudentLocationIsStickyAndCanBeClearedForANewTeacherSession() {
        val bookId = "book-${UUID.randomUUID()}"
        val legacyPages = mutableListOf<Int>()
        val listener = object : LanSyncBus.Listener {
            override fun onRemotePageChanged(bookId: String, pageNumber: Int) {
                legacyPages += pageNumber
            }
        }
        LanSyncBus.addListener(listener)
        try {
            val location = StudentLocation(bookId, 7, attemptNo = 3, revision = 41L)

            LanSyncBus.remotePageChanged(location)

            assertEquals(location, LanSyncBus.remoteStudentLocation(bookId))
            assertEquals(listOf(7), legacyPages)
            LanSyncBus.clearRemoteStudentLocation(bookId)
            assertNull(LanSyncBus.remoteStudentLocation(bookId))
        } finally {
            LanSyncBus.removeListener(listener)
        }
    }

    @Test
    fun listenerCallbacksRunWithoutHoldingTheBusMonitor() {
        val bookId = "book-${UUID.randomUUID()}"
        var anotherThreadCouldReadStickyState = false
        val listener = object : LanSyncBus.Listener {
            override fun onPagePresenceChanged(presence: PagePresence) {
                val completed = CountDownLatch(1)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    executor.execute {
                        LanSyncBus.localPagePresence(presence.bookId)
                        completed.countDown()
                    }
                    anotherThreadCouldReadStickyState = completed.await(2, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
        LanSyncBus.addListener(listener)
        try {
            LanSyncBus.pageChanged(PagePresence(bookId, 0, revision = 1L))
            assertTrue(anotherThreadCouldReadStickyState)
        } finally {
            LanSyncBus.removeListener(listener)
        }
    }

    @Test
    fun operationWatermarksAreIndependentPerPageAndMonotonicWithinAPage() {
        val watermarks = PageOperationWatermarks()

        watermarks.acknowledge(pageNumber = 3, deviceId = "teacher", logicalClock = 20L)
        watermarks.acknowledge(pageNumber = 4, deviceId = "teacher", logicalClock = 2L)
        watermarks.acknowledge(pageNumber = 3, deviceId = "teacher", logicalClock = 15L)

        assertEquals(20L, watermarks.clock(3, "teacher"))
        assertEquals(2L, watermarks.clock(4, "teacher"))
        assertEquals(0L, watermarks.clock(5, "teacher"))

        // SUBSCRIBE carries the receiver's authoritative cursor for that page and may legitimately
        // regress after its local data was restored or reset.
        watermarks.replace(pageNumber = 3, deviceId = "teacher", logicalClock = 4L)
        assertEquals(4L, watermarks.clock(3, "teacher"))
        assertEquals(2L, watermarks.clock(4, "teacher"))
    }
}
