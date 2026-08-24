package com.studyink.reader

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S23UltraTopStripTest {
    @Test
    fun onlyS23UltraTeacherPhonePortraitUsesTheDeviceStrip() {
        assertTrue(
            shouldUseS23UltraTopStrip(
                model = "SM-S918N",
                orientation = Configuration.ORIENTATION_PORTRAIT,
                role = ReaderRole.TEACHER_PHONE,
            ),
        )
        assertTrue(
            shouldUseS23UltraTopStrip(
                model = "sm-s918u1",
                orientation = Configuration.ORIENTATION_PORTRAIT,
                role = ReaderRole.TEACHER_PHONE,
            ),
        )

        assertFalse(
            shouldUseS23UltraTopStrip(
                model = "SM-S918N",
                orientation = Configuration.ORIENTATION_LANDSCAPE,
                role = ReaderRole.TEACHER_PHONE,
            ),
        )
        assertFalse(
            shouldUseS23UltraTopStrip(
                model = "SM-S918N",
                orientation = Configuration.ORIENTATION_PORTRAIT,
                role = ReaderRole.TEACHER_TABLET,
            ),
        )
        assertFalse(
            shouldUseS23UltraTopStrip(
                model = "SM-S928N",
                orientation = Configuration.ORIENTATION_PORTRAIT,
                role = ReaderRole.TEACHER_PHONE,
            ),
        )
    }

    @Test
    fun attemptLaneKeepsCurrentAndThreeEarlierFramesWhenAvailable() {
        val bundles = (1..8).map { ReaderAttemptMarkBundle(it, emptyList()) }

        assertEquals(listOf(1, 2, 3, 4), s23VisibleAttemptBundles(bundles, 1).map { it.attemptNo })
        assertEquals(listOf(1, 2, 3, 4), s23VisibleAttemptBundles(bundles, 4).map { it.attemptNo })
        assertEquals(listOf(5, 6, 7, 8), s23VisibleAttemptBundles(bundles, 8).map { it.attemptNo })
    }

    @Test
    fun shortAttemptHistoryDoesNotInventFrames() {
        val bundles = listOf(
            ReaderAttemptMarkBundle(1, emptyList()),
            ReaderAttemptMarkBundle(2, emptyList()),
        )

        assertEquals(listOf(1, 2), s23VisibleAttemptBundles(bundles, 2).map { it.attemptNo })
    }
}
