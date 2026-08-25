package com.studyink.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemotePageDeltaCodecTest {
    @Test
    fun `round trip preserves operation boundaries`() {
        val operations = listOf("one".encodeToByteArray(), byteArrayOf(0, 1, 2, 3))

        val decoded = RemotePageDeltaCodec.decode(RemotePageDeltaCodec.encode(operations))

        assertEquals(2, decoded.size)
        assertArrayEquals(operations[0], decoded[0])
        assertArrayEquals(operations[1], decoded[1])
    }

    @Test
    fun `decode rejects truncation and trailing data`() {
        val encoded = RemotePageDeltaCodec.encode(listOf("operation".encodeToByteArray()))

        assertThrows(IllegalArgumentException::class.java) {
            RemotePageDeltaCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemotePageDeltaCodec.decode(encoded + 1)
        }
    }

    @Test
    fun `empty delta is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RemotePageDeltaCodec.encode(emptyList()) }
    }
}
