package com.studyink.sync.lan

import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.annotation.engine.AnnotationChange
import com.studyink.annotation.storage.AppliedTeacherReviewReceipt
import com.studyink.annotation.storage.AnnotationPointEncoding
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.annotation.storage.TeacherReviewPublicationOrderDisposition
import com.studyink.annotation.storage.teacherReviewPublicationOrderDisposition
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.Attempt
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.OperationId
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import com.studyink.memo.core.MemoTarget
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import java.util.Base64
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
    fun teacherReviewPublicationOrderFencesCrossTransportRollback() {
        val older = "a".repeat(64)
        val newer = "b".repeat(64)
        fun receipt(publicationId: String, publishedAt: Long) = AppliedTeacherReviewReceipt(
            bookId = "book",
            pageNumber = 93,
            attemptNo = 2,
            publicationId = publicationId,
            resultLayerSha256 = "c".repeat(64),
            markGroupsSha256 = "d".repeat(64),
            appliedAtEpochMillis = 1L,
            publishedAtEpochMillis = publishedAt,
            remotePairId = "pair",
        )

        assertEquals(
            TeacherReviewPublicationOrderDisposition.APPLY,
            teacherReviewPublicationOrderDisposition(null, older, 10L),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.STALE,
            teacherReviewPublicationOrderDisposition(receipt(newer, 20L), older, 10L),
        )
        assertEquals(
            "an unsequenced delayed frame cannot overwrite ordered state",
            TeacherReviewPublicationOrderDisposition.STALE,
            teacherReviewPublicationOrderDisposition(receipt(newer, 20L), older, 0L),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.DUPLICATE_VERIFY,
            teacherReviewPublicationOrderDisposition(receipt(newer, 20L), newer, 20L),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.CONFLICT,
            teacherReviewPublicationOrderDisposition(receipt(newer, 20L), older, 20L),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.APPLY,
            teacherReviewPublicationOrderDisposition(receipt(older, 10L), newer, 20L),
        )
    }

    @Test
    fun legacyReceiptCanUpgradeButSameLegacyPublicationIsVerified() {
        val legacy = "c".repeat(64)
        val ordered = "d".repeat(64)
        val receipt = AppliedTeacherReviewReceipt(
            bookId = "book",
            pageNumber = 93,
            attemptNo = 2,
            publicationId = legacy,
            resultLayerSha256 = "e".repeat(64),
            markGroupsSha256 = "f".repeat(64),
            appliedAtEpochMillis = 1L,
            publishedAtEpochMillis = 0L,
            remotePairId = "pair",
        )

        assertEquals(
            TeacherReviewPublicationOrderDisposition.APPLY,
            teacherReviewPublicationOrderDisposition(receipt, legacy, 0L),
        )
        assertEquals(
            TeacherReviewPublicationOrderDisposition.APPLY,
            teacherReviewPublicationOrderDisposition(receipt, ordered, 30L),
        )
        assertEquals(
            "legacy receipts have no cross-transport order, so another legacy frame remains compatible",
            TeacherReviewPublicationOrderDisposition.APPLY,
            teacherReviewPublicationOrderDisposition(receipt, ordered, 0L),
        )
    }

    @Test
    fun exactTeacherReviewGradeSliceDoesNotMixPagesBooksOrAttempts() {
        val target = MarkGroup(
            id = "group",
            bookId = "book-a",
            pageNumber = 93,
            anchor = PagePoint(1f, 2f),
            marks = listOf(
                Mark(attemptNo = 1, color = MarkColor.RED, gradedAtEpochMillis = 10L),
                Mark(attemptNo = 2, color = MarkColor.BLUE, gradedAtEpochMillis = 20L),
            ),
            createdAtEpochMillis = 1L,
        )
        val otherPage = target.copy(id = "other-page", pageNumber = 94)
        val otherBook = target.copy(id = "other-book", bookId = "book-b")

        val exact = exactLanTeacherReviewMarkGroups(
            listOf(target, otherPage, otherBook),
            "book-a",
            93,
            2,
        )

        assertEquals(listOf("group"), exact.map(MarkGroup::id))
        assertEquals(listOf(2), exact.single().marks.map(Mark::attemptNo))
    }

    @Test
    fun duplicateTeacherReviewRequiresIncomingMetadataOrNewer() {
        val incoming = MarkGroup(
            id = "group",
            bookId = "book",
            pageNumber = 93,
            anchor = PagePoint(20f, 30f),
            marks = listOf(Mark(attemptNo = 2, color = MarkColor.RED)),
            createdAtEpochMillis = 1L,
            syncRevision = 2L,
            lastModifiedByDeviceId = "teacher",
        )

        assertTrue(lanTeacherReviewMetadataCoversIncoming(listOf(incoming), listOf(incoming)))
        assertTrue(
            "later-attempt metadata must not be rolled back by an older duplicate",
            lanTeacherReviewMetadataCoversIncoming(
                listOf(incoming.copy(syncRevision = 3L, lastModifiedByDeviceId = "student")),
                listOf(incoming),
            ),
        )
        assertFalse(
            "restored older metadata must enter the exact replacement repair path",
            lanTeacherReviewMetadataCoversIncoming(
                listOf(incoming.copy(syncRevision = 1L)),
                listOf(incoming),
            ),
        )
        assertFalse(lanTeacherReviewMetadataCoversIncoming(emptyList(), listOf(incoming)))
    }

    @Test
    fun helloAdvertisesCompactInkWithoutReplacingExistingCapabilities() {
        assertTrue(LAN_CAPABILITY_GPT_EXPLANATION_V2 in lanCapabilities())
        assertTrue(LAN_CAPABILITY_TEACHER_REVIEW_STATE_V1 in lanCapabilities())
        assertTrue(LAN_CAPABILITY_STUDENT_MEMO_V1 in lanCapabilities())
        assertTrue(LAN_CAPABILITY_ANNOTATION_Q16_DELTA_V1 in lanCapabilities())
        assertEquals(lanCapabilities().size, lanCapabilities().distinct().size)
    }

    @Test
    fun compactInkRequiresBothPeersAndOtherwiseFallsBackToLegacy() {
        val compact = setOf(LAN_CAPABILITY_ANNOTATION_Q16_DELTA_V1)

        assertEquals(
            AnnotationPointEncoding.COMPACT_Q16_DELTA,
            negotiatedLanAnnotationPointEncoding(compact, compact),
        )
        assertEquals(
            AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
            negotiatedLanAnnotationPointEncoding(compact, emptySet()),
        )
        assertEquals(
            AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
            negotiatedLanAnnotationPointEncoding(emptySet(), compact),
        )
    }

    @Test
    fun negotiatedCompactAndLegacyInkBothRoundTripThroughTheLanReceiverDecoder() {
        val sourceRoot = Files.createTempDirectory("masternote-lan-compact-source").toFile()
        val compactTargetRoot = Files.createTempDirectory("masternote-lan-compact-target").toFile()
        val legacyTargetRoot = Files.createTempDirectory("masternote-lan-legacy-target").toFile()
        try {
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            val points = List(1_024) { index ->
                PagePoint(
                    x = 20f + index.toFloat() / 16f,
                    y = 30f + (index % 11).toFloat() / 16f,
                )
            }
            val expectedStroke = AnnotationDocument(AnnotationSnapshot.empty("book", 3))
                .addStroke(
                    StrokeAsset(
                        pageNumber = 3,
                        tool = StrokeTool.PEN,
                        colorArgb = 0xFF17233C.toInt(),
                        width = 3f,
                        points = points,
                        authorId = "student",
                        attemptNo = 1,
                        deviceId = "student-device",
                    ),
                )
                .also(source::append)
                .addedAssets
                .single()

            fun payloadFor(peerCapabilities: Set<String>): ByteArray = source
                .encodedStudentOperationsAfter(
                    bookId = "book",
                    pageNumber = 3,
                    originDeviceId = "student-device",
                    logicalClock = 0L,
                    pointEncoding = negotiatedLanAnnotationPointEncoding(
                        localCapabilities = lanCapabilities(),
                        peerCapabilities = peerCapabilities,
                    ),
                )
                .single()

            val compactPayload = payloadFor(setOf(LAN_CAPABILITY_ANNOTATION_Q16_DELTA_V1))
            val legacyPayload = payloadFor(emptySet())
            assertTrue(compactPayload.size < legacyPayload.size)

            listOf(
                compactPayload to PageOperationLogStore(compactTargetRoot),
                legacyPayload to PageOperationLogStore(legacyTargetRoot),
            ).forEach { (payload, target) ->
                val line = LanWire.message("OPERATION") {
                    put("page", 3)
                    put("payload", Base64.getEncoder().encodeToString(payload))
                }
                val receivedPayload = Base64.getDecoder().decode(
                    LanWire.decode(line).getString("payload"),
                )
                assertEquals("student-device", target.operationCursor(receivedPayload).deviceId)
                assertEquals(1L, target.operationCursor(receivedPayload).logicalClock)

                target.appendEncodedStudentOperation("book", 3, receivedPayload)
                assertEquals(expectedStroke, target.loadPage("book", 3).activeStrokes.single())
            }
        } finally {
            sourceRoot.deleteRecursively()
            compactTargetRoot.deleteRecursively()
            legacyTargetRoot.deleteRecursively()
        }
    }

    @Test
    fun negotiatedCompactCarriesHistoricalSingleStrokeBelowLanLimitsWithoutFloatLoss() {
        assertHistoricalCompactLanRoundTrip(
            pageNumber = 8,
            pointCounts = listOf(17_462),
        )
    }

    @Test
    fun negotiatedCompactCarriesHistoricalMultiAssetOperationBelowLanLimitsWithoutFloatLoss() {
        assertHistoricalCompactLanRoundTrip(
            pageNumber = 73,
            // Mirrors the measured 634 KiB operation: 121 assets, 9,766 total points, max 321.
            pointCounts = listOf(321, 163) + List(119) { 78 },
        )
    }

    @Test
    fun teacherReviewMismatchLatchIsPageAndConnectionScoped() {
        val latch = LanTeacherReviewMismatchLatch()
        val expected = "a".repeat(64)
        val observed = "b".repeat(64)

        assertTrue(latch.shouldRepair(1L, 93, expected, observed))
        assertFalse(latch.shouldRepair(1L, 93, expected, observed))
        assertTrue("another page must reconcile independently", latch.shouldRepair(1L, 94, expected, observed))
        assertTrue("a reconnect must retry unresolved evidence", latch.shouldRepair(2L, 93, expected, observed))
    }

    @Test
    fun matchingTeacherReviewStateClearsOnlyItsPageLatch() {
        val latch = LanTeacherReviewMismatchLatch()
        val expected = "c".repeat(64)
        val observed = "d".repeat(64)
        assertTrue(latch.shouldRepair(1L, 93, expected, observed))
        assertTrue(latch.shouldRepair(1L, 94, expected, observed))

        assertFalse(latch.shouldRepair(1L, 93, expected, expected))
        assertTrue("a later rollback must be repairable", latch.shouldRepair(1L, 93, expected, observed))
        assertFalse("matching page 93 must not reset page 94", latch.shouldRepair(1L, 94, expected, observed))

        latch.clearPage(94)
        assertTrue(latch.shouldRepair(1L, 94, expected, observed))
    }

    @Test
    fun staleRepairFailureCannotClearNewerTeacherReviewMismatch() {
        val latch = LanTeacherReviewMismatchLatch()
        val oldExpected = "e".repeat(64)
        val newExpected = "f".repeat(64)
        val observed = "0".repeat(64)
        assertTrue(latch.shouldRepair(1L, 93, oldExpected, observed))
        assertTrue(latch.shouldRepair(1L, 93, newExpected, observed))

        latch.clearIfMatches(1L, 93, oldExpected, observed)
        assertFalse(latch.shouldRepair(1L, 93, newExpected, observed))
        latch.clearIfMatches(1L, 93, newExpected, observed)
        assertTrue(latch.shouldRepair(1L, 93, newExpected, observed))
    }

    @Test
    fun gptAckMustMatchEveryDurablePublicationIdentityField() {
        val expected = arrayOf(
            "a".repeat(64), "b".repeat(64), "c".repeat(64),
        )
        fun matches(
            publicationId: String = expected[0],
            page: Int = 93,
            attempt: Int = 4,
            revision: Long = 7L,
            digest: String = expected[1],
            authority: String = expected[2],
        ) = isExactLanGptExplanationAck(
            expected[0], 93, 4, 7L, expected[1], expected[2],
            publicationId, page, attempt, revision, digest, authority,
        )

        assertTrue(matches())
        assertFalse(matches(publicationId = "d".repeat(64)))
        assertFalse(matches(page = 94))
        assertFalse(matches(attempt = 3))
        assertFalse(matches(revision = 8L))
        assertFalse(matches(digest = "e".repeat(64)))
        assertFalse(matches(authority = "f".repeat(64)))
    }

    @Test
    fun publishedReviewTargetsExactOpenOrSubmittedAttempt() {
        val open = Attempt("book-a", pageNumber = 93, attemptNo = 4, locked = false)

        assertTrue(isExactLanTeacherReviewAttempt(listOf(open), "book-a", 93, 4))
        assertTrue(isExactLanTeacherReviewAttempt(listOf(open.copy(locked = true)), "book-a", 93, 4))
        assertFalse(isExactLanTeacherReviewAttempt(listOf(open), "book-a", 93, 3))
        assertFalse(isExactLanTeacherReviewAttempt(listOf(open), "book-a", 92, 4))
        assertFalse(isExactLanTeacherReviewAttempt(listOf(open), "book-b", 93, 4))
    }

    @Test
    fun teacherReviewStateAdvertisesOnlyExactStudentPageAttempts() {
        val attempts = listOf(
            Attempt("book-a", pageNumber = 93, attemptNo = 4, locked = false),
            Attempt("book-a", pageNumber = 93, attemptNo = 2, locked = true),
            Attempt("book-a", pageNumber = 94, attemptNo = 9, locked = false),
            Attempt("book-b", pageNumber = 93, attemptNo = 8, locked = false),
        )

        assertEquals(listOf(2, 4), exactLanStudentAttemptNos(attempts, "book-a", 93))
    }

    @Test
    fun memoCatchUpTargetsOnlyCurrentCatalogAttemptsOnTheSubscribedPage() {
        val attempts = listOf(
            Attempt("book-a", pageNumber = 93, attemptNo = 4, locked = false),
            Attempt("book-a", pageNumber = 93, attemptNo = 2, locked = true),
            Attempt("book-a", pageNumber = 93, attemptNo = 4, locked = true),
            Attempt("book-a", pageNumber = 94, attemptNo = 9, locked = false),
            Attempt("book-b", pageNumber = 93, attemptNo = 8, locked = false),
        )

        assertEquals(
            listOf(
                MemoTarget("book-a", pageNumber = 93, attemptNo = 2),
                MemoTarget("book-a", pageNumber = 93, attemptNo = 4),
            ),
            exactLanStudentMemoTargets(attempts, "book-a", 93),
        )
    }

    @Test
    fun memoSendQueueCoalescesOneIdentityAndYieldsFairlyToOtherMemos() {
        val queue = LatestLanStudentMemoSendQueue()
        val target = MemoTarget("book-a", pageNumber = 93, attemptNo = 4)
        val first = LanStudentMemoSendKey(target, "memo-a")
        val second = LanStudentMemoSendKey(target, "memo-b")

        assertTrue(queue.offer(first))
        assertFalse(queue.offer(first))
        assertFalse(queue.offer(second))
        assertEquals(listOf(first), queue.takeBatch(1))

        // A new stroke for the in-flight memo is retained, but moves behind the waiting memo.
        assertFalse(queue.offer(first))
        assertTrue(queue.completeBatch())
        assertEquals(listOf(second, first), queue.takeBatch(2))
        assertFalse(queue.completeBatch())

        // Completing the prior drain arms exactly one new worker for the next burst.
        assertTrue(queue.offer(first))
        queue.clear()
        assertTrue(queue.offer(second))
    }

    @Test
    fun memoTransferGateKeepsWholeChunkStreamsContiguous() {
        val gate = LanStudentMemoTransferGate()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val order = mutableListOf<String>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Boolean> {
                gate.serialize {
                    order += "first-0"
                    firstEntered.countDown()
                    check(releaseFirst.await(2, TimeUnit.SECONDS))
                    order += "first-1"
                    true
                }
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val second = executor.submit<Boolean> {
                secondAttempting.countDown()
                gate.serialize {
                    secondEntered.countDown()
                    order += "second-0"
                    order += "second-1"
                    true
                }
            }
            assertTrue(secondAttempting.await(2, TimeUnit.SECONDS))
            assertFalse("a second transfer entered before the first completed", secondEntered.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()

            assertTrue(first.get(2, TimeUnit.SECONDS))
            assertTrue(second.get(2, TimeUnit.SECONDS))
            assertEquals(listOf("first-0", "first-1", "second-0", "second-1"), order)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun teacherReviewStateCacheKeepsRepeatedStrokeReportsOnTheCheapPath() {
        val cache = LanTeacherReviewStateDigestCache()
        val digest = "1".repeat(64)
        val missing = cache.lookup(93, listOf(1, 2), 100L, 30_000L)
        assertNull(missing.digestSha256)
        assertTrue(missing.shouldRefresh)

        val refresh = requireNotNull(cache.beginRefresh(93, listOf(2, 1), 1L))
        assertTrue(cache.complete(refresh, digest, 100L))
        repeat(100) { stroke ->
            val cached = cache.lookup(93, listOf(1, 2), 101L + stroke, 30_000L)
            assertEquals(digest, cached.digestSha256)
            assertFalse("ordinary strokes must not request a full rehash", cached.shouldRefresh)
        }

        val stale = cache.lookup(93, listOf(1, 2), 30_100L, 30_000L)
        assertEquals("stale evidence remains available while refreshing", digest, stale.digestSha256)
        assertTrue(stale.shouldRefresh)
        val changedAttempts = cache.lookup(93, listOf(1, 2, 3), 200L, 30_000L)
        assertNull(changedAttempts.digestSha256)
        assertTrue(changedAttempts.shouldRefresh)
    }

    @Test
    fun teacherReviewStateCacheRejectsRefreshThatRacedAnApplyOrReconnect() {
        val cache = LanTeacherReviewStateDigestCache()
        val digest = "2".repeat(64)
        val beforeApply = requireNotNull(cache.beginRefresh(93, listOf(1), 1L))
        cache.invalidate(93)
        assertFalse(cache.complete(beforeApply, digest, 10L))
        assertNull(cache.lookup(93, listOf(1), 11L, 30_000L).digestSha256)

        val beforeReconnect = requireNotNull(cache.beginRefresh(93, listOf(1), 1L))
        cache.clear()
        assertFalse(cache.complete(beforeReconnect, digest, 12L))
        val afterReconnect = requireNotNull(cache.beginRefresh(93, listOf(1), 2L))
        assertTrue(cache.complete(afterReconnect, digest, 13L))
    }

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
    fun teacherReviewPayloadChunkingPreservesEmptyExactBoundaryAndTailBytes() {
        assertTrue(splitLanTeacherReviewPayload(byteArrayOf(), 4).isEmpty())

        val exact = byteArrayOf(0, 1, 2, 3)
        val exactChunks = splitLanTeacherReviewPayload(exact, 4)
        assertEquals(1, exactChunks.size)
        assertTrue(exact.contentEquals(exactChunks.single()))

        val payload = ByteArray(10) { it.toByte() }
        val chunks = splitLanTeacherReviewPayload(payload, 4)
        assertEquals(listOf(4, 4, 2), chunks.map(ByteArray::size))
        assertTrue(payload.contentEquals(chunks.fold(ByteArray(0)) { all, chunk -> all + chunk }))

        payload[0] = 99
        assertEquals(0, chunks.first().first().toInt())
        assertThrows(IllegalArgumentException::class.java) {
            splitLanTeacherReviewPayload(byteArrayOf(1), 0)
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
    fun heartbeatSilenceDoesNotCloseAProgressingBoundedCatchUp() {
        assertFalse(isLanHeartbeatSilenceExpired(100L, 109L, 120L, 8L))
        assertTrue(isLanHeartbeatSilenceExpired(100L, 109L, 0L, 8L))
        assertFalse(isLanHeartbeatSilenceExpired(100L, 107L, 0L, 8L))
    }

    @Test
    fun invalidOperationClosesBeforeAFollowingPageSyncedCanClaimReady() {
        assertTrue(mustCloseLanConnectionAfterFailure("OPERATION", authenticated = true))
        assertTrue(mustCloseLanConnectionAfterFailure("PAGE_SYNCED", authenticated = true))
        assertTrue(mustCloseLanConnectionAfterFailure("STUDENT_MEMO_CHUNK", authenticated = true))
        assertTrue(mustCloseLanConnectionAfterFailure("PING", authenticated = false))
        assertFalse(mustCloseLanConnectionAfterFailure("PING", authenticated = true))
        assertFalse(
            "a stale review attempt must be dropped without a reconnect loop",
            mustCloseLanConnectionAfterFailure("TEACHER_REVIEW_CHUNK", authenticated = true),
        )
        assertFalse(mustCloseLanConnectionAfterFailure("TEACHER_REVIEW_REJECT", authenticated = true))
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
    fun serviceSessionRoleIsStickyUntilThatSessionIsCleared() {
        val bookId = "role-book-${UUID.randomUUID()}"
        try {
            assertNull(LanSyncBus.sessionRole(bookId))

            LanSyncBus.sessionRoleChanged(bookId, LanPeerRole.STUDENT_SERVER)
            assertEquals(LanPeerRole.STUDENT_SERVER, LanSyncBus.sessionRole(bookId))

            LanSyncBus.sessionRoleChanged(bookId, LanPeerRole.TEACHER_CLIENT)
            assertEquals(LanPeerRole.TEACHER_CLIENT, LanSyncBus.sessionRole(bookId))

            LanSyncBus.clearConnectionState(bookId)
            assertNull(LanSyncBus.sessionRole(bookId))
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
    fun catchUpYieldRequestIsExactAndRunsOutsideTheBusMonitor() {
        val bookId = "book-${UUID.randomUUID()}"
        var receivedBookId: String? = null
        var anotherThreadCouldReadState = false
        val listener = object : LanSyncBus.Listener {
            override fun onCatchUpYieldRequested(bookId: String) {
                receivedBookId = bookId
                val completed = CountDownLatch(1)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    executor.execute {
                        LanSyncBus.sessionSnapshot(bookId)
                        completed.countDown()
                    }
                    anotherThreadCouldReadState = completed.await(2, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
        LanSyncBus.addListener(listener)
        try {
            LanSyncBus.requestCatchUpYield(bookId)
            assertEquals(bookId, receivedBookId)
            assertTrue(anotherThreadCouldReadState)
        } finally {
            LanSyncBus.removeListener(listener)
        }
    }

    @Test
    fun readyPublicationLosesToCommittedYieldAndRejectsOldSocketGeneration() {
        assertTrue(canPublishLanReady(7L, 7L, -1L))
        assertFalse(canPublishLanReady(7L, 7L, 7L))
        assertFalse(canPublishLanReady(6L, 7L, -1L))
        assertTrue(canPublishLanReady(8L, 8L, 7L))
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

    private fun assertHistoricalCompactLanRoundTrip(pageNumber: Int, pointCounts: List<Int>) {
        val sourceRoot = Files.createTempDirectory("masternote-lan-historical-source").toFile()
        val targetRoot = Files.createTempDirectory("masternote-lan-historical-target").toFile()
        try {
            val bookId = "historical-book"
            val deviceId = "student-device"
            val source = PageOperationLogStore(sourceRoot, checkpointInterval = 10_000)
            val target = PageOperationLogStore(targetRoot, checkpointInterval = 10_000)
            val assets = pointCounts.mapIndexed { assetIndex, pointCount ->
                StrokeAsset(
                    id = StrokeId("historical-$pageNumber-$assetIndex"),
                    pageNumber = pageNumber,
                    tool = StrokeTool.PEN,
                    colorArgb = 0xFF17233C.toInt(),
                    width = 2.75f,
                    points = historicalFloatPoints(assetIndex, pointCount),
                    authorId = "student",
                    attemptNo = 1,
                    logicalClock = 1L,
                    deviceId = deviceId,
                    createdAtEpochMillis = assetIndex + 1L,
                )
            }
            val operation = AssetOperation(
                id = OperationId("historical-operation-$pageNumber"),
                removedStrokeIds = emptySet(),
                addedStrokeIds = assets.mapTo(linkedSetOf(), StrokeAsset::id),
                logicalClock = 1L,
                deviceId = deviceId,
            )
            source.append(
                AnnotationChange(
                    snapshot = AnnotationSnapshot(
                        bookId = bookId,
                        pageNumber = pageNumber,
                        revision = 1L,
                        assets = assets.associateBy(StrokeAsset::id),
                        activeStrokeIds = operation.addedStrokeIds,
                        appliedOperationIds = setOf(operation.id),
                    ),
                    operation = operation,
                    addedAssets = assets,
                ),
            )

            val payload = source.encodedStudentOperationsAfter(
                bookId = bookId,
                pageNumber = pageNumber,
                originDeviceId = deviceId,
                logicalClock = 0L,
                pointEncoding = negotiatedLanAnnotationPointEncoding(
                    localCapabilities = lanCapabilities(),
                    peerCapabilities = setOf(LAN_CAPABILITY_ANNOTATION_Q16_DELTA_V1),
                ),
            ).single()
            assertEquals(deviceId, source.operationCursor(payload).deviceId)
            assertEquals(1L, source.operationCursor(payload).logicalClock)

            val line = LanWire.message("OPERATION") {
                put("page", pageNumber)
                put("payload", Base64.getEncoder().encodeToString(payload))
            }
            assertTrue(
                "${line.length}-character historical operation exceeds the LAN line limit",
                line.length <= LanWire.MAX_LINE_CHARS,
            )
            val receivedPayload = Base64.getDecoder().decode(
                LanWire.decode(line).getString("payload"),
            )
            assertTrue(payload.contentEquals(receivedPayload))
            assertEquals(deviceId, target.operationCursor(receivedPayload).deviceId)
            assertEquals(1L, target.operationCursor(receivedPayload).logicalClock)

            target.appendEncodedStudentOperation(bookId, pageNumber, receivedPayload)
            val receivedAssets = target.loadPage(bookId, pageNumber).activeStrokes
                .associateBy(StrokeAsset::id)
            assertEquals(assets.map(StrokeAsset::id).toSet(), receivedAssets.keys)
            assets.forEach { expected ->
                val actual = requireNotNull(receivedAssets[expected.id])
                assertEquals(expected, actual)
                expected.points.zip(actual.points).forEach { (expectedPoint, actualPoint) ->
                    assertEquals(expectedPoint.x.toRawBits(), actualPoint.x.toRawBits())
                    assertEquals(expectedPoint.y.toRawBits(), actualPoint.y.toRawBits())
                    assertEquals(expectedPoint.pressure.toRawBits(), actualPoint.pressure.toRawBits())
                }
            }
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    /** Representative old ink: pressure varies and every coordinate is halfway off the Q16 grid. */
    private fun historicalFloatPoints(assetIndex: Int, pointCount: Int): List<PagePoint> =
        List(pointCount) { pointIndex ->
            PagePoint(
                x = 12.03125f + assetIndex * 0.5f + (pointIndex % 257) * 0.125f,
                y = 24.03125f + assetIndex * 0.25f + (pointIndex / 257) * 0.0625f,
                pressure = 0.25f + (pointIndex % 47) * 0.015625f,
            )
        }
}
