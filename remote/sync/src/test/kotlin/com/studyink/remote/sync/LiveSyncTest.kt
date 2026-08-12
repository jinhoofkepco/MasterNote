package com.studyink.remote.sync

import com.studyink.remote.protocol.RemotePageState
import com.studyink.remote.protocol.RemoteStrokePoint
import com.studyink.remote.protocol.RemoteViewportState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSyncTest {
    @Test fun previewIsThrottledSampledAndConflated() = runTest {
        val publisher = RemoteLivePublisher()
        val points = List(120) { RemoteStrokePoint(it.toFloat(), 1f, .5f, it.toLong()) }

        assertTrue(publisher.offerStroke("preview", "page", points, 0))
        assertFalse(publisher.offerStroke("preview", "page", points, 50))
        assertTrue(publisher.offerStroke("preview", "page", points, 100))

        assertEquals(24, publisher.preview.value?.points?.size)
        assertEquals(0f, publisher.preview.value?.points?.first()?.x)
        assertEquals(119f, publisher.preview.value?.points?.last()?.x)
    }

    @Test fun finalStrokeAndTimeoutClearOnlyMatchingPreview() {
        val publisher = RemoteLivePublisher()
        publisher.offerStroke("one", "page", listOf(point()), 0)
        publisher.finishStroke("other")
        assertEquals("one", publisher.preview.value?.previewId)
        publisher.finishStroke("one")
        assertNull(publisher.preview.value)
        publisher.offerStroke("two", "page", listOf(point()), 100)
        assertTrue(publisher.expirePreview(2_100))
        assertNull(publisher.preview.value)
    }

    @Test fun pageUpdatesRetainOnlyLatestValue() {
        val publisher = RemoteLivePublisher()
        repeat(100) { publisher.updatePage(RemotePageState("page-$it", it, it.toLong())) }
        assertEquals("page-99", publisher.pageState.value?.pageId)
    }

    @Test fun finalDurableStrokeReplacesMatchingTeacherPreview() {
        val replica = RemotePreviewReplica()
        replica.receive(com.studyink.remote.protocol.RemoteLiveStrokePreview("stroke-id", "page", listOf(point())), 0)
        assertEquals(1, replica.size())
        replica.onFinalStroke("stroke-id")
        assertEquals(0, replica.size())
    }

    @Test fun followOffKeepsTeacherViewWhileStudentPageStillUpdates() {
        val follow = RemoteFollowController()
        follow.onPage(RemotePageState("one", 1, 1))
        follow.setFollowing(false)
        follow.onPage(RemotePageState("two", 2, 2))
        follow.onViewport(RemoteViewportState("two", .5f, .5f, 2f, .5f, .5f))
        assertEquals("two", follow.state.value.studentPage?.pageId)
        assertEquals("one", follow.state.value.appliedPage?.pageId)
        assertNull(follow.state.value.appliedViewport)
        follow.setFollowing(true)
        assertEquals("two", follow.state.value.appliedPage?.pageId)
    }

    private fun point() = RemoteStrokePoint(1f, 2f, .5f, 1)
}
