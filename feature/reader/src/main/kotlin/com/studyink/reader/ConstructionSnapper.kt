package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometrySegment
import kotlin.math.abs
import kotlin.math.hypot

/** Snap proposals carry exact mathematical incidence rules, not just rounded screen coordinates.
 * Candidates are picked on the visible finite segments; a committed POINT_ON_LINE condition
 * subsequently applies to the supporting line, including extensions. No source line is split. */
internal object ConstructionSnapper {
    fun resolve(scene: ConstructionScene, x: Double, y: Double, tolerance: Double, enabled: Boolean): ConstructionAnchor {
        val free = ConstructionAnchor(x, y)
        if (!enabled || !x.isFinite() || !y.isFinite() || !tolerance.isFinite() || tolerance <= 0) return free
        scene.points.filter { hypot(it.x - x, it.y - y) <= tolerance }
            .minWithOrNull(compareBy({ hypot(it.x - x, it.y - y) }, { it.id }))?.let {
                return ConstructionAnchor(it.x, it.y, it.id, snapLabel = "점 ${it.label.ifBlank { "연결" }}")
            }
        val candidates = scene.segments.mapNotNull { line -> endpoints(scene, line) }
            .filter { projection(x, y, it)?.let { p -> hypot(p.x - x, p.y - y) <= tolerance } == true }
        var closestIntersection: ConstructionAnchor? = null
        var closestDistance = Double.POSITIVE_INFINITY
        for (a in candidates.indices) for (b in a + 1 until candidates.size) {
            val cross = intersection(candidates[a], candidates[b]) ?: continue
            val distance = hypot(cross.x - x, cross.y - y)
            if (distance <= tolerance && distance < closestDistance) {
                closestDistance = distance
                closestIntersection = cross
            }
        }
        if (closestIntersection != null) return closestIntersection
        return candidates.mapNotNull { projection(x, y, it) }
            .minByOrNull { hypot(it.x - x, it.y - y) }?.copy(snapLabel = "직선 위") ?: free
    }

    private data class Line(val id: String, val ax: Double, val ay: Double, val bx: Double, val by: Double)
    private fun endpoints(scene: ConstructionScene, segment: GeometrySegment): Line? {
        val a = scene.point(segment.startPointId) ?: return null
        val b = scene.point(segment.endPointId) ?: return null
        if (hypot(b.x - a.x, b.y - a.y) < 1e-8) return null
        return Line(segment.id, a.x, a.y, b.x, b.y)
    }
    private fun projection(x: Double, y: Double, line: Line): ConstructionAnchor? {
        val dx = line.bx - line.ax; val dy = line.by - line.ay
        val norm = dx * dx + dy * dy
        if (norm < 1e-16) return null
        val t = (((x - line.ax) * dx + (y - line.ay) * dy) / norm).coerceIn(0.0, 1.0)
        return ConstructionAnchor(line.ax + t * dx, line.ay + t * dy, lineIds = listOf(line.id))
    }
    private fun intersection(a: Line, b: Line): ConstructionAnchor? {
        val dx = a.bx - a.ax; val dy = a.by - a.ay
        val ex = b.bx - b.ax; val ey = b.by - b.ay
        val determinant = dx * ey - dy * ex
        if (abs(determinant) <= 1e-10 * hypot(dx, dy) * hypot(ex, ey)) return null
        val rx = b.ax - a.ax; val ry = b.ay - a.ay
        val t = (rx * ey - ry * ex) / determinant
        val u = (rx * dy - ry * dx) / determinant
        if (t !in -1e-8..1.00000001 || u !in -1e-8..1.00000001) return null
        val x = a.ax + t * dx; val y = a.ay + t * dy
        if (!x.isFinite() || !y.isFinite()) return null
        return ConstructionAnchor(x, y, lineIds = listOf(a.id, b.id).sorted(), snapLabel = "교점")
    }
}
