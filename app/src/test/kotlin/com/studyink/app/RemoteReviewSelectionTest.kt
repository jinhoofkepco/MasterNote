package com.studyink.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteReviewSelectionTest {
    @Test fun retainsDisplayedTransferAtItsNewIndex() {
        assertEquals(
            RemoteSnapshotRefreshDecision(RemoteSnapshotRefreshAction.RETAIN, 1),
            decideRemoteSnapshotRefresh("current", false, listOf("new", "current", "old")),
        )
    }

    @Test fun missingCleanTransferOpensNewestAndEmptyListClears() {
        assertEquals(
            RemoteSnapshotRefreshDecision(RemoteSnapshotRefreshAction.OPEN_NEWEST, 0),
            decideRemoteSnapshotRefresh("evicted", false, listOf("new")),
        )
        assertEquals(
            RemoteSnapshotRefreshDecision(RemoteSnapshotRefreshAction.CLEAR),
            decideRemoteSnapshotRefresh("evicted", false, emptyList()),
        )
        assertEquals(
            RemoteSnapshotRefreshDecision(RemoteSnapshotRefreshAction.OPEN_NEWEST, 0),
            decideRemoteSnapshotRefresh(null, false, listOf("first-arrival")),
        )
    }

    @Test fun missingDirtyTransferIsNeverDiscardedImplicitly() {
        assertEquals(
            RemoteSnapshotRefreshDecision(RemoteSnapshotRefreshAction.KEEP_DIRTY_STALE),
            decideRemoteSnapshotRefresh("evicted", true, listOf("new")),
        )
    }
}
