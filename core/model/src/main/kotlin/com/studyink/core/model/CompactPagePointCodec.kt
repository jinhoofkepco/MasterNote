package com.studyink.core.model

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.DataFormatException
import kotlin.math.round

/**
 * Lossless storage codec for points which have already been canonicalized to a 1/16 page unit.
 *
 * The first x/y pair is signed absolute Q16. Every following pair is a signed delta from the
 * previous point. Signed values use ZigZag and every value uses canonical unsigned LEB128. Point
 * count deliberately remains outside the byte stream so a caller can enforce its own allocation
 * limit before decoding untrusted storage or transport data.
 */
object CompactPagePointCodec {
    const val COORDINATE_SCALE = 16
    const val DEFAULT_MAX_POINT_COUNT = 1_000_000

    fun canonicalizeNewPoint(point: PagePoint): PagePoint = PagePoint(
        x = canonicalCoordinate(point.x),
        y = canonicalCoordinate(point.y),
        pressure = 1f,
    )

    fun canonicalizeNewPoints(points: List<PagePoint>): List<PagePoint> =
        points.map(::canonicalizeNewPoint)

    /** True only when encoding and decoding preserves every runtime value exactly. */
    fun canEncodeExactly(points: List<PagePoint>): Boolean {
        if (points.size > DEFAULT_MAX_POINT_COUNT) return false
        var previousX = 0
        var previousY = 0
        points.forEachIndexed { index, point ->
            if (point.pressure.toBits() != 1f.toBits()) return false
            val x = exactQuantizedCoordinate(point.x) ?: return false
            val y = exactQuantizedCoordinate(point.y) ?: return false
            if (index > 0) {
                if (x.toLong() - previousX.toLong() !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    return false
                }
                if (y.toLong() - previousY.toLong() !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    return false
                }
            }
            previousX = x
            previousY = y
        }
        return true
    }

    fun encode(points: List<PagePoint>): ByteArray {
        require(points.size <= DEFAULT_MAX_POINT_COUNT) { "Compact point count is too large" }
        require(canEncodeExactly(points)) { "Points are not exactly representable as Q16 deltas" }
        if (points.isEmpty()) return ByteArray(0)
        val output = ByteArrayOutputStream(points.size.coerceAtMost(16 * 1024))
        var previousX = 0
        var previousY = 0
        points.forEachIndexed { index, point ->
            val x = requireNotNull(exactQuantizedCoordinate(point.x))
            val y = requireNotNull(exactQuantizedCoordinate(point.y))
            if (index == 0) {
                writeSignedVarInt(output, x)
                writeSignedVarInt(output, y)
            } else {
                writeSignedVarInt(output, (x.toLong() - previousX.toLong()).toInt())
                writeSignedVarInt(output, (y.toLong() - previousY.toLong()).toInt())
            }
            previousX = x
            previousY = y
        }
        return output.toByteArray()
    }

    fun decode(
        encoded: ByteArray,
        pointCount: Int,
        maximumPointCount: Int = DEFAULT_MAX_POINT_COUNT,
    ): List<PagePoint> {
        require(maximumPointCount in 0..DEFAULT_MAX_POINT_COUNT) {
            "Compact point limit is invalid"
        }
        require(pointCount in 0..maximumPointCount) { "Compact point count is invalid" }
        if (pointCount == 0) {
            require(encoded.isEmpty()) { "Empty compact points contain trailing data" }
            return emptyList()
        }
        val cursor = VarIntCursor(encoded)
        val result = ArrayList<PagePoint>(pointCount)
        var x = cursor.readSigned()
        var y = cursor.readSigned()
        result += PagePoint(dequantize(x), dequantize(y), 1f)
        repeat(pointCount - 1) {
            val dx = cursor.readSigned()
            val dy = cursor.readSigned()
            x = checkedAdd(x, dx)
            y = checkedAdd(y, dy)
            result += PagePoint(dequantize(x), dequantize(y), 1f)
        }
        require(cursor.exhausted()) { "Compact points contain trailing data" }
        return result
    }

    private fun canonicalCoordinate(value: Float): Float {
        require(value.isFinite()) { "Point coordinate must be finite" }
        val scaled = value.toDouble() * COORDINATE_SCALE.toDouble()
        require(scaled >= Int.MIN_VALUE.toDouble() && scaled <= Int.MAX_VALUE.toDouble()) {
            "Point coordinate is outside the Q16 range"
        }
        return dequantize(round(scaled).toInt())
    }

