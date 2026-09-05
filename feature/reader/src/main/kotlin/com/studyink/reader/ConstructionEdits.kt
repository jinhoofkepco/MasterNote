package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryLineStyle
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import com.studyink.construction.core.SceneValidator
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Convenience recipes, never new rigid entities: every generated relation remains editable. */
internal enum class ConstructionPreset { SQUARE, RECTANGLE, ISOSCELES_TRIANGLE, EQUILATERAL_TRIANGLE, TRAPEZOID }

/** Pure editing commands shared by the canvas and the numerical editor. No ink format is changed. */
internal object ConstructionEdits {
    fun id() = UUID.randomUUID().toString()
    fun pointLabel(index: Int) = ('A'.code + index % 26).toChar().toString() + if (index >= 26) (index / 26).toString() else ""
    fun nextPointLabel(scene: ConstructionScene): String {
        val used = scene.points.mapTo(hashSetOf()) { it.label }
        return generateSequence(0) { it + 1 }.map(::pointLabel).first { it !in used }
    }

    fun addPoint(scene: ConstructionScene, x: Double, y: Double): ConstructionScene =
        addPoint(scene, ConstructionAnchor(x, y))

    /** The new point and any visible snap relations are one atomic editor command. */
    fun addPoint(scene: ConstructionScene, anchor: ConstructionAnchor, colorArgb: Int? = null): ConstructionScene =
        anchor(scene, anchor, colorArgb).first

    private fun anchor(scene: ConstructionScene, anchor: ConstructionAnchor, colorArgb: Int?): Pair<ConstructionScene, String> {
        anchor.pointId?.let { known ->
            require(scene.point(known) != null) { "연결할 점이 변경되었습니다. 시작점을 다시 선택하세요." }
            // Reusing an explicit existing endpoint does not recolor it or create duplicate rules.
            return scene to known
        }
        require(anchor.x.isFinite() && anchor.y.isFinite()) { "점의 위치가 올바르지 않습니다." }
        val lines = anchor.lineIds.distinct().map { lineId ->
            val line = requireNotNull(scene.segment(lineId)) { "연결할 선분이 변경되었습니다. 위치를 다시 선택하세요." }
            val a = requireNotNull(scene.point(line.startPointId)) { "선분의 시작점이 없습니다." }
            val b = requireNotNull(scene.point(line.endPointId)) { "선분의 끝점이 없습니다." }
            val dx = b.x - a.x; val dy = b.y - a.y
            val length = hypot(dx, dy)
            require(length >= SceneValidator.MIN_LENGTH) { "길이가 0인 선분에는 붙일 수 없습니다." }
            require(abs(dx * (anchor.y - a.y) - dy * (anchor.x - a.x)) / length <= 1e-5) {
                "붙일 위치가 변경되었습니다. 선 위의 위치를 다시 선택하세요."
            }
            val amount = ((anchor.x - a.x) * dx + (anchor.y - a.y) * dy) / (length * length)
            require(amount in -1e-8..1.00000001) { "붙일 위치가 선분 밖입니다. 선분 안의 위치를 다시 선택하세요." }
            line
        }
        if (lines.size == 2) {
            val a = scene.point(lines[0].startPointId)!!; val b = scene.point(lines[0].endPointId)!!
            val c = scene.point(lines[1].startPointId)!!; val d = scene.point(lines[1].endPointId)!!
            val dx = b.x - a.x; val dy = b.y - a.y; val ex = d.x - c.x; val ey = d.y - c.y
            require(abs(dx * ey - dy * ex) > 1e-10 * hypot(dx, dy) * hypot(ex, ey)) {
                "평행하거나 겹친 선에는 하나의 교점이 정해지지 않습니다."
            }
        }
        val point = GeometryPoint(id(), anchor.x, anchor.y, nextPointLabel(scene), colorArgb)
        val relations = lines.map { line ->
            GeometryConstraint(id(), ConstraintType.POINT_ON_SEGMENT, listOf(point.id, line.id))
        }
        // The original segment stays intact, including its length condition and endpoint IDs.
        return scene.copy(points = scene.points + point, constraints = scene.constraints + relations) to point.id
    }

