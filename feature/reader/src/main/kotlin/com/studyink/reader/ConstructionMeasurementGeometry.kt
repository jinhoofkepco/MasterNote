package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.MeasurementType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class ConstructionVector(val x: Double, val y: Double) {
    operator fun plus(other: ConstructionVector) = ConstructionVector(x + other.x, y + other.y)
    operator fun minus(other: ConstructionVector) = ConstructionVector(x - other.x, y - other.y)
    operator fun times(amount: Double) = ConstructionVector(x * amount, y * amount)
    fun length() = hypot(x, y)
    fun unit() = if (length() > 1e-10) this * (1.0 / length()) else ConstructionVector(0.0, 1.0)
}

/** Model-space layout; offsets stay relative to the same geometric anchor across zoom and reload. */
internal data class ConstructionMeasurementLayout(
    val id: String,
    val type: MeasurementType,
    val baseAnchor: ConstructionVector,
    val label: ConstructionVector,
    val value: Double,
    val first: ConstructionVector,
    val second: ConstructionVector? = null,
    val vertex: ConstructionVector? = null,
    val third: ConstructionVector? = null,
    val angleStart: Double = 0.0,
    val angleSweep: Double = 0.0,
    val arcRadius: Double = 0.0,
    /** Start->end direction markers distinguish solver angles from three-point interior angles. */
    val directionSegments: List<Pair<ConstructionVector, ConstructionVector>> = emptyList(),
    /** Collision avoidance may move the caption without moving its extension/dimension lines. */
    val dimensionGuideAnchor: ConstructionVector? = null,
)

internal object ConstructionMeasurementGeometry {
    /**
     * Driving annotations keep their saved target even while disabled. Their guide geometry is
     * always derived from the current scene, never used to move points or replace solver math.
     */
    fun constraintLayout(scene: ConstructionScene, constraint: GeometryConstraint): ConstructionMeasurementLayout? {
        val refs = constraint.entityIds
        val layout = when (constraint.type) {
            ConstraintType.LENGTH -> {
                val segment = refs.firstOrNull()?.let(scene::segment) ?: return null
                val measurement = scene.measurements.firstOrNull { matchesConstraint(scene, it, constraint) }
                    ?.copy(id = constraint.id)
                    ?: GeometryMeasurement(constraint.id, MeasurementType.DISTANCE, listOf(segment.startPointId, segment.endPointId))
                layout(scene, measurement)
            }
            ConstraintType.RADIUS -> {
                val measurement = scene.measurements.firstOrNull { matchesConstraint(scene, it, constraint) }
                    ?.copy(id = constraint.id)
                    ?: GeometryMeasurement(constraint.id, MeasurementType.RADIUS, refs)
                layout(scene, measurement)
            }
            ConstraintType.DISTANCE_POINT_LINE -> {
                val p = refs.getOrNull(0)?.let(scene::point) ?: return null
                val segment = refs.getOrNull(1)?.let(scene::segment) ?: return null
                val a = scene.point(segment.startPointId) ?: return null
                val b = scene.point(segment.endPointId) ?: return null
                val delta = ConstructionVector(b.x - a.x, b.y - a.y)
                val norm = delta.x * delta.x + delta.y * delta.y
                if (norm < 1e-16) return null
                val point = ConstructionVector(p.x, p.y)
                val amount = ((p.x - a.x) * delta.x + (p.y - a.y) * delta.y) / norm
                val foot = ConstructionVector(a.x, a.y) + delta * amount
                val base = (point + foot) * .5 + delta.unit() * .9
                ConstructionMeasurementLayout(constraint.id, MeasurementType.DISTANCE, base, base,
                    (point - foot).length(), point, foot)
            }
            ConstraintType.ANGLE -> directedAngleLayout(scene, constraint)
            else -> null
        } ?: return null
        return layout.copy(value = constraint.value ?: layout.value)
    }

    /** A saved reference label is retained in the model; its driving label takes over the hit. */
    fun matchesConstraint(scene: ConstructionScene, measurement: GeometryMeasurement, constraint: GeometryConstraint): Boolean =
        when (constraint.type) {
            ConstraintType.LENGTH -> measurement.type == MeasurementType.DISTANCE &&
                measurement.entityIds.toSet() == constraint.entityIds.firstOrNull()?.let(scene::segment)
                    ?.let { setOf(it.startPointId, it.endPointId) }
            ConstraintType.RADIUS -> measurement.type == MeasurementType.RADIUS && measurement.entityIds == constraint.entityIds
            else -> false
        }

