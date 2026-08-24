package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceCrashRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun finalizedVoiceSurvivesQueueFullThenIsRecoveredExactlyOnce() {
        val paths = TelegramStoragePaths.forTests(temporary.newFolder("storage"))
        val outbox = TelegramOutbox(paths.outboxJournal, maxPendingEntries = 1)
        val blocker = textEntry("blocker")
        assertEquals(TelegramEnqueueResult.ENQUEUED, outbox.enqueue(blocker))
        val partial = paths.voiceDirectory.resolve("student-crash.m4a.part").apply { writeText("partial") }
        val voice = paths.voiceDirectory.resolve("student-crash.m4a").apply { writeText("voice") }
        val credentials = TelegramCredentials("123456:abcdefghijklmnopqrstuvwxyz", 77L, "부모")

        val retained = recoverUnqueuedVoiceMedia(paths, outbox, credentials, nowEpochMs = 10L)
        cleanupUnqueuedOwnedMedia(paths, outbox, retained)

        assertFalse(partial.exists())
        assertTrue(voice.exists())
        assertEquals(listOf("blocker"), outbox.pendingSnapshot().map { it.idempotencyKey })

        outbox.acknowledge(blocker.idempotencyKey, 11L)
        val secondRetained = recoverUnqueuedVoiceMedia(paths, outbox, credentials, nowEpochMs = 12L)
        cleanupUnqueuedOwnedMedia(paths, outbox, secondRetained)
        val recovered = outbox.pendingSnapshot().single()
        assertEquals("student-voice:${voice.name}", recovered.idempotencyKey)
        assertEquals(TelegramOutboxKind.VOICE, recovered.kind)
        assertEquals(voice.absolutePath, recovered.filePath)
        assertTrue(voice.exists())

        assertTrue(recoverUnqueuedVoiceMedia(paths, outbox, credentials, nowEpochMs = 13L).isEmpty())
        assertEquals(1, outbox.size())
    }

    @Test fun orphanVoiceWithoutCredentialsIsRemovedInsteadOfCrossingPairingBoundary() {
        val paths = TelegramStoragePaths.forTests(temporary.newFolder("unpaired"))
        val outbox = TelegramOutbox(paths.outboxJournal)
        val voice = paths.voiceDirectory.resolve("student-orphan.m4a").apply { writeText("voice") }

        val retained = recoverUnqueuedVoiceMedia(paths, outbox, credentials = null, nowEpochMs = 1L)
        cleanupUnqueuedOwnedMedia(paths, outbox, retained)

        assertTrue(retained.isEmpty())
        assertFalse(voice.exists())
        assertEquals(0, outbox.size())
    }

    @Test fun connectionChangePurgesOnlyVoiceWithoutADurableOldDestination() {
        val paths = TelegramStoragePaths.forTests(temporary.newFolder("repaired"))
        val outbox = TelegramOutbox(paths.outboxJournal)
        val queuedVoice = paths.voiceDirectory.resolve("student-queued.m4a").apply { writeText("queued") }
        val orphanVoice = paths.voiceDirectory.resolve("student-orphan.m4a").apply { writeText("orphan") }
        val partial = paths.voiceDirectory.resolve("student-active.m4a.part").apply { writeText("partial") }
        assertEquals(
            TelegramEnqueueResult.ENQUEUED,
            outbox.enqueue(
                TelegramOutboxEntry(
                    idempotencyKey = "student-voice:${queuedVoice.name}",
                    destinationChatId = 77L,
                    kind = TelegramOutboxKind.VOICE,
                    filePath = queuedVoice.absolutePath,
                    text = "학생 음성 메시지",
                    mimeType = "audio/mp4",
                    displayName = queuedVoice.name,
                    attempts = 0,
                    nextAttemptEpochMs = 0L,
                    createdAtEpochMs = 0L,
                    deleteAfterSend = true,
                ),
            ),
        )

        purgeUnqueuedVoiceMedia(paths, outbox)

        assertTrue(queuedVoice.exists())
        assertFalse(orphanVoice.exists())
        assertFalse(partial.exists())
    }

    private fun textEntry(key: String) = TelegramOutboxEntry(
        idempotencyKey = key,
        destinationChatId = 77L,
        kind = TelegramOutboxKind.TEXT,
        filePath = null,
        text = "pending",
        mimeType = null,
        displayName = null,
        attempts = 0,
        nextAttemptEpochMs = 0L,
        createdAtEpochMs = 0L,
        deleteAfterSend = false,
    )
}
