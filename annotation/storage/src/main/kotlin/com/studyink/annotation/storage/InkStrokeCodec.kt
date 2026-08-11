package com.studyink.annotation.storage

import androidx.ink.brush.InputToolType
import androidx.ink.storage.StrokeInputBatchSerialization
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import com.studyink.core.model.PagePoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Stable Ink 1.0.0 stream codec; ByteArray convenience APIs are intentionally not used. */
object InkStrokeCodec {
    fun encode(points: List<PagePoint>): ByteArray {
        require(points.isNotEmpty()) { "A persisted stroke must contain at least one input" }
        val batch = MutableStrokeInputBatch()
        points.forEachIndexed { index, point ->
            batch.add(
                InputToolType.STYLUS,
                point.x,
                point.y,
                point.elapsedTimeMillis.coerceAtLeast(index.toLong()),
                StrokeInput.NO_STROKE_UNIT_LENGTH,
                point.pressure.coerceIn(0f, 1f),
                StrokeInput.NO_TILT,
                StrokeInput.NO_ORIENTATION,
            )
        }
        return ByteArrayOutputStream().use { output ->
            StrokeInputBatchSerialization.encode(batch, output)
            output.toByteArray()
        }
    }

    fun decode(payload: ByteArray): List<PagePoint> {
        val batch = ByteArrayInputStream(payload).use(StrokeInputBatchSerialization::decode)
        return List(batch.size) { index ->
            val input = batch[index]
            PagePoint(
                x = input.x,
                y = input.y,
                pressure = input.pressure.takeIf { input.hasPressure } ?: 1f,
                elapsedTimeMillis = input.elapsedTimeMillis,
            )
        }
    }
}
