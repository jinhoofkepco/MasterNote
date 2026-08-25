package com.studyink.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Small binary frame for a page's already-encoded annotation operations.
 *
 * Individual operation bytes remain owned and validated by PageOperationLogStore. This wrapper
 * only makes the Telegram payload deterministic and rejects truncation, trailing bytes, and
 * allocation-heavy declared lengths before any operation reaches storage.
 */
internal object RemotePageDeltaCodec {
    fun encode(operations: List<ByteArray>): ByteArray {
        require(operations.isNotEmpty()) { "A page delta cannot be empty" }
        require(operations.size <= MAX_OPERATION_COUNT) { "A page delta has too many operations" }
        var encodedSize = HEADER_BYTES
        operations.forEach { operation ->
            require(operation.isNotEmpty()) { "A page delta operation cannot be empty" }
            require(operation.size <= MAX_OPERATION_BYTES) { "A page delta operation is too large" }
            encodedSize = Math.addExact(encodedSize, LENGTH_BYTES + operation.size)
            require(encodedSize <= MAX_DELTA_BYTES) { "A page delta is too large" }
        }
        return ByteArrayOutputStream(encodedSize).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(operations.size)
                operations.forEach { operation ->
                    output.writeInt(operation.size)
                    output.write(operation)
                }
            }
            bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): List<ByteArray> {
        require(bytes.size in HEADER_BYTES..MAX_DELTA_BYTES) { "Page delta size is invalid" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Page delta header is invalid" }
            val count = input.readInt()
            require(count in 1..MAX_OPERATION_COUNT) { "Page delta operation count is invalid" }
            buildList(count) {
                repeat(count) {
                    val length = input.readInt()
                    require(length in 1..MAX_OPERATION_BYTES) { "Page delta operation size is invalid" }
                    require(length <= input.available()) { "Page delta is truncated" }
                    add(ByteArray(length).also(input::readFully))
                }
                require(input.available() == 0) { "Page delta has trailing bytes" }
            }
        }
    }

    private const val MAGIC: Int = 0x4d4e4431 // MND1
    private const val HEADER_BYTES: Int = 8
    private const val LENGTH_BYTES: Int = 4
    private const val MAX_OPERATION_COUNT: Int = 16_384
    private const val MAX_OPERATION_BYTES: Int = 512 * 1024
    private const val MAX_DELTA_BYTES: Int = 1024 * 1024
}
