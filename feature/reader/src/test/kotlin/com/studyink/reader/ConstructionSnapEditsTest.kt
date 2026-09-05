package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintSolver
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.DragTarget
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import com.studyink.construction.core.SceneValidator
import org.junit.Assert.*
import org.junit.Test

class ConstructionSnapEditsTest {
    @Test fun `line snap adds a point and relation atomically while retaining original full length`() {
        val original = baseline()
        val result = ConstructionEdits.addPoint(original, ConstructionAnchor(4.0, 0.0, lineIds = listOf("ab")), BLUE)
        assertEquals(3, result.points.size)
        assertEquals(original.segments, result.segments)
        assertEquals(original.measurements, result.measurements)
        val p = result.points.last()
        val snap = result.constraints.single { it.type == ConstraintType.POINT_ON_LINE }
        assertEquals(listOf(p.id, "ab"), snap.entityIds)
        assertEquals(BLUE, p.colorArgb)
        assertEquals(original.constraints, result.constraints.filterNot { it.id == snap.id })
        assertEquals(listOf("a", "b"), result.measurements.single().entityIds)
        val moved = ConstraintSolver().solve(result, DragTarget(p.id, 6.0, 3.0))
        assertTrue(moved.message, moved.success)
        assertEquals(0.0, moved.scene.point(p.id)!!.y, 1e-4)
        assertEquals(2, original.points.size)
        assertEquals(1, original.segments.size)
    }

    @Test fun `new line starts at a real intersection with both incidence rules in one result`() {
        val base = baseline().copy(
            points = baseline().points + listOf(GeometryPoint("c", 5.0, -5.0), GeometryPoint("d", 5.0, 5.0)),
            segments = baseline().segments + GeometrySegment("cd", "c", "d"),
            constraints = baseline().constraints + listOf(
                fixed("fix-c", "c", 5.0, -5.0), fixed("fix-d", "d", 5.0, 5.0),
            ),
        )
        val result = ConstructionEdits.addSegment(base,
            ConstructionAnchor(5.0, 0.0, lineIds = listOf("ab", "cd"), snapLabel = "교점"),
            ConstructionAnchor(8.0, 3.0), BLUE)
        assertEquals(6, result.points.size)
        assertEquals(3, result.segments.size)
        assertEquals(base.segments, result.segments.take(2))
        val line = result.segments.last()
        val start = result.point(line.startPointId)!!
        val relations = result.constraints.filter { it.type == ConstraintType.POINT_ON_LINE }
        assertEquals(setOf(listOf(start.id, "ab"), listOf(start.id, "cd")), relations.map { it.entityIds }.toSet())
        assertEquals(BLUE, line.colorArgb)
        assertEquals(BLUE, start.colorArgb)
        assertEquals(base.measurements, result.measurements)
        val solved = ConstraintSolver().solve(result)
        assertTrue(solved.message, solved.success)
        assertEquals(5.0, solved.scene.point(start.id)!!.x, 1e-4)
        assertEquals(0.0, solved.scene.point(start.id)!!.y, 1e-4)
        assertEquals(4, base.points.size)
    }

    @Test fun `duplicate line snap candidates produce only one condition`() {
        val result = ConstructionEdits.addPoint(baseline(), ConstructionAnchor(3.0, 0.0, lineIds = listOf("ab", "ab")))
        assertEquals(1, result.constraints.count { it.type == ConstraintType.POINT_ON_LINE })
        assertTrue(SceneValidator.validate(result).isEmpty())
    }

