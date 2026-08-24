package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteReviewGradeGestureTest {
    private val snapshot = RemotePageSnapshotRef(
        transferId = "transfer-a",
        pageToken = "page-token-a",
        bookFingerprint = "book-a",
        pageNumber = 6,
        studentRevision = 41L,
        imageWidthPx = 1_200,
        imageHeightPx = 1_800,
        receivedAtEpochMillis = 100L,
    )

    @Test
    fun qualifiedTapCarriesExactSnapshotAndNormalizedReleaseAnchor() {
        val tap = resolveRemoteReviewGradeTap(
            snapshotAtDown = snapshot,
            currentSnapshot = snapshot,
            releasePoint = RemoteNormalizedPoint(0.25f, 0.75f, pressure = 0.2f),
            maxTravelPixels = 5f,
            tapSlopPixels = 12f,
        )

        assertEquals(snapshot, tap?.snapshot)
        assertEquals(RemoteNormalizedPoint(0.25f, 0.75f, pressure = 1f), tap?.anchor)
    }

    @Test
    fun dragDoesNotOpenGradeChooser() {
        assertNull(
            resolveRemoteReviewGradeTap(
                snapshotAtDown = snapshot,
                currentSnapshot = snapshot,
                releasePoint = RemoteNormalizedPoint(0.3f, 0.4f),
                maxTravelPixels = 12.01f,
                tapSlopPixels = 12f,
            ),
        )
    }

    @Test
    fun pageChangingDuringGestureRejectsTheTap() {
        val newerPage = snapshot.copy(
            transferId = "transfer-b",
            pageToken = "page-token-b",
            pageNumber = 7,
        )

        assertNull(
            resolveRemoteReviewGradeTap(
                snapshotAtDown = snapshot,
                currentSnapshot = newerPage,
                releasePoint = RemoteNormalizedPoint(0.3f, 0.4f),
                maxTravelPixels = 0f,
                tapSlopPixels = 12f,
            ),
        )
    }

    @Test
    fun invalidCoordinatesOrGestureMetricsAreRejected() {
        assertNull(
            resolveRemoteReviewGradeTap(
                snapshotAtDown = snapshot,
                currentSnapshot = snapshot,
                releasePoint = RemoteNormalizedPoint(Float.NaN, 0.4f),
                maxTravelPixels = 0f,
                tapSlopPixels = 12f,
            ),
        )
        assertNull(
            resolveRemoteReviewGradeTap(
                snapshotAtDown = snapshot,
                currentSnapshot = snapshot,
                releasePoint = RemoteNormalizedPoint(0.3f, 0.4f),
                maxTravelPixels = Float.POSITIVE_INFINITY,
                tapSlopPixels = 12f,
            ),
        )
    }
}
