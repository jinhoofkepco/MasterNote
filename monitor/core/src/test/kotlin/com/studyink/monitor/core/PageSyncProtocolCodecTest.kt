package com.studyink.monitor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSyncProtocolCodecTest {
    @Test fun manifestRoundTripsGenerationPageIdentityAttemptsSubmissionsAndCursor() {
        val mutableAttemptNos = mutableListOf(1, 2, 4)
        val mutableSubmittedAttemptNos = mutableListOf(2, 4)
        val mutableEntries = mutableListOf(
            entry(
                attemptNos = mutableAttemptNos,
                submittedAttemptNos = mutableSubmittedAttemptNos,
                approxBytes = 24_000L,
            ),
        )
        val original = manifest(
            syncGeneration = 7L,
            sequence = 23L,
            currentCursor = PageSyncCursor(
                sequence = 23L,
                pageToken = PAGE_TOKEN,
                pageNumber = 37,
                currentAttemptNo = 4,
                revision = 91L,
            ),
            entries = mutableEntries,
        )
        mutableAttemptNos += 5
        mutableSubmittedAttemptNos.clear()
        mutableEntries.clear()
        assertThrows(UnsupportedOperationException::class.java) {
            (original.entries as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (original.entries.single().submittedAttemptNos as MutableList).clear()
        }

        val encoded = RemoteReviewDocumentCodec.encode(original)
        val decoded = roundTrip(original) as PageSyncManifestEnvelope
        val decodedEntry = decoded.entries.single()

        assertEquals(6, encoded.wireTypeCode())
        assertEquals(RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST, decoded.type)
        assertEquals(7L, decoded.syncGeneration)
        assertEquals(23L, decoded.sequence)
        assertEquals(23L, decoded.currentCursor?.sequence)
        assertEquals(4, decoded.currentCursor?.currentAttemptNo)
        assertEquals(listOf(1, 2, 4), decodedEntry.attemptNos)
        assertEquals(listOf(2, 4), decodedEntry.submittedAttemptNos)
        assertTrue(decodedEntry.submitted)
        assertEquals(WORKBOOK_TOKEN, decodedEntry.workbookToken)
        assertEquals(CONTENT_SHA, decodedEntry.contentSha256)
        assertEquals(STUDENT_LAYER_SHA, decodedEntry.studentLayerSha256)
        assertEquals(37, decodedEntry.pageNumber)
        assertEquals(91L, decodedEntry.revision)
        assertEquals(12_000L, decodedEntry.lastChangedEpochMs)
        assertEquals(24_000L, decodedEntry.approxBytes)
        assertEquals(1, decoded.inventoryPageCount)
    }

    @Test fun fortyEightWorstCaseAttemptRowsStayInsideOperationalDocumentLimit() {
        val attempts = (1..RemoteReviewLimits.MAX_PAGE_SYNC_ATTEMPTS_PER_PAGE).toList()
        val entries = (1..48).map { index ->
            entry(
                pageToken = "page_token_${index.toString().padStart(8, '0')}",
                pageNumber = index,
                attemptNos = attempts,
                submittedAttemptNos = attempts,
            )
        }
        val first = entries.first()
        val encoded = RemoteReviewDocumentCodec.encode(
            manifest(
                sequence = 1L,
                currentCursor = PageSyncCursor(
                    sequence = 1L,
                    pageToken = first.pageToken,
                    pageNumber = first.pageNumber,
                    currentAttemptNo = attempts.last(),
                    revision = first.revision,
                ),
                entries = entries,
            ),
        )

        assertTrue(encoded.sizeBytes < RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES)
    }

    @Test fun emptyManifestAndFullPageOrSingleAttemptRequestsRoundTrip() {
        val emptyManifest = manifest(currentCursor = null, entries = emptyList())
        val legacyManifestWithoutInventoryCount = manifest(inventoryPageCount = null)
        val fullPageRequest = request(attemptNo = null)
        val attemptRequest = request(
            transferId = "request_transfer_0002",
            attemptNo = 3,
            syncGeneration = 8L,
        )

        val decodedManifest = roundTrip(emptyManifest) as PageSyncManifestEnvelope
        val decodedLegacy = roundTrip(legacyManifestWithoutInventoryCount) as PageSyncManifestEnvelope
        val decodedFull = roundTrip(fullPageRequest) as PageSyncRequestEnvelope
        val decodedAttempt = roundTrip(attemptRequest) as PageSyncRequestEnvelope

        assertNull(decodedManifest.currentCursor)
        assertTrue(decodedManifest.entries.isEmpty())
        assertNull(decodedLegacy.inventoryPageCount)
        assertEquals(PAGE_TOKEN, decodedLegacy.entries.single().pageToken)
        assertEquals(7, RemoteReviewDocumentCodec.encode(fullPageRequest).wireTypeCode())
        assertNull(decodedFull.attemptNo)
        assertEquals(3, decodedAttempt.attemptNo)
        assertEquals(8L, decodedAttempt.syncGeneration)
        assertEquals(fullPageRequest, decodedFull)
    }

    @Test fun studentDeltaRoundTripsRequestAndOriginIdentityAndOwnsMutableInputs() {
        val originalPayload = "delta-one".toByteArray()
        val attempts = mutableListOf(1, 2)
        val original = PageAnnotationEnvelope.fromDecodedPayload(
            transferId = "annotation_transfer_0001",
            createdAtEpochMs = 20_000L,
            syncGeneration = 7L,
            purpose = PageAnnotationPurpose.STUDENT_PAGE,
            responseToTransferId = REQUEST_TRANSFER_ID,
            pageToken = PAGE_TOKEN,
            pageNumber = 37,
            attemptNos = attempts,
            kind = PageAnnotationKind.DELTA,
            baseRevision = 91L,
            sourceRevision = 92L,
            deltaOriginDeviceId = ORIGIN_DEVICE_ID,
            baseOriginCursor = 11L,
            sourceOriginCursor = 17L,
            compression = PageAnnotationCompression.NONE,
            decodedPayloadBytes = originalPayload,
            resultLayerSha256 = RESULT_LAYER_SHA,
        )
        originalPayload[0] = 0
        attempts += 3
        assertThrows(UnsupportedOperationException::class.java) {
            (original.attemptNos as MutableList).add(3)
        }
        val leakedCopy = original.copyPayloadBytes()
        leakedCopy[0] = 1

        val encoded = RemoteReviewDocumentCodec.encode(original)
        val decoded = roundTrip(original) as PageAnnotationEnvelope

        assertEquals(8, encoded.wireTypeCode())
        assertEquals(7L, decoded.syncGeneration)
        assertEquals(PageAnnotationPurpose.STUDENT_PAGE, decoded.purpose)
        assertEquals(REQUEST_TRANSFER_ID, decoded.responseToTransferId)
        assertEquals(PageAnnotationKind.DELTA, decoded.kind)
        assertEquals(PageAnnotationCompression.NONE, decoded.compression)
        assertEquals(listOf(1, 2), decoded.attemptNos)
        assertEquals(91L, decoded.baseRevision)
        assertEquals(92L, decoded.sourceRevision)
        assertEquals(ORIGIN_DEVICE_ID, decoded.deltaOriginDeviceId)
        assertEquals(11L, decoded.baseOriginCursor)
        assertEquals(17L, decoded.sourceOriginCursor)
        assertArrayEquals("delta-one".toByteArray(), decoded.copyPayloadBytes())
        assertArrayEquals("delta-one".toByteArray(), decoded.copyDecodedPayloadBytes())
        assertEquals(pageAnnotationSha256Hex("delta-one".toByteArray()), decoded.payloadSha256)
        assertEquals(RESULT_LAYER_SHA, decoded.resultLayerSha256)
    }

    @Test fun teacherCheckpointRoundTripsWithoutRequestOrOriginAndWithCanonicalDigest() {
        val canonical = ByteArray(300_000) { index -> (index % 17).toByte() }
        val original = annotation(
            transferId = "checkpoint_transfer_0001",
            purpose = PageAnnotationPurpose.TEACHER_REVIEW,
            responseToTransferId = null,
            attemptNos = listOf(5),
            kind = PageAnnotationKind.CHECKPOINT,
            compression = PageAnnotationCompression.GZIP,
            payload = canonical,
            sourceRevision = 150L,
        )

        val decoded = roundTrip(original) as PageAnnotationEnvelope

        assertEquals(PageAnnotationPurpose.TEACHER_REVIEW, decoded.purpose)
        assertNull(decoded.responseToTransferId)
        assertNull(decoded.deltaOriginDeviceId)
        assertEquals(0L, decoded.baseOriginCursor)
        assertEquals(0L, decoded.sourceOriginCursor)
        assertEquals(PageAnnotationCompression.GZIP, decoded.compression)
        assertTrue(decoded.payloadSizeBytes < canonical.size)
        assertArrayEquals(canonical, decoded.copyDecodedPayloadBytes())
        assertEquals(pageAnnotationSha256Hex(canonical), decoded.payloadSha256)
    }

    @Test fun studentCheckpointFragmentRoundTripsGroupOrderAndBothDigests() {
        val whole = "first-fragment|second-fragment".toByteArray()
        val fragment = "second-fragment".toByteArray()
        val original = PageAnnotationEnvelope.fromDecodedPayload(
            transferId = "checkpoint_chunk_transfer_0002",
            createdAtEpochMs = 20_000L,
            syncGeneration = 7L,
            purpose = PageAnnotationPurpose.STUDENT_PAGE,
            responseToTransferId = REQUEST_TRANSFER_ID,
            pageToken = PAGE_TOKEN,
            pageNumber = 37,
            attemptNos = listOf(1, 2),
            kind = PageAnnotationKind.CHECKPOINT,
            baseRevision = 0L,
            sourceRevision = 92L,
            deltaOriginDeviceId = null,
            baseOriginCursor = 0L,
            sourceOriginCursor = 0L,
            compression = PageAnnotationCompression.GZIP,
            decodedPayloadBytes = fragment,
            resultLayerSha256 = RESULT_LAYER_SHA,
            chunkGroupId = "checkpoint_group_transfer_0001",
            chunkIndex = 1,
            chunkCount = 2,
            assembledPayloadSizeBytes = whole.size,
            assembledPayloadSha256 = pageAnnotationSha256Hex(whole),
        )

        val decoded = roundTrip(original) as PageAnnotationEnvelope

        assertTrue(decoded.chunked)
        assertEquals("checkpoint_group_transfer_0001", decoded.chunkGroupId)
        assertEquals(1, decoded.chunkIndex)
        assertEquals(2, decoded.chunkCount)
        assertEquals(whole.size, decoded.assembledPayloadSizeBytes)
        assertEquals(pageAnnotationSha256Hex(whole), decoded.payloadSha256)
        assertEquals(pageAnnotationSha256Hex(fragment), decoded.chunkSha256)
        assertArrayEquals(fragment, decoded.copyDecodedPayloadBytes())
    }

    @Test fun eightStudentCheckpointFragmentsMayDeclareTheirExactMaximumAssembly() {
        assertEquals(
            RemoteReviewLimits.MAX_PAGE_ANNOTATION_CHUNKS *
                RemoteReviewLimits.MAX_PAGE_ANNOTATION_CHECKPOINT_BYTES,
            RemoteReviewLimits.MAX_PAGE_ANNOTATION_ASSEMBLED_BYTES,
        )
        val fragment = "bounded-fragment".toByteArray()
        val original = PageAnnotationEnvelope.fromDecodedPayload(
            transferId = "checkpoint_chunk_transfer_0008",
            createdAtEpochMs = 20_000L,
            syncGeneration = 7L,
            purpose = PageAnnotationPurpose.STUDENT_PAGE,
            responseToTransferId = REQUEST_TRANSFER_ID,
            pageToken = PAGE_TOKEN,
            pageNumber = 37,
            attemptNos = listOf(1),
            kind = PageAnnotationKind.CHECKPOINT,
            baseRevision = 0L,
            sourceRevision = 92L,
            deltaOriginDeviceId = null,
            baseOriginCursor = 0L,
            sourceOriginCursor = 0L,
            compression = PageAnnotationCompression.GZIP,
            decodedPayloadBytes = fragment,
            resultLayerSha256 = RESULT_LAYER_SHA,
            chunkGroupId = "checkpoint_group_transfer_0008",
            chunkIndex = 7,
            chunkCount = 8,
            assembledPayloadSizeBytes = RemoteReviewLimits.MAX_PAGE_ANNOTATION_ASSEMBLED_BYTES,
            assembledPayloadSha256 = "d".repeat(64),
        )

        val decoded = roundTrip(original) as PageAnnotationEnvelope
        assertEquals(8, decoded.chunkCount)
        assertEquals(7, decoded.chunkIndex)
        assertEquals(RemoteReviewLimits.MAX_PAGE_ANNOTATION_ASSEMBLED_BYTES,
            decoded.assembledPayloadSizeBytes)

        assertValidationField("assembledPayloadSizeBytes") {
            PageAnnotationEnvelope.fromDecodedPayload(
                transferId = "checkpoint_chunk_transfer_over",
                createdAtEpochMs = 20_000L,
                syncGeneration = 7L,
                purpose = PageAnnotationPurpose.STUDENT_PAGE,
                responseToTransferId = REQUEST_TRANSFER_ID,
                pageToken = PAGE_TOKEN,
                pageNumber = 37,
                attemptNos = listOf(1),
                kind = PageAnnotationKind.CHECKPOINT,
                baseRevision = 0L,
                sourceRevision = 92L,
                deltaOriginDeviceId = null,
                baseOriginCursor = 0L,
                sourceOriginCursor = 0L,
                compression = PageAnnotationCompression.GZIP,
                decodedPayloadBytes = fragment,
                resultLayerSha256 = RESULT_LAYER_SHA,
                chunkGroupId = "checkpoint_group_transfer_over",
                chunkIndex = 0,
                chunkCount = 8,
                assembledPayloadSizeBytes = RemoteReviewLimits.MAX_PAGE_ANNOTATION_ASSEMBLED_BYTES + 1,
                assembledPayloadSha256 = "e".repeat(64),
            )
        }
    }

    @Test fun pageSyncAckRoundTripsGenerationSourceTypeExactPageRevisionAndReason() {
        val annotationApplied = ack()
        val requestRejected = ack(
            transferId = "sync_ack_transfer_0002",
            sourceType = PageSyncAckSourceType.REQUEST,
            sourceTransferId = REQUEST_TRANSFER_ID,
            disposition = PageSyncAckDisposition.REJECTED,
            reasonCode = "BASE_REVISION_MISMATCH",
        )
        val requestDuplicate = ack(
            transferId = "sync_ack_transfer_0003",
            sourceType = PageSyncAckSourceType.REQUEST,
            sourceTransferId = REQUEST_TRANSFER_ID,
            disposition = PageSyncAckDisposition.DUPLICATE,
        )

        assertEquals(annotationApplied, roundTrip(annotationApplied))
        assertEquals(requestRejected, roundTrip(requestRejected))
        assertEquals(requestDuplicate, roundTrip(requestDuplicate))
        assertEquals(7L, (roundTrip(annotationApplied) as PageSyncAckEnvelope).syncGeneration)
        assertEquals(PageSyncAckSourceType.ANNOTATION, annotationApplied.sourceType)
        assertEquals(9, RemoteReviewDocumentCodec.encode(annotationApplied).wireTypeCode())
    }

    @Test fun payloadAndFrameCorruptionAreRejected() {
        assertValidationField("payloadSha256") {
            rawAnnotation(
                payloadBytes = "delta".toByteArray(),
                payloadSha256 = "00".repeat(32),
            )
        }
        assertValidationField("payloadBytes") {
            rawAnnotation(
                compression = PageAnnotationCompression.GZIP,
                payloadBytes = byteArrayOf(1, 2, 3, 4),
                payloadSha256 = "00".repeat(32),
            )
        }

        val corruptedFrame = RemoteReviewDocumentCodec.encode(annotation()).copyBytes()
        corruptedFrame[corruptedFrame.lastIndex] = (corruptedFrame.last().toInt() xor 1).toByte()
        val failure = assertThrows(RemoteReviewCodecException::class.java) {
            RemoteReviewDocumentCodec.decode(corruptedFrame)
        }
        assertEquals(RemoteReviewCodecError.CHECKSUM_MISMATCH, failure.error)
    }

    @Test fun deltaTargetIsAdvisoryButOneMiBIsAHardDecodedAndEncodedLimit() {
        val justAboveTarget = ByteArray(RemoteReviewLimits.PAGE_ANNOTATION_DELTA_TARGET_BYTES + 1) { 7 }
        val recoveryDelta = annotation(payload = justAboveTarget)
        assertArrayEquals(
            justAboveTarget,
            (roundTrip(recoveryDelta) as PageAnnotationEnvelope).copyDecodedPayloadBytes(),
        )

        val exactHard = annotation(
            transferId = "annotation_transfer_hard",
            payload = ByteArray(RemoteReviewLimits.MAX_PAGE_ANNOTATION_DELTA_BYTES) { 3 },
        )
        assertTrue(RemoteReviewDocumentCodec.encode(exactHard).sizeBytes < RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES)

        assertValidationField("payloadBytes") {
            annotation(
                transferId = "annotation_transfer_over",
                payload = ByteArray(RemoteReviewLimits.MAX_PAGE_ANNOTATION_DELTA_BYTES + 1) { 3 },
            )
        }
        assertValidationField("payloadBytes") {
            annotation(
                transferId = "annotation_transfer_bomb",
                compression = PageAnnotationCompression.GZIP,
                payload = ByteArray(RemoteReviewLimits.MAX_PAGE_ANNOTATION_DELTA_BYTES + 1),
            )
        }
    }

    @Test fun checkpointMayExceedDeltaHardLimitButStaysBelowTwoMiBOperationalFrame() {
        val largerThanDelta = annotation(
            transferId = "checkpoint_transfer_large",
            kind = PageAnnotationKind.CHECKPOINT,
            payload = ByteArray(RemoteReviewLimits.MAX_PAGE_ANNOTATION_DELTA_BYTES + 1) { 5 },
        )
        assertTrue(RemoteReviewDocumentCodec.encode(largerThanDelta).sizeBytes < RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES)

        val exactCheckpointHard = annotation(
            transferId = "checkpoint_transfer_hard",
            kind = PageAnnotationKind.CHECKPOINT,
            payload = ByteArray(RemoteReviewLimits.MAX_PAGE_ANNOTATION_CHECKPOINT_BYTES) { 9 },
        )
        assertTrue(
            RemoteReviewDocumentCodec.encode(exactCheckpointHard).sizeBytes <=
                RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES,
        )

        assertValidationField("payloadBytes") {
            annotation(
                transferId = "checkpoint_transfer_over",
                kind = PageAnnotationKind.CHECKPOINT,
                payload = ByteArray(RemoteReviewLimits.MAX_PAGE_ANNOTATION_CHECKPOINT_BYTES + 1),
            )
        }
    }

    @Test fun manifestIdentitySubmittedAttemptsAndBoundsAreStrict() {
        assertValidationField("entries.workbookToken") { entry(workbookToken = "bad/token") }
        assertValidationField("entries.contentSha256") { entry(contentSha256 = "AB".repeat(32)) }
        assertValidationField("entries.studentLayerSha256") {
            entry(studentLayerSha256 = "AB".repeat(32))
        }
        assertValidationField("entries.attemptNos") { entry(attemptNos = listOf(1, 1)) }
        assertValidationField("entries.attemptNos") { entry(attemptNos = listOf(2, 1)) }
        assertValidationField("entries.submittedAttemptNos") {
            entry(submittedAttemptNos = listOf(2, 1))
        }
        assertValidationField("entries.submittedAttemptNos") {
            entry(attemptNos = listOf(1, 2), submittedAttemptNos = listOf(3))
        }
        assertValidationField("entries.approxBytes") {
            entry(approxBytes = RemoteReviewLimits.MAX_PAGE_SYNC_APPROX_BYTES + 1)
        }
        assertValidationField("currentCursor.currentAttemptNo") {
            manifest(
                sequence = 1L,
                currentCursor = PageSyncCursor(1L, PAGE_TOKEN, 37, 3, 91L),
                entries = listOf(entry(attemptNos = listOf(1, 2))),
            )
        }
        assertValidationField("entries.pageToken") {
            manifest(
                currentCursor = null,
                entries = listOf(entry(), entry(pageNumber = 38)),
            )
        }
        assertValidationField("entries.pageNumber") {
            manifest(
                currentCursor = null,
                entries = listOf(
                    entry(),
                    entry(
                        pageToken = "page_token_duplicate_37",
                        contentSha256 = "dc".repeat(32),
                    ),
                ),
            )
        }

        val samePageNumberInAnotherWorkbook = manifest(
            currentCursor = null,
            entries = listOf(
                entry(),
                entry(
                    pageToken = "page_token_other_book_37",
                    workbookToken = "workbook_token_0002",
                    contentSha256 = "dc".repeat(32),
                ),
            ),
        )
        assertEquals(2, samePageNumberInAnotherWorkbook.entries.size)
        assertFalse(entry(submittedAttemptNos = emptyList()).submitted)
    }

    @Test fun generationsSequencesRequestCorrelationAndDeltaOriginAreStrict() {
        assertValidationField("syncGeneration") { manifest(syncGeneration = 0L) }
        assertValidationField("sequence") { manifest(sequence = 0L, currentCursor = null) }
        assertValidationField("currentCursor.sequence") { PageSyncCursor(0L, PAGE_TOKEN, 37, 1, 91L) }
        assertValidationField("currentCursor.sequence") {
            manifest(
                sequence = 2L,
                currentCursor = PageSyncCursor(1L, PAGE_TOKEN, 37, 2, 91L),
            )
        }
        assertValidationField("syncGeneration") { request(syncGeneration = 0L) }
        assertValidationField("syncGeneration") { annotation(syncGeneration = 0L) }
        assertValidationField("syncGeneration") { ack(syncGeneration = 0L) }
        assertValidationField("attemptNo") { request(attemptNo = 0) }
        assertValidationField("requesterRevision") { request(requesterRevision = -1L) }

        assertValidationField("responseToTransferId") {
            annotation(purpose = PageAnnotationPurpose.STUDENT_PAGE, responseToTransferId = null)
        }
        assertValidationField("responseToTransferId") {
            annotation(
                purpose = PageAnnotationPurpose.TEACHER_REVIEW,
                responseToTransferId = REQUEST_TRANSFER_ID,
            )
        }
        assertValidationField("kind") {
            annotation(
                purpose = PageAnnotationPurpose.TEACHER_REVIEW,
                responseToTransferId = null,
                attemptNos = listOf(1),
                kind = PageAnnotationKind.DELTA,
            )
        }
        assertValidationField("attemptNos") {
            annotation(
                purpose = PageAnnotationPurpose.TEACHER_REVIEW,
                responseToTransferId = null,
                attemptNos = listOf(1, 2),
                kind = PageAnnotationKind.CHECKPOINT,
            )
        }
        assertValidationField("baseRevision") {
            annotation(baseRevision = 92L, sourceRevision = 92L)
        }
        assertValidationField("deltaOriginDeviceId") { annotation(deltaOriginDeviceId = null) }
        assertValidationField("baseOriginCursor") { annotation(baseOriginCursor = -1L) }
        assertValidationField("sourceOriginCursor") {
            annotation(baseOriginCursor = 11L, sourceOriginCursor = 11L)
        }
        assertValidationField("baseRevision") {
            annotation(kind = PageAnnotationKind.CHECKPOINT, baseRevision = 1L)
        }
        assertValidationField("deltaOriginDeviceId") {
            annotation(kind = PageAnnotationKind.CHECKPOINT, deltaOriginDeviceId = ORIGIN_DEVICE_ID)
        }
        assertValidationField("baseOriginCursor") {
            annotation(kind = PageAnnotationKind.CHECKPOINT, baseOriginCursor = 1L)
        }
        assertValidationField("sourceOriginCursor") {
            annotation(kind = PageAnnotationKind.CHECKPOINT, sourceOriginCursor = 1L)
        }
        assertValidationField("resultLayerSha256") {
            annotation(resultLayerSha256 = "AB".repeat(32))
        }
        assertValidationField("reasonCode") {
            ack(disposition = PageSyncAckDisposition.REJECTED, reasonCode = null)
        }
        assertValidationField("disposition") {
            ack(
                sourceType = PageSyncAckSourceType.REQUEST,
                sourceTransferId = REQUEST_TRANSFER_ID,
                disposition = PageSyncAckDisposition.APPLIED,
            )
        }
    }

    @Test fun pageAnnotationStatePlanUsesGenerationAwareSemanticAckOnlyAfterCommit() {
        val annotation = annotation(syncGeneration = 17L)
        val apply = RemoteReviewExchangeStateMachine.planIncoming(annotation, EmptyState)

        assertEquals(RemoteReviewIncomingAction.APPLY_PAGE_ANNOTATION, apply.action)
        assertNull(apply.ackAfterCommit)
        assertEquals(annotation.transferId, apply.pageSyncAckAfterCommit?.sourceTransferId)
        assertEquals(17L, apply.pageSyncAckAfterCommit?.syncGeneration)
        assertEquals(PageSyncAckSourceType.ANNOTATION, apply.pageSyncAckAfterCommit?.sourceType)
        assertEquals(PageSyncAckDisposition.APPLIED, apply.pageSyncAckAfterCommit?.disposition)
        assertEquals(annotation.sourceRevision, apply.pageSyncAckAfterCommit?.sourceRevision)
        val ackEnvelope = apply.pageSyncAckAfterCommit!!.toEnvelope(
            transferId = "generated_sync_ack_0001",
            createdAtEpochMs = 22_000L,
        )
        assertEquals(17L, ackEnvelope.syncGeneration)
        assertEquals(PageSyncAckSourceType.ANNOTATION, ackEnvelope.sourceType)
        assertTrue(
            apply.commitMutations.contains(
                RemoteReviewStateMutation.RecordCommittedTransfer(annotation.transferId),
            ),
        )

        val duplicate = RemoteReviewExchangeStateMachine.planIncoming(annotation, CommittedState)
        assertEquals(RemoteReviewIncomingAction.IGNORE_DUPLICATE, duplicate.action)
        assertNull(duplicate.ackAfterCommit)
        assertEquals(PageSyncAckDisposition.DUPLICATE, duplicate.pageSyncAckAfterCommit?.disposition)
        assertEquals(17L, duplicate.pageSyncAckAfterCommit?.syncGeneration)
        assertNull(RemoteReviewExchangeStateMachine.coalesceKey(annotation))
    }

    private fun manifest(
        transferId: String = "manifest_transfer_0001",
        syncGeneration: Long = 7L,
        sequence: Long = 23L,
        currentCursor: PageSyncCursor? = PageSyncCursor(sequence, PAGE_TOKEN, 37, 2, 91L),
        entries: List<PageSyncManifestEntry> = listOf(entry()),
        inventoryPageCount: Int? = entries.size,
    ) = PageSyncManifestEnvelope(
        transferId = transferId,
        createdAtEpochMs = 12_500L,
        syncGeneration = syncGeneration,
        sequence = sequence,
        currentCursor = currentCursor,
        entries = entries,
        inventoryPageCount = inventoryPageCount,
    )

    private fun request(
        transferId: String = REQUEST_TRANSFER_ID,
        attemptNo: Int? = null,
        requesterRevision: Long = 91L,
        syncGeneration: Long = 7L,
    ) = PageSyncRequestEnvelope(
        transferId = transferId,
        createdAtEpochMs = 13_000L,
        syncGeneration = syncGeneration,
        pageToken = PAGE_TOKEN,
        pageNumber = 37,
        attemptNo = attemptNo,
        requesterRevision = requesterRevision,
    )

    private fun annotation(
        transferId: String = "annotation_transfer_0001",
        syncGeneration: Long = 7L,
        purpose: PageAnnotationPurpose = PageAnnotationPurpose.STUDENT_PAGE,
        responseToTransferId: String? = if (purpose == PageAnnotationPurpose.STUDENT_PAGE) {
            REQUEST_TRANSFER_ID
        } else {
            null
        },
        attemptNos: List<Int> = listOf(1, 2),
        kind: PageAnnotationKind = PageAnnotationKind.DELTA,
        compression: PageAnnotationCompression = PageAnnotationCompression.NONE,
        payload: ByteArray = "delta-one".toByteArray(),
        baseRevision: Long = if (kind == PageAnnotationKind.DELTA) 91L else 0L,
        sourceRevision: Long = 92L,
        deltaOriginDeviceId: String? = if (kind == PageAnnotationKind.DELTA) ORIGIN_DEVICE_ID else null,
        baseOriginCursor: Long = if (kind == PageAnnotationKind.DELTA) 11L else 0L,
        sourceOriginCursor: Long = if (kind == PageAnnotationKind.DELTA) 17L else 0L,
        resultLayerSha256: String = RESULT_LAYER_SHA,
    ) = PageAnnotationEnvelope.fromDecodedPayload(
        transferId = transferId,
        createdAtEpochMs = 20_000L,
        syncGeneration = syncGeneration,
        purpose = purpose,
        responseToTransferId = responseToTransferId,
        pageToken = PAGE_TOKEN,
        pageNumber = 37,
        attemptNos = attemptNos,
        kind = kind,
        baseRevision = baseRevision,
        sourceRevision = sourceRevision,
        deltaOriginDeviceId = deltaOriginDeviceId,
        baseOriginCursor = baseOriginCursor,
        sourceOriginCursor = sourceOriginCursor,
        compression = compression,
        decodedPayloadBytes = payload,
        resultLayerSha256 = resultLayerSha256,
    )

    private fun rawAnnotation(
        compression: PageAnnotationCompression = PageAnnotationCompression.NONE,
        payloadBytes: ByteArray,
        payloadSha256: String,
    ) = PageAnnotationEnvelope(
        transferId = "annotation_transfer_0001",
        createdAtEpochMs = 20_000L,
        syncGeneration = 7L,
        purpose = PageAnnotationPurpose.STUDENT_PAGE,
        responseToTransferId = REQUEST_TRANSFER_ID,
        pageToken = PAGE_TOKEN,
        pageNumber = 37,
        attemptNos = listOf(1),
        kind = PageAnnotationKind.DELTA,
        baseRevision = 91L,
        sourceRevision = 92L,
        deltaOriginDeviceId = ORIGIN_DEVICE_ID,
        baseOriginCursor = 11L,
        sourceOriginCursor = 17L,
        compression = compression,
        payloadBytes = payloadBytes,
        payloadSha256 = payloadSha256,
        resultLayerSha256 = RESULT_LAYER_SHA,
    )

    private fun ack(
        transferId: String = "sync_ack_transfer_0001",
        syncGeneration: Long = 7L,
        sourceType: PageSyncAckSourceType = PageSyncAckSourceType.ANNOTATION,
        sourceTransferId: String = "annotation_transfer_0001",
        disposition: PageSyncAckDisposition = PageSyncAckDisposition.APPLIED,
        reasonCode: String? = null,
    ) = PageSyncAckEnvelope(
        transferId = transferId,
        createdAtEpochMs = 21_000L,
        syncGeneration = syncGeneration,
        sourceType = sourceType,
        sourceTransferId = sourceTransferId,
        pageToken = PAGE_TOKEN,
        pageNumber = 37,
        sourceRevision = 92L,
        disposition = disposition,
        reasonCode = reasonCode,
    )

    private fun entry(
        pageToken: String = PAGE_TOKEN,
        workbookToken: String = WORKBOOK_TOKEN,
        contentSha256: String = CONTENT_SHA,
        studentLayerSha256: String = STUDENT_LAYER_SHA,
        pageNumber: Int = 37,
        attemptNos: List<Int> = listOf(1, 2),
        submittedAttemptNos: List<Int> = emptyList(),
        approxBytes: Long = 100L,
    ) = PageSyncManifestEntry(
        pageToken = pageToken,
        workbookToken = workbookToken,
        contentSha256 = contentSha256,
        studentLayerSha256 = studentLayerSha256,
        pageNumber = pageNumber,
        attemptNos = attemptNos,
        submittedAttemptNos = submittedAttemptNos,
        revision = 91L,
        lastChangedEpochMs = 12_000L,
        approxBytes = approxBytes,
    )

    private fun roundTrip(envelope: RemoteReviewEnvelope): RemoteReviewEnvelope =
        RemoteReviewDocumentCodec.decode(
            RemoteReviewDocumentCodec.encode(envelope).copyBytes(),
        ).envelope

    private fun EncodedRemoteReviewDocument.wireTypeCode(): Int =
        copyBytes()[WIRE_TYPE_OFFSET].toInt() and 0xff

    private fun assertValidationField(expectedField: String, block: () -> Unit) {
        val failure = assertThrows(RemoteReviewValidationException::class.java, block)
        assertEquals(expectedField, failure.field)
    }

    private object EmptyState : RemoteReviewStateView {
        override fun isTransferCommitted(transferId: String): Boolean = false
        override fun snapshotByTransferId(transferId: String): RemoteSnapshotCursor? = null
        override fun latestSnapshot(pageToken: String): RemoteSnapshotCursor? = null
        override fun latestFeedback(pageToken: String): RemoteFeedbackCursor? = null
    }

    private object CommittedState : RemoteReviewStateView {
        override fun isTransferCommitted(transferId: String): Boolean = true
        override fun snapshotByTransferId(transferId: String): RemoteSnapshotCursor? = null
        override fun latestSnapshot(pageToken: String): RemoteSnapshotCursor? = null
        override fun latestFeedback(pageToken: String): RemoteFeedbackCursor? = null
    }

    private companion object {
        const val WIRE_TYPE_OFFSET = 5
        const val PAGE_TOKEN = "page_token_00000037"
        const val WORKBOOK_TOKEN = "workbook_token_0001"
        const val REQUEST_TRANSFER_ID = "request_transfer_0001"
        const val ORIGIN_DEVICE_ID = "student_device_0001"
        val CONTENT_SHA: String = "ab".repeat(32)
        val STUDENT_LAYER_SHA: String = "cd".repeat(32)
        val RESULT_LAYER_SHA: String = "ef".repeat(32)
    }
}
