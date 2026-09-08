package com.studyink.memo.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/** Memo adaptation of core/model's LosslessF32PagePointCodec, with the memo's 100,000 point
 * bound. Exactly x/y/pressure's IEEE-754 bits, big endian; never Q16 or synthesized pressure.
 * One ordinary GZIP member only. Counts and Base64 sizes are checked before allocation. */
internal object LosslessMemoPointCodec {
    const val ENCODING = "f32-gzip-base64-v1"
    private const val BYTES_PER_POINT = 12
    internal val compressionCount = AtomicLong()
    internal val inflationCount = AtomicLong()

    fun encode(points: List<MemoPoint>): String {
        requireCount(points.size)
        compressionCount.incrementAndGet()
        val raw = ByteBuffer.allocate(points.size * BYTES_PER_POINT)
        points.forEach { raw.putInt(it.normalizedX.toRawBits()).putInt(it.normalizedY.toRawBits()).putInt(it.pressure.toRawBits()) }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw.array()) }
        val compressed = output.toByteArray()
        check(compressed.size <= maximumEncodedBytes(points.size))
        return Base64.getEncoder().encodeToString(compressed)
    }

    fun decode(text: String, count: Int): List<MemoPoint> {
        requireCount(count)
        inflationCount.incrementAndGet()
        val maxBytes = maximumEncodedBytes(count)
        require(text.length <= ((maxBytes + 2) / 3) * 4) { "Memo point Base64 exceeds its declared count" }
        val encoded = Base64.getDecoder().decode(text)
        require(encoded.size <= maxBytes) { "Memo point payload exceeds its declared count" }
        require(encoded.size >= 18 && encoded[0].toInt() and 255 == 31 &&
            encoded[1].toInt() and 255 == 139 && encoded[2].toInt() and 255 == 8 && encoded[3].toInt() == 0) {
            "Memo points require one ordinary GZIP member"
        }
        val raw = ByteArray(count * BYTES_PER_POINT)
        val inflater = Inflater(true)
        var written = 0
        val remaining: Int
        try {
            inflater.setInput(encoded, 10, encoded.size - 10)
            while (written < raw.size) {
                val read = inflater.inflate(raw, written, raw.size - written)
                if (read == 0) {
                    require(!inflater.needsDictionary() && !inflater.needsInput() && inflater.finished()) {
                        "Memo points are truncated or cannot make progress"
                    }
                    break
                }
                written += read
            }
            require(inflater.inflate(ByteArray(1)) == 0 && inflater.finished()) {
                "Memo points expand beyond their declared count"
            }
            remaining = inflater.remaining
        } catch (error: DataFormatException) {
            throw IllegalArgumentException("Memo point compression is corrupt", error)
        } finally {
            inflater.end()
        }
        require(written == raw.size && remaining == 8) { "Memo points are truncated or contain trailing data/multiple members" }
        val trailer = encoded.size - 8
        require(readUIntLE(encoded, trailer) == CRC32().apply { update(raw) }.value) { "Memo point checksum mismatch" }
        require(readUIntLE(encoded, trailer + 4) == raw.size.toLong()) { "Memo point uncompressed length mismatch" }
        val input = ByteBuffer.wrap(raw)
        return List(count) { MemoPoint(Float.fromBits(input.int), Float.fromBits(input.int), Float.fromBits(input.int)) }
    }

    private fun requireCount(count: Int) {
        require(count > 0) { "Memo stroke must contain points" }
        checkMemoCapacity(count, MemoStorageLimits.MAX_POINTS_PER_STROKE, MemoLimitScope.POINTS_PER_STROKE)
    }

    private fun maximumEncodedBytes(count: Int): Int {
        val raw = count * BYTES_PER_POINT
        return raw + (raw shr 12) + (raw shr 14) + (raw shr 25) + 32
    }

    private fun readUIntLE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 255L) or ((bytes[offset + 1].toLong() and 255L) shl 8) or
            ((bytes[offset + 2].toLong() and 255L) shl 16) or ((bytes[offset + 3].toLong() and 255L) shl 24)
}
