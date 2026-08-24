package com.studyink.core.model

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shape of a single stroke, in canonical page units. Everything here is derived from the points a
 * stroke already carries, so measuring costs nothing at drawing time.
 */
data class StrokeShape(
    /** Distance actually travelled by the pen. */
    val pathLength: Float,
    /** Diagonal of the stroke's bounding box. */
    val spanDiagonal: Float,
    /** Times the pen turned back along the direction the stroke mostly runs in. */
    val reversals: Int,
    /** Shorter bounding-box side over the longer one. 1 is square, 0 is a line. */
    val aspect: Float,
) {
    /**
     * How much of the travelled distance actually went somewhere. A straight line is 1; a stroke
     * that keeps folding back on itself tends towards 0.
     */
    val directness: Float get() = if (pathLength <= 0f) 1f else (spanDiagonal / pathLength).coerceIn(0f, 1f)
}

fun strokeShape(points: List<PagePoint>): StrokeShape {
    if (points.size < 2) return StrokeShape(0f, 0f, 0, 1f)
    var length = 0f
    var minX = points.first().x
    var maxX = minX
    var minY = points.first().y
    var maxY = minY
    for (index in 1 until points.size) {
        val from = points[index - 1]
        val to = points[index]
        length += hypot(to.x - from.x, to.y - from.y)
        minX = min(minX, to.x); maxX = max(maxX, to.x)
        minY = min(minY, to.y); maxY = max(maxY, to.y)
    }
    val width = maxX - minX
    val height = maxY - minY
    val longSide = max(width, height)
    return StrokeShape(
        pathLength = length,
        spanDiagonal = hypot(width, height),
        // Take whichever axis sees more turning. Picking only the longer side misses a tall
        // narrow area filled with short horizontal sweeps, where the run is vertical but the
        // doubling back is horizontal.
        reversals = max(
            countAxisReversals(points, horizontal = true),
            countAxisReversals(points, horizontal = false),
        ),
        aspect = if (longSide <= 0f) 1f else (min(width, height) / longSide).coerceIn(0f, 1f),
    )
}

/**
 * Counts how often the pen turns around along the axis the stroke mostly runs in.
 *
 * Comparing consecutive segment directions does not work for colouring in: each sweep is joined to
 * the next by a short perpendicular step, so no single pair of segments ever turns by more than a
 * right angle even though the stroke plainly doubles back. Projecting onto the dominant axis sees
 * the sweeps for what they are.
 */
private fun countAxisReversals(points: List<PagePoint>, horizontal: Boolean): Int {
    var reversals = 0
    var direction = 0
    var travelled = 0f
    for (index in 1 until points.size) {
        val delta = if (horizontal) {
            points[index].x - points[index - 1].x
        } else {
            points[index].y - points[index - 1].y
        }
        if (abs(delta) < AXIS_REVERSAL_EPSILON) continue
        val next = if (delta > 0f) 1 else -1
        if (direction == 0) {
            direction = next
            travelled = abs(delta)
            continue
        }
        if (next != direction) {
            // Ignore jitter: a turn only counts once the pen has actually run some distance the
            // other way, otherwise a wobbly line reads as a dozen reversals.
            if (travelled >= AXIS_REVERSAL_EPSILON) reversals++
            direction = next
            travelled = abs(delta)
        } else {
            travelled += abs(delta)
        }
    }
    return reversals
}

private const val AXIS_REVERSAL_EPSILON = 1f

/**
 * Whether a stroke looks like filling an area in rather than writing.
 *
 * Colouring in is a pen driven back and forth over the same ground: the travelled distance dwarfs
 * the distance covered, and the direction flips over and over. Handwriting also reverses, but a
 * letter or a digit is short and mostly gets somewhere, so it keeps a far higher directness.
 *
 * This is a hint for a teacher looking at a graph, never a verdict about a student. The thresholds
 * are deliberately conservative: a stroke has to be both long and heavily folded to count.
 */
fun isFillStroke(shape: StrokeShape): Boolean =
    shape.reversals >= FILL_MINIMUM_REVERSALS &&
        shape.directness <= FILL_MAXIMUM_DIRECTNESS &&
        shape.pathLength >= FILL_MINIMUM_PATH_LENGTH

const val FILL_MINIMUM_REVERSALS = 4
const val FILL_MAXIMUM_DIRECTNESS = 0.28f
/** In canonical units, where the page is [CANONICAL_PAGE_WIDTH] wide. */
const val FILL_MINIMUM_PATH_LENGTH = 120f

/** One ten second bucket of a student's writing, as observed by the teacher's reader. */
data class StudentActivitySample(
    val startedAtEpochMillis: Long,
    val pageNumber: Int,
    /** Strokes that appeared during the bucket. */
    val strokeCount: Int,
    /** Distance the pen travelled during the bucket, in canonical units. */
    val inkLength: Float,
    /** Portion of [inkLength] that came from strokes shaped like colouring in, 0..1. */
    val fillRatio: Float,
) {
    val isIdle: Boolean get() = strokeCount == 0
}

/**
 * Folds the strokes that appeared in one bucket into a sample. Called once per bucket rather than
 * per stroke, so its cost never lands on the drawing or sync path.
 */
fun summariseActivity(
    startedAtEpochMillis: Long,
    pageNumber: Int,
    strokes: List<List<PagePoint>>,
): StudentActivitySample {
    var total = 0f
    var fill = 0f
    strokes.forEach { points ->
        val shape = strokeShape(points)
        total += shape.pathLength
        if (isFillStroke(shape)) fill += shape.pathLength
    }
    return StudentActivitySample(
        startedAtEpochMillis = startedAtEpochMillis,
        pageNumber = pageNumber,
        strokeCount = strokes.size,
        inkLength = total,
        fillRatio = if (total <= 0f) 0f else (fill / total).coerceIn(0f, 1f),
    )
}

/** Keeps the most recent [limit] samples; older buckets fall off the front. */
fun List<StudentActivitySample>.trimmedTo(limit: Int): List<StudentActivitySample> =
    if (size <= limit) this else subList(size - limit, size)

/** Largest ink length in the window, used to scale a graph without it jumping every bucket. */
fun List<StudentActivitySample>.peakInkLength(): Float =
    maxOfOrNull { it.inkLength }?.takeIf { it > 0f } ?: 1f

internal fun approximatelyEqual(a: Float, b: Float, tolerance: Float = 0.001f) = abs(a - b) <= tolerance