    private fun exactQuantizedCoordinate(value: Float): Int? {
        if (!value.isFinite()) return null
        val scaled = value.toDouble() * COORDINATE_SCALE.toDouble()
        if (scaled < Int.MIN_VALUE.toDouble() || scaled > Int.MAX_VALUE.toDouble()) return null
        val quantized = round(scaled).toInt()
        return quantized.takeIf { dequantize(it).toBits() == value.toBits() }
    }

    private fun dequantize(value: Int): Float = value.toFloat() / COORDINATE_SCALE.toFloat()

    private fun checkedAdd(left: Int, right: Int): Int {
        val result = left.toLong() + right.toLong()
        require(result in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "Compact point coordinate overflow"
        }
        return result.toInt()
    }

    private fun writeSignedVarInt(output: ByteArrayOutputStream, value: Int) {
        var remaining = ((value shl 1) xor (value shr 31)).toUInt()
        while (remaining >= 0x80u) {
            output.write(((remaining and 0x7fu) or 0x80u).toInt())
            remaining = remaining shr 7
        }
        output.write(remaining.toInt())
    }

    private class VarIntCursor(private val bytes: ByteArray) {
        private var offset = 0

        fun exhausted(): Boolean = offset == bytes.size

        fun readSigned(): Int {
            var value = 0u
            var shift = 0
            var byteCount = 0
            while (byteCount < 5) {
                require(offset < bytes.size) { "Compact points are truncated" }
                val next = bytes[offset++].toInt() and 0xff
                if (byteCount == 4) {
                    require(next and 0xf0 == 0) { "Compact point varint overflows 32 bits" }
                }
                value = value or ((next and 0x7f).toUInt() shl shift)
                byteCount++
                if (next and 0x80 == 0) {
                    require(byteCount == 1 || next != 0) { "Compact point varint is not canonical" }
                    return ((value shr 1).toInt()) xor -((value and 1u).toInt())
                }
                shift += 7
            }
            throw IllegalArgumentException("Compact point varint is too long")
        }
    }
}

/**
 * Bit-exact fallback for ink that cannot use [CompactPagePointCodec] (legacy coordinates or
 * pressure). The uncompressed body is exactly three big-endian IEEE-754 Float bit patterns per
 * point. A single ordinary GZIP member keeps the wire representation compact without changing any
 * runtime value.
 *
 * Point count deliberately remains outside the stream. Callers must supply their context-specific
 * limit, which is checked before the output list or uncompressed buffer is allocated.
 */
object LosslessF32PagePointCodec {
    const val BYTES_PER_POINT = 12
    const val DEFAULT_MAX_POINT_COUNT = 32_768

    fun encode(
        points: List<PagePoint>,
        maximumPointCount: Int = DEFAULT_MAX_POINT_COUNT,
    ): ByteArray {
        require(maximumPointCount in 0..DEFAULT_MAX_POINT_COUNT) {
            "Lossless point limit is invalid"
        }
        require(points.size <= maximumPointCount) { "Lossless point count is too large" }
        val raw = ByteArray(exactDecodedByteCount(points.size))
        var offset = 0
        points.forEach { point ->
            writeIntBigEndian(raw, offset, point.x.toRawBits())
            writeIntBigEndian(raw, offset + 4, point.y.toRawBits())
            writeIntBigEndian(raw, offset + 8, point.pressure.toRawBits())
            offset += BYTES_PER_POINT
        }
        val output = ByteArrayOutputStream(raw.size.coerceAtMost(16 * 1024))
        GZIPOutputStream(output).use { gzip -> gzip.write(raw) }
        return output.toByteArray().also { encoded ->
            check(encoded.size <= maximumEncodedByteCount(points.size)) {
                "Lossless point compression exceeded its deterministic bound"
            }
        }
    }

    fun decode(
        encoded: ByteArray,
        pointCount: Int,
        maximumPointCount: Int = DEFAULT_MAX_POINT_COUNT,
    ): List<PagePoint> {
        require(maximumPointCount in 0..DEFAULT_MAX_POINT_COUNT) {
            "Lossless point limit is invalid"
        }
        require(pointCount in 0..maximumPointCount) { "Lossless point count is invalid" }
        require(encoded.size <= maximumEncodedByteCount(pointCount)) {
            "Lossless point payload exceeds its declared count"
        }
        val raw = inflateSingleGzipMember(
            encoded = encoded,
            expectedByteCount = exactDecodedByteCount(pointCount),
        )
        val result = ArrayList<PagePoint>(pointCount)
        var offset = 0
        repeat(pointCount) {
            result += PagePoint(
                x = Float.fromBits(readIntBigEndian(raw, offset)),
                y = Float.fromBits(readIntBigEndian(raw, offset + 4)),
                pressure = Float.fromBits(readIntBigEndian(raw, offset + 8)),
            )
            offset += BYTES_PER_POINT
        }
        return result
    }

