package com.studyink.core.model

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterNoteOptionalDataRootGuardTest {
    @Test
    fun sameNormalizedRootSerializesSnapshotAndOptionalWriter() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val root = File("build/optional-root-guard/../optional-root-guard/data")
        try {
            pool.submit {
                MasterNoteOptionalDataRootGuard.withStableDataRoot(root) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pool.submit {
                MasterNoteOptionalDataRootGuard.withStableDataRoot(root.absoluteFile) {
                    writerEntered.countDown()
                }
            }
            assertFalse(writerEntered.await(150, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
