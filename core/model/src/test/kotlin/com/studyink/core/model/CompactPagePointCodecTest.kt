package com.studyink.core.model

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPagePointCodecTest {
    @Test
    fun `golden Q16 delta encoding is stable`() {
        val points = listOf(
            PagePoint(10f, -2f),
            PagePoint(10.0625f, -2.125f),
            PagePoint(9.9375f, -2.125f),
        )

        assertArrayEquals(
            byteArrayOf(0xc0.toByte(), 0x02, 0x3f, 0x02, 0x03, 0x03, 0x00),
            CompactPagePointCodec.encode(points),
        )
        assertEquals(points, CompactPagePointCodec.decode(CompactPagePointCodec.encode(points), 3))
    }

    @Test
    fun `new ink is rounded once and pressure is discarded`() {
        val source = StrokeAsset(
            pageNumber = 2,
            tool = StrokeTool.PEN,
            colorArgb = 0xff010203.toInt(),
            width = 1f,
            points = listOf(PagePoint(10.03f, 20.04f, 0.37f)),
        )

        val canonical = source.canonicalizedForNewInk()

        assertEquals(listOf(PagePoint(10f, 20.0625f, 1f)), canonical.points)
        assertEquals(PageBounds(10f, 20.0625f, 10f, 20.0625f), canonical.bounds)
        assertEquals(canonical, canonical.canonicalizedForNewInk())
        assertTrue(CompactPagePointCodec.canEncodeExactly(canonical.points))
        assertFalse(CompactPagePointCodec.canEncodeExactly(source.points))
    }

    @Test
    fun `legacy pressure and non-grid coordinates are not eligible`() {
        assertFalse(CompactPagePointCodec.canEncodeExactly(listOf(PagePoint(1f, 2f, 0.5f))))
        assertFalse(CompactPagePointCodec.canEncodeExactly(listOf(PagePoint(0.1f, 2f, 1f))))
    }

    @Test
    fun `decoder rejects truncation trailing bytes noncanonical values and overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompactPagePointCodec.decode(byteArrayOf(0x80.toByte()), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompactPagePointCodec.decode(byteArrayOf(0, 0, 0), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompactPagePointCodec.decode(byteArrayOf(0x80.toByte(), 0, 0), 1)
        }

        val maximumThenPositiveDelta = byteArrayOf(
            0xfe.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f,
            0,
            2,
            0,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CompactPagePointCodec.decode(maximumThenPositiveDelta, 2)
        }
    }

    @Test
    fun `lossless F32 gzip preserves every coordinate and pressure bit`() {
        val points = listOf(
            PagePoint(Float.fromBits(0x80000000.toInt()), 0.1f, 0.37f),
            PagePoint(Float.fromBits(0x00000001), Float.fromBits(0x807fffff.toInt()), 1.0001f),
            PagePoint(12_345.678f, -98_765.43f, Float.fromBits(0x3eaaaaab)),
        )

        val decoded = LosslessF32PagePointCodec.decode(
            encoded = LosslessF32PagePointCodec.encode(points),
            pointCount = points.size,
        )

        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.x.toRawBits(), actual.x.toRawBits())
            assertEquals(expected.y.toRawBits(), actual.y.toRawBits())
            assertEquals(expected.pressure.toRawBits(), actual.pressure.toRawBits())
        }
    }

    @Test
    fun `lossless F32 decoder rejects truncation trailing members bombs and count overflow`() {
        val encoded = LosslessF32PagePointCodec.encode(listOf(PagePoint(1.1f, 2.2f, 0.3f)))
        assertThrows(IllegalArgumentException::class.java) {
            LosslessF32PagePointCodec.decode(encoded.copyOf(encoded.size - 1), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LosslessF32PagePointCodec.decode(
                encoded.copyOf().apply { this[size - 8] = (this[size - 8].toInt() xor 1).toByte() },
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LosslessF32PagePointCodec.decode(encoded + byteArrayOf(0), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LosslessF32PagePointCodec.decode(encoded + encoded, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LosslessF32PagePointCodec.decode(gzip(ByteArray(24)), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LosslessF32PagePointCodec.decode(
                encoded = ByteArray(0),
                pointCount = LosslessF32PagePointCodec.DEFAULT_MAX_POINT_COUNT + 1,
            )
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }
}