    /** Strict upper bound used before decoding Base64 or allocating the expanded body. */
    fun maximumEncodedByteCount(pointCount: Int): Int {
        require(pointCount in 0..DEFAULT_MAX_POINT_COUNT) { "Lossless point count is invalid" }
        val raw = exactDecodedByteCount(pointCount).toLong()
        // zlib's conservative compressBound plus the GZIP header/trailer allowance.
        val bound = raw + (raw shr 12) + (raw shr 14) + (raw shr 25) + 32L
        return Math.toIntExact(bound)
    }

    private fun exactDecodedByteCount(pointCount: Int): Int =
        Math.multiplyExact(pointCount, BYTES_PER_POINT)

    private fun inflateSingleGzipMember(encoded: ByteArray, expectedByteCount: Int): ByteArray {
        require(encoded.size >= GZIP_HEADER_BYTES + GZIP_TRAILER_BYTES) {
            "Lossless point payload is truncated"
        }
        require(
            encoded[0].toInt() and 0xff == GZIP_MAGIC_FIRST &&
                encoded[1].toInt() and 0xff == GZIP_MAGIC_SECOND &&
                encoded[2].toInt() and 0xff == GZIP_DEFLATE_METHOD
        ) { "Lossless point payload is not GZIP" }
        require(encoded[3].toInt() and 0xff == 0) {
            "Lossless point GZIP header is unsupported"
        }

        val raw = ByteArray(expectedByteCount)
        val inflater = Inflater(true)
        var written = 0
        var remaining: Int
        try {
            inflater.setInput(encoded, GZIP_HEADER_BYTES, encoded.size - GZIP_HEADER_BYTES)
            while (written < raw.size) {
                val count = inflater.inflate(raw, written, raw.size - written)
                if (count == 0) {
                    require(!inflater.needsDictionary()) {
                        "Lossless point payload requires a dictionary"
                    }
                    require(!inflater.needsInput()) { "Lossless point payload is truncated" }
                    require(inflater.finished()) {
                        "Lossless point payload could not make progress"
                    }
                    break
                }
                written += count
            }
            val extra = ByteArray(1)
            val extraCount = inflater.inflate(extra)
            require(extraCount == 0 && inflater.finished()) {
                "Lossless point payload expands beyond its declared count"
            }
            remaining = inflater.remaining
        } catch (error: DataFormatException) {
            throw IllegalArgumentException("Lossless point payload is corrupt", error)
        } finally {
            inflater.end()
        }
        require(written == expectedByteCount) { "Lossless point payload is truncated" }
        require(remaining == GZIP_TRAILER_BYTES) {
            "Lossless point payload contains trailing data or multiple members"
        }

        val trailerOffset = encoded.size - GZIP_TRAILER_BYTES
        val expectedCrc = readUIntLittleEndian(encoded, trailerOffset)
        val expectedSize = readUIntLittleEndian(encoded, trailerOffset + 4)
        val actualCrc = CRC32().apply { update(raw) }.value
        require(expectedCrc == actualCrc) { "Lossless point payload checksum mismatch" }
        require(expectedSize == expectedByteCount.toLong()) {
            "Lossless point payload length mismatch"
        }
        return raw
    }

    private fun writeIntBigEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff shl 24) or
            (bytes[offset + 1].toInt() and 0xff shl 16) or
            (bytes[offset + 2].toInt() and 0xff shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun readUIntLittleEndian(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private const val GZIP_HEADER_BYTES = 10
    private const val GZIP_TRAILER_BYTES = 8
    private const val GZIP_MAGIC_FIRST = 0x1f
    private const val GZIP_MAGIC_SECOND = 0x8b
    private const val GZIP_DEFLATE_METHOD = 8
}

/** Canonicalizes newly-authored general ink once before it enters the annotation document. */
fun StrokeAsset.canonicalizedForNewInk(): StrokeAsset {
    val canonicalPoints = CompactPagePointCodec.canonicalizeNewPoints(points)
    return copy(
        points = canonicalPoints,
        bounds = PageBounds.from(canonicalPoints),
    )
}