    fun addSegment(scene: ConstructionScene, start: ConstructionAnchor, end: ConstructionAnchor, colorArgb: Int? = null,
                   lineStyle: GeometryLineStyle = GeometryLineStyle.SOLID): ConstructionScene {
        val (withStart, a) = anchor(scene, start, colorArgb)
        val (withBoth, b) = anchor(withStart, end, colorArgb)
        require(a != b) { "서로 다른 두 점을 선택하세요." }
        return withBoth.copy(segments = withBoth.segments + GeometrySegment(id(), a, b, colorArgb = colorArgb, lineStyle = lineStyle))
    }

    fun addCircle(scene: ConstructionScene, center: ConstructionAnchor, radius: Double, colorArgb: Int? = null,
                  lineStyle: GeometryLineStyle = GeometryLineStyle.SOLID): ConstructionScene {
        val (withCenter, p) = anchor(scene, center, colorArgb)
        require(radius.isFinite() && radius > 0) { "반지름은 0보다 커야 합니다." }
        return withCenter.copy(circles = withCenter.circles + GeometryCircle(id(), p, radius, colorArgb = colorArgb, lineStyle = lineStyle))
    }

    fun remove(scene: ConstructionScene, selected: Set<String>): ConstructionScene {
        val points = scene.points.filterNot { it.id in selected }
        val pointIds = points.mapTo(hashSetOf()) { it.id }
        val segments = scene.segments.filter { it.id !in selected && it.startPointId in pointIds && it.endPointId in pointIds }
        val circles = scene.circles.filter { it.id !in selected && it.centerPointId in pointIds }
        val remaining = pointIds + segments.map { it.id } + circles.map { it.id }
        return scene.copy(points = points, segments = segments, circles = circles,
            constraints = scene.constraints.filter { c -> c.id !in selected && c.entityIds.all { it in remaining } },
            measurements = scene.measurements.filter { m -> m.id !in selected && m.entityIds.all { it in remaining } })
    }

    /** Presentation-only edit: no point coordinates or condition equations are changed. */
    fun setColor(scene: ConstructionScene, selectedIds: Set<String>, colorArgb: Int): ConstructionScene = scene.copy(
        points = scene.points.map { if (it.id in selectedIds) it.copy(colorArgb = colorArgb) else it },
        segments = scene.segments.map { if (it.id in selectedIds) it.copy(colorArgb = colorArgb) else it },
        circles = scene.circles.map { if (it.id in selectedIds) it.copy(colorArgb = colorArgb) else it },
    )

    /** Only selected strokes change; choosing an endpoint never restyles its adjoining lines. */
    fun setLineStyle(scene: ConstructionScene, selectedIds: Set<String>, lineStyle: GeometryLineStyle): ConstructionScene = scene.copy(
        segments = scene.segments.map { if (it.id in selectedIds) it.copy(lineStyle = lineStyle) else it },
        circles = scene.circles.map { if (it.id in selectedIds) it.copy(lineStyle = lineStyle) else it },
    )

    /** An equality group needs only N-1 independent links; existing chains count too. */
    fun multiEqualLength(scene: ConstructionScene, segmentIds: Collection<String>): ConstructionScene {
        val ids = segmentIds.distinct()
        require(ids.size >= 2 && ids.all { scene.segment(it) != null }) { "길이를 같게 할 선분을 두 개 이상 선택하세요." }
        val parent = scene.segments.associate { it.id to it.id }.toMutableMap()
        fun root(id: String): String {
            var current = id
            while (parent.getValue(current) != current) current = parent.getValue(current)
            return current
        }
        fun join(a: String, b: String) { parent[root(b)] = root(a) }
        scene.constraints.filter { it.enabled && it.type == ConstraintType.EQUAL_LENGTH }.forEach {
            if (it.entityIds.size == 2 && it.entityIds.all(parent::containsKey)) join(it.entityIds[0], it.entityIds[1])
        }
        var result = scene
        for (other in ids.drop(1)) {
            if (root(ids.first()) == root(other)) continue
            result = addConstraint(result, GeometryConstraint(id(), ConstraintType.EQUAL_LENGTH, listOf(ids.first(), other)))
            join(ids.first(), other)
        }
        return validated(result)
    }

