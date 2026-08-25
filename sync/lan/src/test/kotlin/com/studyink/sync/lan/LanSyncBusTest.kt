package com.studyink.sync.lan

import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import java.io.BufferedReader
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSyncBusTest {

    @Test
    fun mutualAuthProofBindsBothPeersRolesBooksDigestAndNonces() {
        val secret = "11".repeat(32)
        val studentNonce = "22".repeat(32)
        val teacherNonce = "33".repeat(32)
        val documentHash = "44".repeat(32)
        val studentProof = lanAuthProofHex(
            secret,
            studentNonce,
            teacherNonce,
            "student-device",
            "teacher-device",
            LanPeerRole.STUDENT_SERVER,
            LanPeerRole.TEACHER_CLIENT,
            "student-book",
            "teacher-book",
            documentHash,
        )
        val teacherProof = lanAuthProofHex(
            secret,
            teacherNonce,
            studentNonce,
            "teacher-device",
            "student-device",
            LanPeerRole.TEACHER_CLIENT,
            LanPeerRole.STUDENT_SERVER,
            "teacher-book",
            "student-book",
            documentHash,
        )

        assertTrue(isValidLanSha256(studentProof))
        assertTrue(lanAuthProofMatches(studentProof, studentProof))
        assertFalse(lanAuthProofMatches(studentProof, teacherProof))
        assertFalse(
            lanAuthProofMatches(
                studentProof,
                lanAuthProofHex(
                    secret,
                    studentNonce,
                    teacherNonce,
                    "student-device",
                    "teacher-device",
                    LanPeerRole.STUDENT_SERVER,
                    LanPeerRole.TEACHER_CLIENT,
                    "another-student-book",
                    "teacher-book",
                    documentHash,
                ),
            ),
        )
    }

    @Test
    fun lanSecretsAndDocumentDigestsAreFullSha256Values() {
        assertTrue(isValidLanSha256(newLanSecretHex()))
        assertTrue(isValidLanSha256("ab".repeat(32)))
        assertFalse(isValidLanSha256("ab".repeat(16)))
        assertFalse(isValidLanSha256(""))
        assertFalse(isValidLanSha256("AB".repeat(32)))
    }

    @Test
    fun publicHelloContainsNonceAndIdentityButNeverTheSharedSecret() {
        val secret = "55".repeat(32)
        val nonce = "66".repeat(32)
        val hello = lanHelloPublicFields(
            deviceId = "student-device",
            role = LanPeerRole.STUDENT_SERVER,
            bookId = "student-book",
            documentSha256 = "77".repeat(32),
            nonceHex = nonce,
        )

        assertEquals(LAN_AUTH_VERSION, hello["authVersion"])
        assertEquals(nonce, hello["nonce"])
        assertFalse("token" in hello)
        assertFalse("secret" in hello)
        assertFalse(hello.toString().contains(secret))
    }

    @Test
    fun boundedLanLineNeverAllocatesPastTheFrameLimit() {
        fun reader(value: String) = BufferedReader(StringReader(value))
        assertEquals("hello", readBoundedLanLine(reader("hello\r\nnext"), 5))
        assertEquals("exact", readBoundedLanLine(reader("exact\n"), 5))
        assertNull(readBoundedLanLine(reader(""), 5))
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedLanLine(reader("123456"), 5)
        }
    }

    @Test
    fun pageCatchUpDeadlineIgnoresHeartbeatTrafficAndEventuallyExpires() {
        assertFalse(isLanPageCatchUpExpired(0L, 100L))
        assertFalse(isLanPageCatchUpExpired(130L, 129L))
        assertTrue(isLanPageCatchUpExpired(130L, 130L))
        assertTrue(isLanPageCatchUpExpired(130L, 999L))
    }

    @Test
    fun invalidOperationClosesBeforeAFollowingPageSyncedCanClaimReady() {
        assertTrue(mustCloseLanConnectionAfterFailure("OPERATION", authenticated = true))
        assertTrue(mustCloseLanConnectionAfterFailure("PAGE_SYNCED", authenticated = true))
        assertTrue(mustCloseLanConnectionAfterFailure("PING", authenticated = false))
        assertFalse(mustCloseLanConnectionAfterFailure("PING", authenticated = true))
    }

    @Test
    fun connectionEpochCannotBeReusedWithinOneServiceLifetime() {
        val epoch = MonotonicLanConnectionEpoch()

        val first = epoch.advance()
        val second = epoch.advance()

        assertEquals(1L, first)
        assertEquals(2L, second)
        assertEquals(second, epoch.current)
    }

    @Test
    fun activeSessionLeaseSerializesTelegramMutationBeforeLanTakeover() {
        val bookId = "lease-${System.nanoTime()}"
        val leaseEntered = CountDownLatch(1)
        val releaseLease = CountDownLatch(1)
        val takeoverAttempting = CountDownLatch(1)
        val takeoverCompleted = CountDownLatch(1)
        val mutationCompleted = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        LanSyncBus.clearConnectionState(bookId)

        val mutation = executor.submit {
            LanSyncBus.withActiveSessionLease { active ->
                assertNull(active)
                leaseEntered.countDown()
                assertTrue(releaseLease.await(2, TimeUnit.SECONDS))
                mutationCompleted.set(true)
            }
        }
        val takeover = executor.submit {
            assertTrue(leaseEntered.await(2, TimeUnit.SECONDS))
            takeoverAttempting.countDown()
            LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTED)
            assertTrue(
                "LAN takeover completed before the Telegram mutation",
                mutationCompleted.get(),
            )
            takeoverCompleted.countDown()
        }

        try {
            assertTrue(leaseEntered.await(2, TimeUnit.SECONDS))
            assertTrue(takeoverAttempting.await(2, TimeUnit.SECONDS))
            assertFalse(
                "LAN takeover crossed the active Telegram mutation",
                takeoverCompleted.await(150, TimeUnit.MILLISECONDS),
            )
            releaseLease.countDown()
            assertTrue(takeoverCompleted.await(2, TimeUnit.SECONDS))
            mutation.get(2, TimeUnit.SECONDS)
            takeover.get(2, TimeUnit.SECONDS)
            assertTrue(mutationCompleted.get())
            assertEquals(
                LanConnectionState.CONNECTED,
                LanSyncBus.activeSessionSnapshot()?.session?.connectionState,
            )
        } finally {
            releaseLease.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            LanSyncBus.clearConnectionState(bookId)
        }
    }

    @Test
    fun legacyMarkSyncNeverLeaksAttemptGradesOutsideAtomicReviewBundle() {
        val pageLevel = MarkGroup(
            bookId = "book",
            pageNumber = 3,
            anchor = PagePoint(1f, 2f),
            marks = listOf(Mark(attemptNo = 0, color = MarkColor.BLUE)),
        )
        val attemptGrade = pageLevel.copy(
            id = "attempt-grade",
            marks = listOf(Mark(attemptNo = 2, color = MarkColor.RED)),
        )

        assertTrue(isLegacyLanMarkGroup(pageLevel))
        assertTrue(!isLegacyLanMarkGroup(attemptGrade))
        assertTrue(!isLegacyLanMarkGroup(pageLevel.copy(marks = pageLevel.marks + attemptGrade.marks)))
    }

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
