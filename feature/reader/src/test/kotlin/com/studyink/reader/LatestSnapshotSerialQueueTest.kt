package com.studyink.reader

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestSnapshotSerialQueueTest {
    @Test
    fun keepsOneActiveValueAndOnlyTheNewestFollowUp() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val drained = CountDownLatch(2)
            val persisted = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
            val queue = LatestSnapshotSerialQueue(
                executor = executor,
                initialBase = 0,
                persist = { value: String, base: Int ->
                    if (value == "first") {
                        firstStarted.countDown()
                        assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                    }
                    persisted += value to base
                    base + 1
                },
                observer = countingObserver(drained),
            )

            assertTrue(queue.offer("first"))
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            assertTrue(queue.offer("second"))
            assertTrue(queue.offer("latest"))
            assertTrue(queue.isBusy)
            releaseFirst.countDown()

            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("first" to 0, "latest" to 1), persisted)
            assertFalse(queue.isBusy)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun acceptedLatestValueStillDrainsAfterObserverDetaches() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val drained = CountDownLatch(2)
            val callbacks = Collections.synchronizedList(mutableListOf<String>())
            val queue = LatestSnapshotSerialQueue(
                executor = executor,
                initialBase = 4,
                persist = { value: String, base: Int ->
                    if (value == "first") {
                        firstStarted.countDown()
                        assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                    }
                    callbacks += "persist:$value:$base"
                    drained.countDown()
                    base + 1
                },
                observer = object : LatestSnapshotSerialQueue.Observer<String, Int> {
                    override fun onPersisted(value: String, committedBase: Int) {
                        callbacks += "ui:$value"
                    }

                    override fun onFailure(error: Throwable) {
                        callbacks += "ui:error"
                    }
                },
            )

            assertTrue(queue.offer("first"))
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            assertTrue(queue.offer("pending"))
            assertTrue(queue.offer("latest"))
            queue.detachObserver() // Models Activity/View detachment.
            releaseFirst.countDown()

            assertTrue(drained.await(5, TimeUnit.SECONDS))
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("persist:first:4", "persist:latest:5"), callbacks)
            assertFalse(queue.isBusy)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun countingObserver(latch: CountDownLatch) =
        object : LatestSnapshotSerialQueue.Observer<String, Int> {
            override fun onPersisted(value: String, committedBase: Int) {
                latch.countDown()
            }

            override fun onFailure(error: Throwable) = Unit
        }
}