    /** All interior division points arrive in one undo step, without splitting the original AB. */
    fun divideSegment(scene: ConstructionScene, segmentId: String, divisions: Int, colorArgb: Int? = null): ConstructionScene {
        require(divisions in 2..SceneValidator.MAX_POINTS) { "등분 수는 2~${SceneValidator.MAX_POINTS} 사이로 입력하세요." }
        val segment = requireNotNull(scene.segment(segmentId)) { "등분할 선분을 다시 선택하세요." }
        val a = requireNotNull(scene.point(segment.startPointId))
        val b = requireNotNull(scene.point(segment.endPointId))
        require(hypot(b.x - a.x, b.y - a.y) >= SceneValidator.MIN_LENGTH) { "길이가 0인 선분은 등분할 수 없습니다." }
        var result = scene
        for (numerator in 1 until divisions) {
            val existing = result.constraints.firstOrNull { c ->
                val savedNumerator = c.numerator
                val savedDenominator = c.denominator
                c.type == ConstraintType.POINT_FRACTION && c.entityIds.getOrNull(1) == segmentId &&
                    savedNumerator != null && savedDenominator != null &&
                    savedNumerator.toLong() * divisions == numerator.toLong() * savedDenominator &&
                    result.point(c.entityIds.firstOrNull().orEmpty()) != null
            }
            if (existing != null) {
                if (!existing.enabled) result = result.copy(constraints = result.constraints.map {
                    if (it.id == existing.id) it.copy(enabled = true) else it
                })
                continue
            }
            val amount = numerator.toDouble() / divisions
            val point = GeometryPoint(id(), a.x + (b.x - a.x) * amount, a.y + (b.y - a.y) * amount,
                nextPointLabel(result), colorArgb)
            result = result.copy(points = result.points + point,
                constraints = result.constraints + GeometryConstraint(id(), ConstraintType.POINT_FRACTION,
                    listOf(point.id, segmentId), numerator = numerator, denominator = divisions))
        }
        return validated(result)
    }

    /** Shape dimensions here are initial placement only. Dragging may change size or orientation. */
    fun createPreset(scene: ConstructionScene, preset: ConstructionPreset, centerX: Double, centerY: Double,
                     size: Double = 6.0, colorArgb: Int? = null,
                     lineStyle: GeometryLineStyle = GeometryLineStyle.SOLID): ConstructionScene {
        require(centerX.isFinite() && centerY.isFinite() && size.isFinite() && size >= SceneValidator.MIN_LENGTH) {
            "도형의 위치 또는 크기가 올바르지 않습니다."
        }
        val half = size / 2
        val coordinates = when (preset) {
            ConstructionPreset.SQUARE -> listOf(-half to -half, half to -half, half to half, -half to half)
            ConstructionPreset.RECTANGLE -> listOf(-half to -half * .65, half to -half * .65, half to half * .65, -half to half * .65)
            ConstructionPreset.ISOSCELES_TRIANGLE -> listOf(-half to -half * .6, half to -half * .6, 0.0 to half)
            ConstructionPreset.EQUILATERAL_TRIANGLE -> {
                val height = size * sqrt(3.0) / 2
                listOf(-half to -height / 3, half to -height / 3, 0.0 to height * 2 / 3)
            }
            ConstructionPreset.TRAPEZOID -> listOf(-half to -half * .65, half to -half * .65, half * .7 to half * .65, -half * .4 to half * .65)
        }
        var result = scene
        val points = coordinates.map { (x, y) ->
            GeometryPoint(id(), centerX + x, centerY + y, nextPointLabel(result), colorArgb).also {
                result = result.copy(points = result.points + it)
            }
        }
        val segments = points.indices.map { i ->
            GeometrySegment(id(), points[i].id, points[(i + 1) % points.size].id, colorArgb = colorArgb, lineStyle = lineStyle)
        }
        result = result.copy(segments = result.segments + segments)
        fun relation(type: ConstraintType, first: Int, second: Int) {
            result = result.copy(constraints = result.constraints + GeometryConstraint(id(), type, listOf(segments[first].id, segments[second].id)))
        }
        when (preset) {
            ConstructionPreset.SQUARE, ConstructionPreset.RECTANGLE -> {
                relation(ConstraintType.PARALLEL, 0, 2)
                relation(ConstraintType.PARALLEL, 1, 3)
                relation(ConstraintType.PERPENDICULAR, 0, 1)
                if (preset == ConstructionPreset.SQUARE) relation(ConstraintType.EQUAL_LENGTH, 0, 1)
            }
            ConstructionPreset.ISOSCELES_TRIANGLE -> relation(ConstraintType.EQUAL_LENGTH, 1, 2)
            ConstructionPreset.EQUILATERAL_TRIANGLE -> {
                relation(ConstraintType.EQUAL_LENGTH, 0, 1)
                relation(ConstraintType.EQUAL_LENGTH, 0, 2)
            }
            ConstructionPreset.TRAPEZOID -> relation(ConstraintType.PARALLEL, 0, 2)
        }
        return validated(result)
    }

