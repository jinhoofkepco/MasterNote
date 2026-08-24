package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class TelegramStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun offsetIsMonotonicAndScopedToBotFingerprint() {
        val store = TelegramUpdateOffsetStore(temporary.newFile("offset"))
        store.commit("bot-a", 9L)
        store.commit("bot-a", 4L)
        assertEquals(9L, store.load("bot-a"))
        assertEquals(0L, store.load("bot-b"))
    }

    @Test fun preferencesRoundTrip() {
        val paths = TelegramStoragePaths.forTests(temporary.newFolder("paths"))
        val first = RemoteMonitorPreferencesStore(paths)
        first.update { RemoteMonitorPreferences(true, true, true, true) }

        assertEquals(
            RemoteMonitorPreferences(true, true, true, true),
            RemoteMonitorPreferencesStore(paths).get(),
        )
    }

    @Test fun legacyPreferencesMigrateToHourlyActivityMode() {
        val paths = TelegramStoragePaths.forTests(temporary.newFolder("legacy-paths"))
        paths.preferencesFile.writeText("V1\t1\t1\t1")

        assertEquals(
            RemoteMonitorPreferences(
                ttsEnabled = true,
                wakeVoiceEnabled = true,
                monitoringEnabled = true,
                realtimeActivityEnabled = false,
            ),
            RemoteMonitorPreferencesStore(paths).get(),
        )
    }
}
