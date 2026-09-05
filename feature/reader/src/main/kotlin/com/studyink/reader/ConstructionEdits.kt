package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import java.util.UUID
import kotlin.math.sqrt

/** Pure editing commands shared by the canvas and the numerical editor. No ink format is changed. */
internal object ConstructionEdits {
    fun id() = UUID.randomUUID().toString()
    fun pointLabel(index: Int) = ('A'.code + index % 26).toChar().toString() + if (index >= 26) (index / 26).toString() else ""
    fun nextPointLabel(scene: ConstructionScene): String {
        val used = scene.points.mapTo(hashSetOf()) { it.label }
        return generateSequence(0) { it + 1 }.map(::pointLabel).first { it !in used }
    }

    fun addPoint(scene: ConstructionScene, x: Double, y: Double): ConstructionScene = scene.copy(
        points = scene.points + GeometryPoint(id(), x, y, nextPointLabel(scene)),
    )

    private fun anchor(scene: ConstructionScene, anchor: ConstructionAnchor): Pair<ConstructionScene, String> {
        anchor.pointId?.let { known -> if (scene.points.any { it.id == known }) return scene to known }
        val point = GeometryPoint(id(), anchor.x, anchor.y, nextPointLabel(scene))
        return scene.copy(points = scene.points + point) to point.id
    }

    fun addSegment(scene: ConstructionScene, start: ConstructionAnchor, end: ConstructionAnchor): ConstructionScene {
        val (withStart, a) = anchor(scene, start)
        val (withBoth, b) = anchor(withStart, end)
        require(a != b) { "서로 다른 두 점을 선택하세요." }
        return withBoth.copy(segments = withBoth.segments + GeometrySegment(id(), a, b))
    }

    fun addCircle(scene: ConstructionScene, center: ConstructionAnchor, radius: Double): ConstructionScene {
        val (withCenter, p) = anchor(scene, center)
        require(radius.isFinite() && radius > 0) { "반지름은 0보다 커야 합니다." }
        return withCenter.copy(circles = withCenter.circles + GeometryCircle(id(), p, radius))
    }

    fun remove(scene: ConstructionScene, selected: Set<String>): ConstructionScene {
        val points = scene.points.filterNot { it.id in selected }
        val pointIds = points.mapTo(hashSetOf()) { it.id }
        val segments = scene.segments.filter { it.id !in selected && it.startPointId in pointIds && it.endPointId in pointIds }
        val circles = scene.circles.filter { it.id !in selected && it.centerPointId in pointIds }
        val remaining = pointIds + segments.map { it.id } + circles.map { it.id }
        return scene.copy(points = points, segments = segments, circles = circles,
            constraints = scene.constraints.filter { c -> c.entityIds.all { it in remaining } })
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