    private fun validated(scene: ConstructionScene): ConstructionScene {
        val issues = SceneValidator.validate(scene)
        require(issues.isEmpty()) { issues.joinToString(" ") }
        return scene
    }

    /** Repeated 'show measurement' keeps the existing label identity and the user's placement. */
    fun upsertMeasurement(scene: ConstructionScene, measurement: GeometryMeasurement): ConstructionScene {
        val existing = matchingMeasurement(scene, measurement)
        if (existing != null) return scene
        val candidate = scene.copy(measurements = scene.measurements + measurement)
        val issues = SceneValidator.validate(candidate)
        require(issues.isEmpty()) { issues.joinToString(" ") }
        return candidate
    }

    fun matchingMeasurement(scene: ConstructionScene, measurement: GeometryMeasurement): GeometryMeasurement? =
        scene.measurements.firstOrNull { sameMeasurement(it, measurement) }

    /** Turn the exact live measurement into a driving value. Display precision and label offsets
     * never enter its value, and a distance between otherwise unjoined points creates no line. */
    fun constrainMeasurement(scene: ConstructionScene, measurementId: String, value: Double? = null): ConstructionScene {
        val measurement = requireNotNull(scene.measurements.firstOrNull { it.id == measurementId }) { "측정 표시를 다시 선택하세요." }
        require(measurement.type != MeasurementType.AREA) { "넓이는 측정만 지원합니다." }
        val measured = ConstructionMeasurementGeometry.layout(scene, measurement)?.value
        require(measured != null && measured.isFinite()) { "현재 정의되지 않은 측정값은 고정할 수 없습니다." }
        val target = value ?: measured
        val minimum = if (measurement.type == MeasurementType.ANGLE) 0.0 else SceneValidator.MIN_LENGTH
        val maximum = if (measurement.type == MeasurementType.ANGLE) 180.0 else SceneValidator.MAX_MAGNITUDE
        require(target.isFinite() && target in minimum..maximum) { "고정할 값이 허용 범위를 벗어났습니다." }
        val existing = scene.constraints.firstOrNull { condition ->
            condition.value != null && (condition.type == ConstraintType.DISTANCE_POINTS && measurement.type == MeasurementType.DISTANCE &&
                condition.entityIds.toSet() == measurement.entityIds.toSet() ||
                ConstructionMeasurementGeometry.matchesConstraint(scene, measurement, condition))
        }
        if (existing != null) return validated(scene.copy(constraints = scene.constraints.map {
            if (it.id == existing.id) it.copy(value = target) else it
        }))
        val segment = if (measurement.type == MeasurementType.DISTANCE) measuredSegment(scene, measurement) else null
        val type = when (measurement.type) {
            MeasurementType.DISTANCE -> if (segment != null) ConstraintType.LENGTH else ConstraintType.DISTANCE_POINTS
            MeasurementType.RADIUS -> ConstraintType.RADIUS
            MeasurementType.ANGLE -> ConstraintType.INTERIOR_ANGLE
            MeasurementType.AREA -> error("Area constraints are not supported")
        }
        val refs = segment?.let { listOf(it.id) } ?: measurement.entityIds
        return validated(scene.copy(constraints = scene.constraints + GeometryConstraint(id(), type, refs, value = target)))
    }

