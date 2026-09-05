package com.studyink.construction.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ConstructionPointDistanceTest {
    private val solver = ConstraintSolver()
    private fun p(id: String, x: Double, y: Double) = GeometryPoint(id, x, y)
    private fun fix(point: GeometryPoint) = GeometryConstraint("fix-${point.id}", ConstraintType.FIXED_POINT,
        listOf(point.id), targetX = point.x, targetY = point.y)
    private fun distance(scene: ConstructionScene, a: String, b: String): Double {
        val first = scene.point(a)!!; val second = scene.point(b)!!
        return hypot(first.x - second.x, first.y - second.y)
    }
    private fun checked(result: SolveResult): ConstructionScene {
        assertTrue("${result.message} ${result.conflictingConstraintIds} ${result.maxResidual}", result.success)
        assertTrue(result.maxResidual <= ConstraintSolver.HARD_TOLERANCE)
        return result.scene
    }

    @Test fun `two point distance follows drag without creating a segment`() {
        val a = p("A", 0.0, 0.0)
        val scene = ConstructionScene(points = listOf(a, p("P", 6.0, 8.0)), constraints = listOf(fix(a),
            GeometryConstraint("distance", ConstraintType.DISTANCE_POINTS, listOf("A", "P"), 10.0)))
        val result = checked(solver.solve(scene, DragTarget("P", 8.0, 6.0)))
        assertTrue(result.segments.isEmpty())
        assertEquals(10.0, distance(result, "A", "P"), 1e-5)
        assertEquals(8.0, result.point("P")!!.x, .003)
    }

    @Test fun `coincident but distinct points can receive a positive distance`() {
        val a = p("A", 0.0, 0.0)
        val scene = ConstructionScene(points = listOf(a, p("P", 0.0, 0.0)), constraints = listOf(fix(a),
            GeometryConstraint("distance", ConstraintType.DISTANCE_POINTS, listOf("A", "P"), 7.0)))
        assertEquals(7.0, distance(checked(solver.solve(scene)), "A", "P"), 1e-5)
    }

    @Test fun `two distance links preserve their elbow branch`() {
        val a = p("A", 0.0, 0.0); val b = p("B", 12.0, 0.0)
        val scene = ConstructionScene(points = listOf(a, b, p("P", 6.0, 8.0)), constraints = listOf(fix(a), fix(b),
            GeometryConstraint("left", ConstraintType.DISTANCE_POINTS, listOf("A", "P"), 10.0),
            GeometryConstraint("right", ConstraintType.DISTANCE_POINTS, listOf("B", "P"), 10.0)))
        val result = checked(solver.solve(scene, DragTarget("P", 6.0, -8.0)))
        assertTrue(result.point("P")!!.y > 0.0)
        assertEquals(10.0, distance(result, "A", "P"), 1e-5)
        assertEquals(10.0, distance(result, "B", "P"), 1e-5)
    }

    @Test fun `equal point distances may share a point and follow a new driving length`() {
        val a = p("A", 0.0, 0.0)
        val input = ConstructionScene(points = listOf(a, p("B", 6.0, 0.0), p("C", 0.0, 6.0)), constraints = listOf(fix(a),
            GeometryConstraint("driving", ConstraintType.DISTANCE_POINTS, listOf("A", "B"), 6.0),
            GeometryConstraint("equal", ConstraintType.EQUAL_DISTANCE_POINTS, listOf("A", "B", "A", "C"))))
        val scene = checked(solver.solve(input))
        val changed = scene.copy(constraints = scene.constraints.map { if (it.id == "driving") it.copy(value = 9.0) else it })
        val result = checked(solver.solve(changed))
        assertEquals(9.0, distance(result, "A", "B"), 1e-5)
        assertEquals(9.0, distance(result, "A", "C"), 1e-5)
        assertTrue(result.segments.isEmpty())
    }

    @Test fun `conflicting point distance returns unchanged candidate`() {
        val a = p("A", 0.0, 0.0); val b = p("B", 5.0, 0.0)
        val scene = ConstructionScene(points = listOf(a, b), constraints = listOf(fix(a), fix(b),
            GeometryConstraint("distance", ConstraintType.DISTANCE_POINTS, listOf("A", "B"), 8.0)))
        val result = solver.solve(scene)
        assertFalse(result.success)
        assertEquals(scene, result.scene)
    }

    @Test fun `explicit coincidence cannot be silently broken to satisfy positive distance`() {
        val a = p("A", 0.0, 0.0)
        val scene = ConstructionScene(points = listOf(a, p("B", 0.0, 0.0)), constraints = listOf(fix(a),
            GeometryConstraint("same", ConstraintType.COINCIDENT, listOf("A", "B")),
            GeometryConstraint("distance", ConstraintType.DISTANCE_POINTS, listOf("A", "B"), 5.0)))
        val result = solver.solve(scene)
        assertFalse(result.success)
        assertEquals(scene, result.scene)
    }

    @Test fun `disabled equality preserves definition without affecting drag`() {
        val a = p("A", 0.0, 0.0); val b = p("B", 5.0, 0.0)
        val equal = GeometryConstraint("equal", ConstraintType.EQUAL_DISTANCE_POINTS, listOf("A", "B", "A", "C"), enabled = false)
        val scene = ConstructionScene(points = listOf(a, b, p("C", 0.0, 5.0)), constraints = listOf(fix(a), fix(b), equal))
        val result = checked(solver.solve(scene, DragTarget("C", 0.0, 12.0)))
        assertEquals(12.0, distance(result, "A", "C"), .003)
        assertEquals(equal, result.constraints.last())
    }

    @Test fun `zero length equality cannot fake success and invalid point pairs are rejected`() {
        val points = listOf(p("A", 0.0, 0.0), p("B", 0.0, 0.0), p("C", 0.0, 0.0))
        val scene = ConstructionScene(points = points, constraints = points.map(::fix) +
            GeometryConstraint("equal", ConstraintType.EQUAL_DISTANCE_POINTS, listOf("A", "B", "A", "C")))
        assertFalse(solver.solve(scene).success)
        listOf(
            GeometryConstraint("bad", ConstraintType.DISTANCE_POINTS, listOf("A", "A"), 3.0),
            GeometryConstraint("bad", ConstraintType.DISTANCE_POINTS, listOf("A", "B"), 0.0),
            GeometryConstraint("bad", ConstraintType.EQUAL_DISTANCE_POINTS, listOf("A", "B", "B", "A")),
            GeometryConstraint("bad", ConstraintType.EQUAL_DISTANCE_POINTS, listOf("A", "B", "A", "missing")),
        ).forEach { condition -> assertFalse(solver.solve(scene.copy(constraints = listOf(condition))).success) }
    }
}