    private fun directedAngleLayout(scene: ConstructionScene, constraint: GeometryConstraint): ConstructionMeasurementLayout? {
        val first = constraint.entityIds.getOrNull(0)?.let(scene::segment) ?: return null
        val second = constraint.entityIds.getOrNull(1)?.let(scene::segment) ?: return null
        fun point(id: String) = scene.point(id)?.let { ConstructionVector(it.x, it.y) }
        val a = point(first.startPointId) ?: return null
        val b = point(first.endPointId) ?: return null
        val c = point(second.startPointId) ?: return null
        val d = point(second.endPointId) ?: return null
        val left = b - a; val right = d - c
        if (left.length() < 1e-8 || right.length() < 1e-8) return null
        // Incoming segments must NOT be reversed to form an interior angle: that would silently
        // label its supplement. Translate both original directions to one comparison vertex.
        // Avoid intersecting disconnected supporting lines; near parallels send that point to infinity.
        val shared = listOf(first.startPointId, first.endPointId)
            .firstOrNull { it == second.startPointId || it == second.endPointId }
        val vertex = shared?.let(::point) ?: a
        val start = atan2(left.y, left.x)
        val sweep = atan2(left.x * right.y - left.y * right.x, left.x * right.x + left.y * right.y)
        val radius = (min(left.length(), right.length()) * .28).coerceIn(.7, 1.8)
        val base = vertex + ConstructionVector(cos(start + sweep / 2), sin(start + sweep / 2)) * (radius + .35)
        return ConstructionMeasurementLayout(constraint.id, MeasurementType.ANGLE, base, base,
            Math.toDegrees(abs(sweep)), vertex + left, vertex + right, vertex,
            angleStart = start, angleSweep = sweep, arcRadius = radius,
            directionSegments = listOf(a to b, c to d))
    }

    fun layout(scene: ConstructionScene, measurement: GeometryMeasurement): ConstructionMeasurementLayout? {
        fun point(index: Int) = measurement.entityIds.getOrNull(index)?.let(scene::point)?.let { ConstructionVector(it.x, it.y) }
        val offset = ConstructionVector(measurement.offsetX, measurement.offsetY)
        return when (measurement.type) {
            MeasurementType.DISTANCE -> {
                val a = point(0) ?: return null; val b = point(1) ?: return null
                val delta = b - a
                val normal = if (delta.length() < 1e-10) ConstructionVector(0.0, 1.0) else ConstructionVector(-delta.y, delta.x).unit()
                val base = (a + b) * .5 + normal * .9
                ConstructionMeasurementLayout(measurement.id, measurement.type, base, base + offset, delta.length(), a, b)
            }
            MeasurementType.RADIUS -> {
                val circle = measurement.entityIds.firstOrNull()?.let(scene::circle) ?: return null
                val center = scene.point(circle.centerPointId)?.let { ConstructionVector(it.x, it.y) } ?: return null
                val base = center + ConstructionVector(1 / sqrt(2.0), 1 / sqrt(2.0)) * (circle.radius + .5)
                val label = base + offset
                val edge = center + (label - center).unit() * circle.radius
                ConstructionMeasurementLayout(measurement.id, measurement.type, base, label, circle.radius, center, edge)
            }
            MeasurementType.ANGLE -> {
                val a = point(0) ?: return null; val vertex = point(1) ?: return null; val b = point(2) ?: return null
                val left = a - vertex; val right = b - vertex
                if (left.length() < 1e-8 || right.length() < 1e-8) {
                    val base = vertex + ConstructionVector(.9, .9)
                    return ConstructionMeasurementLayout(measurement.id, measurement.type, base, base + offset,
                        Double.NaN, a, b, vertex)
                }
                val start = atan2(left.y, left.x)
                val sweep = atan2(left.x * right.y - left.y * right.x, left.x * right.x + left.y * right.y)
                val radius = (min(left.length(), right.length()) * .28).coerceIn(.7, 1.8)
                val bisector = ConstructionVector(cos(start + sweep / 2), sin(start + sweep / 2))
                val base = vertex + bisector * (radius + .35)
                val label = base + offset
                val arcRadius = ((label - vertex).length() - .35).coerceAtLeast(.2)
                ConstructionMeasurementLayout(measurement.id, measurement.type, base, label,
                    Math.toDegrees(abs(sweep)), a, b, vertex, angleStart = start, angleSweep = sweep, arcRadius = arcRadius)
            }
            MeasurementType.AREA -> {
                val a = point(0) ?: return null; val b = point(1) ?: return null; val c = point(2) ?: return null
                val base = (a + b + c) * (1.0 / 3)
                val area = abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2
                ConstructionMeasurementLayout(measurement.id, measurement.type, base, base + offset, area, a, b, third = c)
            }
        }
    }
}