    /** Compare live distance/angle measurements without pinning their current numeric values.
     * Existing paused relations stay paused; the explicit condition checkbox controls activation. */
    fun equalMeasurements(scene: ConstructionScene, firstId: String, secondId: String): ConstructionScene {
        val first = requireNotNull(scene.measurements.firstOrNull { it.id == firstId }) { "첫 번째 측정 표시를 다시 선택하세요." }
        val second = requireNotNull(scene.measurements.firstOrNull { it.id == secondId }) { "두 번째 측정 표시를 다시 선택하세요." }
        require(first.type == second.type && first.type in setOf(MeasurementType.DISTANCE, MeasurementType.ANGLE)) {
            "거리끼리 또는 각도끼리 선택하세요. 반지름·넓이의 같음은 지원하지 않습니다."
        }
        require(!sameMeasurement(first, second)) { "서로 다른 두 측정값을 선택하세요." }
        listOf(first, second).forEach { measurement ->
            val value = ConstructionMeasurementGeometry.layout(scene, measurement)?.value
            require(value != null && value.isFinite() && (measurement.type != MeasurementType.DISTANCE || value >= SceneValidator.MIN_LENGTH)) {
                "정의되지 않거나 길이가 0인 측정은 같은 조건으로 묶을 수 없습니다."
            }
        }
        val candidate: GeometryConstraint
        if (first.type == MeasurementType.ANGLE) {
            candidate = GeometryConstraint(id(), ConstraintType.EQUAL_ANGLE, first.entityIds + second.entityIds)
        } else {
            val a = measuredSegment(scene, first); val b = measuredSegment(scene, second)
            candidate = if (a != null && b != null) GeometryConstraint(id(), ConstraintType.EQUAL_LENGTH, listOf(a.id, b.id))
                else GeometryConstraint(id(), ConstraintType.EQUAL_DISTANCE_POINTS, first.entityIds + second.entityIds)
        }
        return if (matchingEqualityConstraint(scene, firstId, secondId) != null) scene
            else validated(scene.copy(constraints = scene.constraints + candidate))
    }

    /** Returns the existing relation, including paused ones, so the UI can show its actual state. */
    fun matchingEqualityConstraint(scene: ConstructionScene, firstId: String, secondId: String): GeometryConstraint? {
        val first = scene.measurements.firstOrNull { it.id == firstId } ?: return null
        val second = scene.measurements.firstOrNull { it.id == secondId } ?: return null
        if (first.type != second.type || sameMeasurement(first, second)) return null
        return when (first.type) {
            MeasurementType.ANGLE -> scene.constraints.firstOrNull {
                it.type == ConstraintType.EQUAL_ANGLE && sameAnglePairs(it.entityIds, first.entityIds + second.entityIds)
            }
            MeasurementType.DISTANCE -> {
                val requestedPairs = setOf(first.entityIds.toSet(), second.entityIds.toSet())
                scene.constraints.firstOrNull { condition ->
                    when (condition.type) {
                        ConstraintType.EQUAL_DISTANCE_POINTS -> condition.entityIds.size == 4 &&
                            setOf(condition.entityIds.take(2).toSet(), condition.entityIds.drop(2).toSet()) == requestedPairs
                        ConstraintType.EQUAL_LENGTH -> condition.entityIds.size == 2 && condition.entityIds.mapNotNull(scene::segment)
                            .map { setOf(it.startPointId, it.endPointId) }.toSet() == requestedPairs
                        else -> false
                    }
                }
            }
            else -> null
        }
    }

    private fun measuredSegment(scene: ConstructionScene, measurement: GeometryMeasurement): GeometrySegment? =
        scene.segments.firstOrNull { setOf(it.startPointId, it.endPointId) == measurement.entityIds.toSet() }

    private fun sameInterior(a: List<String>, b: List<String>) = a.size == 3 && b.size == 3 &&
        a[1] == b[1] && setOf(a[0], a[2]) == setOf(b[0], b[2])

    private fun sameAnglePairs(a: List<String>, b: List<String>): Boolean = a.size == 6 && b.size == 6 &&
        (sameInterior(a.take(3), b.take(3)) && sameInterior(a.drop(3), b.drop(3)) ||
            sameInterior(a.take(3), b.drop(3)) && sameInterior(a.drop(3), b.take(3)))