    @Test fun `stale line or point references reject the entire edit instead of silently detaching`() {
        val source = baseline()
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionEdits.addSegment(source, ConstructionAnchor(3.0, 0.0, lineIds = listOf("ab")),
                ConstructionAnchor(7.0, 0.0, lineIds = listOf("deleted-line")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionEdits.addPoint(source, ConstructionAnchor(3.0, 0.0, pointId = "deleted-point"))
        }
        val collapsed = source.copy(points = source.points.map { it.copy(x = 0.0, y = 0.0) })
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionEdits.addPoint(collapsed, ConstructionAnchor(0.0, 0.0, lineIds = listOf("ab")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionEdits.addPoint(source, ConstructionAnchor(3.0, 2.0, lineIds = listOf("ab")))
        }
        val overlapping = source.copy(segments = source.segments + GeometrySegment("same-line", "a", "b"))
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionEdits.addPoint(overlapping, ConstructionAnchor(3.0, 0.0, lineIds = listOf("ab", "same-line")))
        }
        assertEquals(baseline(), source)
    }

    @Test fun `existing endpoint reuse does not duplicate the point or recolor existing geometry`() {
        val original = baseline()
        val result = ConstructionEdits.addSegment(original, ConstructionAnchor(100.0, 100.0, pointId = "a"),
            ConstructionAnchor(4.0, 3.0), BLUE)
        assertEquals(3, result.points.size)
        assertEquals(original.point("a"), result.point("a"))
        assertEquals("a", result.segments.last().startPointId)
        assertEquals(BLUE, result.segments.last().colorArgb)
        assertEquals(BLUE, result.points.last().colorArgb)
        assertEquals(original.constraints, result.constraints)
        assertEquals(original, ConstructionEdits.addPoint(original, ConstructionAnchor(0.0, 0.0, pointId = "a"), BLUE))
    }

    @Test fun `circle center can be attached to a line while radius remains a free measured value`() {
        val original = baseline()
        val result = ConstructionEdits.addCircle(original, ConstructionAnchor(6.0, 0.0, lineIds = listOf("ab")), 2.5, BLUE)
        assertEquals(3, result.points.size)
        assertEquals(1, result.circles.size)
        assertEquals(BLUE, result.circles.single().colorArgb)
        assertEquals(2.5, result.circles.single().radius, 0.0)
        assertEquals(listOf(result.circles.single().centerPointId, "ab"), result.constraints.last().entityIds)
        assertFalse(result.constraints.any { it.type == ConstraintType.RADIUS })
        assertTrue(ConstraintSolver().solve(result).success)
    }

    @Test fun `repeated measurement requests preserve identity direction and user label placement`() {
        val base = baseline()
        val first = base.measurements.single()
        val repeated = ConstructionEdits.upsertMeasurement(base,
            first.copy(id = "new-request", offsetX = 0.0, offsetY = 0.0))
        val reversed = ConstructionEdits.upsertMeasurement(repeated,
            first.copy(id = "reverse-request", entityIds = listOf("b", "a"), offsetX = 99.0))
        assertSame(base, repeated)
        assertSame(base, reversed)
        assertEquals(listOf("a", "b"), reversed.measurements.single().entityIds)
        assertEquals(-1.25, reversed.measurements.single().offsetX, 0.0)
        assertEquals(2.5, reversed.measurements.single().offsetY, 0.0)
        assertEquals(base.constraints, reversed.constraints)
    }

    @Test fun `angle deduplication preserves the vertex while triangle area ignores point order`() {
        val base = baseline().copy(points = baseline().points + GeometryPoint("c", 5.0, 4.0))
        val angle = GeometryMeasurement("angle-b", MeasurementType.ANGLE, listOf("a", "b", "c"), 1.0, 2.0)
        val withAngle = ConstructionEdits.upsertMeasurement(base, angle)
        val reversed = ConstructionEdits.upsertMeasurement(withAngle, angle.copy(id = "reversed", entityIds = listOf("c", "b", "a")))
        assertEquals(withAngle, reversed)
        val anotherVertex = ConstructionEdits.upsertMeasurement(reversed, angle.copy(id = "angle-c", entityIds = listOf("a", "c", "b")))
        assertEquals(2, anotherVertex.measurements.count { it.type == MeasurementType.ANGLE })
        val withArea = ConstructionEdits.upsertMeasurement(anotherVertex,
            GeometryMeasurement("area", MeasurementType.AREA, listOf("a", "b", "c")))
        val sameArea = ConstructionEdits.upsertMeasurement(withArea,
            GeometryMeasurement("area-again", MeasurementType.AREA, listOf("c", "a", "b")))
        assertEquals(withArea, sameArea)
        assertEquals(base.constraints, sameArea.constraints)
    }

    @Test fun `deleting geometry removes only measurements that lose their anchored entities`() {
        val base = baseline().copy(
            circles = listOf(GeometryCircle("circle", "a", 3.0)),
            measurements = baseline().measurements + GeometryMeasurement("radius", MeasurementType.RADIUS, listOf("circle")),
        )
        val noLine = ConstructionEdits.remove(base, setOf("ab"))
        assertTrue(noLine.segments.isEmpty())
        assertEquals(base.measurements, noLine.measurements)
        assertFalse(noLine.constraints.any { it.type == ConstraintType.LENGTH })
        val noCircle = ConstructionEdits.remove(base, setOf("circle"))
        assertEquals(listOf("distance-ab"), noCircle.measurements.map { it.id })
        assertEquals(base.points, noCircle.points)
        val noA = ConstructionEdits.remove(base, setOf("a"))
        assertTrue(noA.segments.isEmpty())
        assertTrue(noA.circles.isEmpty())
        assertTrue(noA.measurements.isEmpty())
        assertTrue(SceneValidator.validate(noA).isEmpty())
    }

    @Test fun `deleting a displayed measurement does not delete its geometry or length condition`() {
        val base = baseline()
        val result = ConstructionEdits.remove(base, setOf("distance-ab"))
        assertTrue(result.measurements.isEmpty())
        assertEquals(base.points, result.points)
        assertEquals(base.segments, result.segments)
        assertEquals(base.constraints, result.constraints)
    }

    @Test fun `recoloring selection changes presentation only and solver preserves it`() {
        val base = baseline().copy(circles = listOf(GeometryCircle("circle", "a", 3.0)))
        val colored = ConstructionEdits.setColor(base, setOf("b", "ab", "circle"), BLUE)
        assertEquals(base.point("a"), colored.point("a"))
        assertEquals(BLUE, colored.point("b")!!.colorArgb)
        assertEquals(BLUE, colored.segments.single().colorArgb)
        assertEquals(BLUE, colored.circles.single().colorArgb)
        assertEquals(base.constraints, colored.constraints)
        assertEquals(base.measurements, colored.measurements)
        val solved = ConstraintSolver().solve(colored)
        assertTrue(solved.message, solved.success)
        assertEquals(colored.points.map { it.colorArgb }, solved.scene.points.map { it.colorArgb })
        assertEquals(colored.segments, solved.scene.segments)
        assertEquals(colored.circles, solved.scene.circles)
        assertEquals(colored.measurements, solved.scene.measurements)
    }

    private fun baseline() = ConstructionScene(
        points = listOf(GeometryPoint("a", 0.0, 0.0, "A", RED), GeometryPoint("b", 10.0, 0.0, "B", RED)),
        segments = listOf(GeometrySegment("ab", "a", "b", colorArgb = RED)),
        constraints = listOf(
            fixed("fix-a", "a", 0.0, 0.0), fixed("fix-b", "b", 10.0, 0.0),
            GeometryConstraint("length-ab", ConstraintType.LENGTH, listOf("ab"), value = 10.0),
        ),
        measurements = listOf(GeometryMeasurement("distance-ab", MeasurementType.DISTANCE, listOf("a", "b"), -1.25, 2.5)),
    )

    private fun fixed(id: String, pointId: String, x: Double, y: Double) =
        GeometryConstraint(id, ConstraintType.FIXED_POINT, listOf(pointId), targetX = x, targetY = y)

    companion object {
        private const val RED = -0x36c6c7
        private const val BLUE = -0xe2913a
    }
}
