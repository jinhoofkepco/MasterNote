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
            GeometryConstraint(id(), ConstraintType.POINT_ON_LINE, listOf(point.id, line.id))
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
        val dimensionTypes = setOf(ConstraintType.LENGTH, ConstraintType.RADIUS, ConstraintType.ANGLE, ConstraintType.DISTANCE_POINT_LINE)
        val existing = scene.constraints.firstOrNull {
            it.type == constraint.type && it.entityIds == constraint.entityIds
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
    ConstraintType.POINT_ON_CIRCLE -> "원 위의 점"
    ConstraintType.LENGTH -> "길이"
    ConstraintType.RADIUS -> "반지름"
    ConstraintType.PARALLEL -> "평행"
    ConstraintType.PERPENDICULAR -> "수직"
    ConstraintType.EQUAL_LENGTH -> "같은 길이"
    ConstraintType.ANGLE -> "각도"
    ConstraintType.HORIZONTAL -> "수평"
    ConstraintType.VERTICAL -> "수직 방향"
    ConstraintType.DISTANCE_POINT_LINE -> "수선 거리 / 높이"
}