    private fun sameMeasurement(a: GeometryMeasurement, b: GeometryMeasurement): Boolean {
        if (a.type != b.type) return false
        return when (a.type) {
            MeasurementType.DISTANCE, MeasurementType.AREA -> a.entityIds.toSet() == b.entityIds.toSet()
            MeasurementType.ANGLE -> a.entityIds.size == 3 && b.entityIds.size == 3 &&
                a.entityIds[1] == b.entityIds[1] && setOf(a.entityIds[0], a.entityIds[2]) == setOf(b.entityIds[0], b.entityIds[2])
            MeasurementType.RADIUS -> a.entityIds == b.entityIds
        }
    }

    fun addConstraint(scene: ConstructionScene, constraint: GeometryConstraint): ConstructionScene {
        // Re-edit an existing driving dimension instead of accumulating contradictory duplicates.
        val dimensionTypes = setOf(ConstraintType.LENGTH, ConstraintType.RADIUS, ConstraintType.ANGLE, ConstraintType.DISTANCE_POINT_LINE,
            ConstraintType.POINT_FRACTION, ConstraintType.POINT_DISTANCE, ConstraintType.LENGTH_RATIO, ConstraintType.INTERIOR_ANGLE,
            ConstraintType.DISTANCE_POINTS)
        fun sameInterior(a: List<String>, b: List<String>) = a.size == 3 && b.size == 3 &&
            a[1] == b[1] && setOf(a[0], a[2]) == setOf(b[0], b[2])
        fun samePairOfInteriors(a: List<String>, b: List<String>): Boolean {
            if (a.size != 6 || b.size != 6) return false
            val first = a.take(3); val second = a.drop(3)
            return (sameInterior(first, b.take(3)) && sameInterior(second, b.drop(3))) ||
                (sameInterior(first, b.drop(3)) && sameInterior(second, b.take(3)))
        }
        val existing = scene.constraints.firstOrNull {
            it.type == constraint.type && (it.entityIds == constraint.entityIds ||
                (constraint.type in setOf(ConstraintType.EQUAL_LENGTH, ConstraintType.LENGTH_RATIO) && it.entityIds.toSet() == constraint.entityIds.toSet()) ||
                (constraint.type == ConstraintType.DISTANCE_POINTS && it.entityIds.toSet() == constraint.entityIds.toSet()) ||
                (constraint.type == ConstraintType.EQUAL_DISTANCE_POINTS && it.entityIds.size == 4 && constraint.entityIds.size == 4 &&
                    setOf(it.entityIds.take(2).toSet(), it.entityIds.drop(2).toSet()) ==
                    setOf(constraint.entityIds.take(2).toSet(), constraint.entityIds.drop(2).toSet())) ||
                (constraint.type == ConstraintType.INTERIOR_ANGLE && sameInterior(it.entityIds, constraint.entityIds)) ||
                (constraint.type == ConstraintType.EQUAL_ANGLE && samePairOfInteriors(it.entityIds, constraint.entityIds)))
        }
        if (existing != null) {
            val updated = if (constraint.type in dimensionTypes || constraint.type == ConstraintType.FIXED_POINT) constraint.copy(id = existing.id) else existing.copy(enabled = true)
            return scene.copy(constraints = scene.constraints.map { if (it.id == existing.id) updated else it })
        }
        return scene.copy(constraints = scene.constraints + constraint)
    }

    fun linkedBars(): ConstructionScene {
        val o = GeometryPoint(id(), 0.0, 0.0, "O")
        val p = GeometryPoint(id(), 6.0, 8.0, "P")
        val q = GeometryPoint(id(), 6.0, 8.0, "Q")
        val r = GeometryPoint(id(), 12.0, 8.0, "R")
        val op = GeometrySegment(id(), o.id, p.id)
        val qr = GeometrySegment(id(), q.id, r.id)
        val circle = GeometryCircle(id(), o.id, 10.0)
        return ConstructionScene(listOf(o, p, q, r), listOf(op, qr), listOf(circle), listOf(
            GeometryConstraint(id(), ConstraintType.FIXED_POINT, listOf(o.id), targetX = 0.0, targetY = 0.0),
            GeometryConstraint(id(), ConstraintType.RADIUS, listOf(circle.id), value = 10.0),
            GeometryConstraint(id(), ConstraintType.POINT_ON_CIRCLE, listOf(p.id, circle.id)),
            GeometryConstraint(id(), ConstraintType.COINCIDENT, listOf(p.id, q.id)),
            GeometryConstraint(id(), ConstraintType.LENGTH, listOf(qr.id), value = 6.0),
        ))
    }

