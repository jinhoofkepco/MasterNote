package com.studyink.monitor.telegram

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom

class TelegramPeerPayloadCipherTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun roundTripStreamsOneMegabyteAndAuthenticatesCaption() {
        val plain = temporary.newFile("page.bin")
        val bytes = ByteArray(1_050_000).also(SecureRandom()::nextBytes)
        plain.writeBytes(bytes)
        val encrypted = temporary.root.resolve("page.mne")
        val decoded = temporary.root.resolve("decoded.bin")
        val key = TelegramPeerProtocol.newSharedKey()
        val caption = TelegramPeerProtocol.documentCaption("pair_id_123", "transfer_123", "PAGE_SNAPSHOT")

        TelegramPeerPayloadCipher.encrypt(plain, encrypted, key, caption)
        assertFalse(encrypted.readBytes().take(16).toByteArray().contentEquals(bytes.take(16).toByteArray()))
        TelegramPeerPayloadCipher.decrypt(encrypted, decoded, key, caption)

        assertArrayEquals(bytes, decoded.readBytes())
    }

    @Test fun wrongPairMetadataDoesNotLeavePlaintextBehind() {
        val plain = temporary.newFile("feedback.bin").apply { writeText("teacher feedback") }
        val encrypted = temporary.root.resolve("feedback.mne")
        val decoded = temporary.root.resolve("decoded.bin")
        val key = TelegramPeerProtocol.newSharedKey()
        TelegramPeerPayloadCipher.encrypt(plain, encrypted, key, "MNTP1 DOC pair_123 transfer_123 FEEDBACK")

        val failure = runCatching {
            TelegramPeerPayloadCipher.decrypt(
                encrypted,
                decoded,
                key,
                "MNTP1 DOC pair_123 transfer_999 FEEDBACK",
            )
        }.exceptionOrNull()

        assertTrue(failure is SecurityException)
        assertFalse(decoded.exists())
    }

    @Test fun modifiedCiphertextFailsAuthenticationAndLeavesNoPlaintext() {
        val plain = temporary.newFile("source.bin").apply { writeText("student page") }
        val encrypted = temporary.root.resolve("modified.mne")
        val decoded = temporary.root.resolve("modified-decoded.bin")
        val key = TelegramPeerProtocol.newSharedKey()
        val caption = TelegramPeerProtocol.documentCaption("pair_id_123", "transfer_123", "PAGE_SNAPSHOT")
        TelegramPeerPayloadCipher.encrypt(plain, encrypted, key, caption)
        val bytes = encrypted.readBytes().also { content ->
            content[content.lastIndex] = (content.last().toInt() xor 1).toByte()
        }
        encrypted.writeBytes(bytes)

        val failure = runCatching {
            TelegramPeerPayloadCipher.decrypt(encrypted, decoded, key, caption)
        }.exceptionOrNull()

        assertTrue(failure is SecurityException)
        assertFalse(decoded.exists())
    }
}