    fun trapezoid(): ConstructionScene {
        val s = sqrt(15.0 * 15.0 - 13.3 * 13.3)
        val a = GeometryPoint(id(), s, 13.3, "ㄱ")
        val b = GeometryPoint(id(), 0.0, 0.0, "ㄴ")
        val c = GeometryPoint(id(), 15.0, 0.0, "ㄷ")
        val d = GeometryPoint(id(), s + 6.0, 13.3, "ㄹ")
        val m = GeometryPoint(id(), 15.0 * (s + 6.0) / 21.0, 9.5, "ㅁ")
        val t = (m.x * s + m.y * 13.3) / 225.0
        val e = GeometryPoint(id(), t * s, t * 13.3, "ㅂ")
        val ab = GeometrySegment(id(), a.id, b.id)
        val bc = GeometrySegment(id(), b.id, c.id)
        val cd = GeometrySegment(id(), c.id, d.id)
        val da = GeometrySegment(id(), a.id, d.id)
        val ac = GeometrySegment(id(), a.id, c.id)
        val bd = GeometrySegment(id(), b.id, d.id)
        val me = GeometrySegment(id(), m.id, e.id, "ㅁㅂ")
        return ConstructionScene(listOf(a, b, c, d, m, e), listOf(ab, bc, cd, da, ac, bd, me), emptyList(), listOf(
            GeometryConstraint(id(), ConstraintType.FIXED_POINT, listOf(b.id), targetX = 0.0, targetY = 0.0),
            GeometryConstraint(id(), ConstraintType.HORIZONTAL, listOf(bc.id)),
            GeometryConstraint(id(), ConstraintType.LENGTH, listOf(ab.id), value = 15.0),
            GeometryConstraint(id(), ConstraintType.LENGTH, listOf(bc.id), value = 15.0),
            GeometryConstraint(id(), ConstraintType.LENGTH, listOf(da.id), value = 6.0),
            GeometryConstraint(id(), ConstraintType.PARALLEL, listOf(da.id, bc.id)),
            GeometryConstraint(id(), ConstraintType.DISTANCE_POINT_LINE, listOf(a.id, bc.id), value = 13.3),
            GeometryConstraint(id(), ConstraintType.POINT_ON_LINE, listOf(m.id, ac.id)),
            GeometryConstraint(id(), ConstraintType.POINT_ON_LINE, listOf(m.id, bd.id)),
            GeometryConstraint(id(), ConstraintType.POINT_ON_LINE, listOf(e.id, ab.id)),
            GeometryConstraint(id(), ConstraintType.PERPENDICULAR, listOf(me.id, ab.id)),
        ))
    }
}

internal fun ConstraintType.koreanName(): String = when (this) {
    ConstraintType.FIXED_POINT -> "점 고정"
    ConstraintType.COINCIDENT -> "점 일치"
    ConstraintType.POINT_ON_LINE -> "선 위의 점"
    ConstraintType.POINT_ON_SEGMENT -> "선분 안의 점"
    ConstraintType.POINT_FRACTION -> "등분 / 비율 위치"
    ConstraintType.POINT_DISTANCE -> "끝점에서 거리"
    ConstraintType.POINT_ON_CIRCLE -> "원 위의 점"
    ConstraintType.LENGTH -> "길이"
    ConstraintType.DISTANCE_POINTS -> "두 점 거리"
    ConstraintType.RADIUS -> "반지름"
    ConstraintType.PARALLEL -> "평행"
    ConstraintType.PERPENDICULAR -> "수직"
    ConstraintType.EQUAL_LENGTH -> "같은 길이"
    ConstraintType.EQUAL_DISTANCE_POINTS -> "같은 거리"
    ConstraintType.LENGTH_RATIO -> "길이 비율"
    ConstraintType.INTERIOR_ANGLE -> "세 점 각도"
    ConstraintType.EQUAL_ANGLE -> "같은 각도"
    ConstraintType.ANGLE -> "각도"
    ConstraintType.HORIZONTAL -> "수평"
    ConstraintType.VERTICAL -> "수직 방향"
    ConstraintType.DISTANCE_POINT_LINE -> "수선 거리 / 높이"
}
