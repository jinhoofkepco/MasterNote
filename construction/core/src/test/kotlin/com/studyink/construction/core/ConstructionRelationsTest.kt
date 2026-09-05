package com.studyink.construction.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ConstructionRelationsTest {
    private val solver = ConstraintSolver()
    private fun p(id: String, x: Double, y: Double) = GeometryPoint(id, x, y)
    private fun s(id: String, a: String, b: String) = GeometrySegment(id, a, b)
    private fun fix(id: String, x: Double, y: Double) = GeometryConstraint("fix-$id", ConstraintType.FIXED_POINT,
        listOf(id), targetX = x, targetY = y)
    private fun relation(type: ConstraintType, vararg ids: String, value: Double? = null) =
        GeometryConstraint("relation", type, ids.toList(), value)
    private fun checked(result: SolveResult): ConstructionScene {
        assertTrue("${result.message} ${result.conflictingConstraintIds} ${result.maxResidual}", result.success)
        assertTrue(result.maxResidual <= ConstraintSolver.HARD_TOLERANCE)
        return result.scene
    }
    private fun baseline(condition: GeometryConstraint, pointX: Double = 3.0, fixedEnd: Boolean = true) = ConstructionScene(
        points = listOf(p("A", 0.0, 0.0), p("B", 12.0, 0.0), p("P", pointX, 1.0)),
        segments = listOf(s("AB", "A", "B")),
        constraints = listOf(fix("A", 0.0, 0.0)) + (if (fixedEnd) listOf(fix("B", 12.0, 0.0)) else emptyList()) + condition,
    )
    private fun length(scene: ConstructionScene, a: String, b: String): Double {
        val first = scene.point(a)!!; val second = scene.point(b)!!
        return hypot(first.x - second.x, first.y - second.y)
    }
    private fun angle(scene: ConstructionScene, a: String, b: String, c: String): Double {
        val x = scene.point(a)!!; val y = scene.point(b)!!; val z = scene.point(c)!!
        val ux = x.x - y.x; val uy = x.y - y.y; val vx = z.x - y.x; val vy = z.y - y.y
        return Math.toDegrees(atan2(abs(ux * vy - uy * vx), ux * vx + uy * vy))
    }

    @Test fun `midpoint and both thirds follow a resized and rotated baseline`() {
        listOf(1 to 2, 1 to 3, 2 to 3).forEach { (k, n) ->
            val condition = relation(ConstraintType.POINT_FRACTION, "P", "AB").copy(numerator = k, denominator = n)
            val scene = checked(solver.solve(baseline(condition)))
            assertEquals(12.0 * k / n, scene.point("P")!!.x, 1e-5)
            val edited = scene.copy(constraints = scene.constraints.map {
                if (it.id == "fix-B") it.copy(targetX = 0.0, targetY = 18.0) else it
            })
            val rotated = checked(solver.solve(edited))
            assertEquals(0.0, rotated.point("P")!!.x, 1e-5)
            assertEquals(18.0 * k / n, rotated.point("P")!!.y, 1e-5)
            assertEquals(k, rotated.constraints.last().numerator)
            assertEquals(n, rotated.constraints.last().denominator)
        }
    }

    @Test fun `dragging free end moves its trisection point at exactly one third`() {
        val condition = relation(ConstraintType.POINT_FRACTION, "P", "AB").copy(numerator = 1, denominator = 3)
        val scene = checked(solver.solve(baseline(condition, fixedEnd = false)))
        val moved = checked(solver.solve(scene, DragTarget("B", 15.0, 9.0)))
        assertEquals(15.0, moved.point("B")!!.x, 0.003)
        assertEquals(moved.point("B")!!.x / 3, moved.point("P")!!.x, 1e-5)
        assertEquals(moved.point("B")!!.y / 3, moved.point("P")!!.y, 1e-5)
    }

    @Test fun `ratio along one baseline remains exact rational not rounded decimal`() {
        val scene = checked(solver.solve(baseline(relation(ConstraintType.POINT_FRACTION, "P", "AB")
            .copy(numerator = 2, denominator = 5))))
        assertEquals(2.0 / 3.0, length(scene, "A", "P") / length(scene, "P", "B"), 1e-7)
    }

    @Test fun `point distance uses chosen endpoint and follows rotation without scaling its centimeters`() {
        listOf(false, true).forEach { fromEnd ->
            val scene = checked(solver.solve(baseline(relation(ConstraintType.POINT_DISTANCE, "P", "AB", value = 4.0)
                .copy(fromEnd = fromEnd))))
            assertEquals(if (fromEnd) 8.0 else 4.0, scene.point("P")!!.x, 1e-5)
            val changed = scene.copy(constraints = scene.constraints.map {
                if (it.id == "fix-B") it.copy(targetX = 0.0, targetY = 18.0) else it
            })
            val result = checked(solver.solve(changed))
            assertEquals(0.0, result.point("P")!!.x, 1e-5)
            assertEquals(if (fromEnd) 14.0 else 4.0, result.point("P")!!.y, 1e-5)
        }
    }

    @Test fun `distance beyond fixed end conflicts unless extension explicitly enabled`() {
        val input = baseline(relation(ConstraintType.POINT_DISTANCE, "P", "AB", value = 15.0))
        val failed = solver.solve(input)
        assertFalse(failed.success)
        assertEquals(input, failed.scene)
        val extension = input.copy(constraints = input.constraints.map { if (it.id == "relation") it.copy(allowExtension = true) else it })
        assertEquals(15.0, checked(solver.solve(extension)).point("P")!!.x, 1e-5)
        val fromEnd = extension.copy(constraints = extension.constraints.map { if (it.id == "relation") it.copy(fromEnd = true) else it })
        assertEquals(-3.0, checked(solver.solve(fromEnd)).point("P")!!.x, 1e-5)
    }

    @Test fun `point distance may lengthen an unconstrained baseline to stay inside`() {
        val input = baseline(relation(ConstraintType.POINT_DISTANCE, "P", "AB", value = 15.0), fixedEnd = false)
        val result = checked(solver.solve(input))
        assertEquals(15.0, length(result, "A", "P"), 1e-5)
        assertTrue(length(result, "A", "B") >= 15.0 - 1e-5)
    }

    @Test fun `point on segment cannot be dragged onto extension`() {
        val input = checked(solver.solve(baseline(relation(ConstraintType.POINT_ON_SEGMENT, "P", "AB"))))
        val result = solver.solve(input, DragTarget("P", 18.0, 4.0))
        val scene = checked(result)
        assertTrue(result.dragLimited)
        assertTrue(scene.point("P")!!.x in -1e-5..12.00001)
        assertEquals(0.0, scene.point("P")!!.y, 1e-5)
    }

    @Test fun `zero distance accepts an endpoint but does not collapse baseline`() {
        val input = baseline(relation(ConstraintType.POINT_DISTANCE, "P", "AB", value = 0.0))
        val scene = checked(solver.solve(input))
        assertEquals(0.0, length(scene, "A", "P"), 1e-5)
        assertEquals(12.0, length(scene, "A", "B"), 1e-5)
    }

    @Test fun `length ratio tracks changed driving length`() {
        val input = ConstructionScene(
            points = listOf(p("A", 0.0, 0.0), p("B", 12.0, 0.0), p("C", 0.0, 5.0), p("D", 5.0, 5.0)),
            segments = listOf(s("AB", "A", "B"), s("CD", "C", "D")),
            constraints = listOf(fix("A", 0.0, 0.0), fix("C", 0.0, 5.0),
                GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), 12.0),
                relation(ConstraintType.LENGTH_RATIO, "AB", "CD", value = 2.0)),
        )
        val scene = checked(solver.solve(input))
        assertEquals(6.0, length(scene, "C", "D"), 1e-5)
        val changed = scene.copy(constraints = scene.constraints.map { if (it.id == "length") it.copy(value = 18.0) else it })
        assertEquals(9.0, length(checked(solver.solve(changed)), "C", "D"), 1e-5)
    }

    @Test fun `interior angle uses vertex rather than segment drawing directions`() {
        val input = ConstructionScene(
            points = listOf(p("A", 10.0, 0.0), p("O", 0.0, 0.0), p("C", 3.0, 4.0)),
            segments = listOf(s("CO", "C", "O")),
            constraints = listOf(fix("O", 0.0, 0.0), fix("A", 10.0, 0.0),
                GeometryConstraint("length", ConstraintType.LENGTH, listOf("CO"), 5.0),
                relation(ConstraintType.INTERIOR_ANGLE, "A", "O", "C", value = 120.0)),
        )
        val scene = checked(solver.solve(input))
        assertEquals(120.0, angle(scene, "A", "O", "C"), 1e-4)
        assertTrue(scene.point("C")!!.y > 0.0)
        listOf(0.0, 180.0).forEach { target ->
            val edited = input.copy(constraints = input.constraints.map { if (it.id == "relation") it.copy(value = target) else it })
            assertEquals(target, angle(checked(solver.solve(edited)), "A", "O", "C"), 1e-4)
        }
    }

    @Test fun `two equal interior angles form a bisector that follows whole angle change`() {
        val input = ConstructionScene(
            points = listOf(p("A", 10.0, 0.0), p("O", 0.0, 0.0), p("C", 5.0, 5.0 * kotlin.math.sqrt(3.0)), p("M", 4.0, 3.0)),
            segments = listOf(s("OM", "O", "M")),
            constraints = listOf(fix("O", 0.0, 0.0), fix("A", 10.0, 0.0), fix("C", 5.0, 5.0 * kotlin.math.sqrt(3.0)),
                GeometryConstraint("arm", ConstraintType.LENGTH, listOf("OM"), 5.0),
                relation(ConstraintType.EQUAL_ANGLE, "A", "O", "M", "M", "O", "C")),
        )
        val scene = checked(solver.solve(input))
        assertEquals(30.0, angle(scene, "A", "O", "M"), 1e-4)
        val changed = scene.copy(constraints = scene.constraints.map {
            if (it.id == "fix-C") it.copy(targetX = 10.0 * cos(Math.toRadians(100.0)), targetY = 10.0 * sin(Math.toRadians(100.0))) else it
        })
        val result = checked(solver.solve(changed))
        assertEquals(50.0, angle(result, "A", "O", "M"), 1e-4)
        assertEquals(50.0, angle(result, "M", "O", "C"), 1e-4)
        assertEquals(5.0, length(result, "O", "M"), 1e-5)
        assertTrue(result.point("M")!!.y > 0.0)
    }

    @Test fun `equal angles compare opposite drawing orientations without supplement confusion`() {
        val input = ConstructionScene(
            points = listOf(p("A", 10.0, 0.0), p("O", 0.0, 0.0), p("C", 5.0, 5.0),
                p("D", 10.0, -5.0), p("E", 0.0, -5.0), p("F", 3.0, -9.0)),
            constraints = listOf(fix("A", 10.0, 0.0), fix("O", 0.0, 0.0), fix("C", 5.0, 5.0),
                fix("D", 10.0, -5.0), fix("E", 0.0, -5.0),
                relation(ConstraintType.EQUAL_ANGLE, "A", "O", "C", "D", "E", "F")),
        )
        val scene = checked(solver.solve(input))
        assertEquals(45.0, angle(scene, "D", "E", "F"), 1e-4)
        assertTrue(scene.point("F")!!.y < -5.0)
    }

    @Test fun `disabled fraction preserves definition while letting point move freely`() {
        val condition = relation(ConstraintType.POINT_FRACTION, "P", "AB").copy(numerator = 1, denominator = 3, enabled = false)
        val scene = checked(solver.solve(baseline(condition), DragTarget("P", 20.0, 5.0)))
        assertEquals(20.0, scene.point("P")!!.x, 0.003)
        assertEquals(condition, scene.constraints.last())
        val enabled = scene.copy(constraints = scene.constraints.map { if (it.id == "relation") it.copy(enabled = true) else it })
        assertEquals(4.0, checked(solver.solve(enabled)).point("P")!!.x, 1e-5)
    }

    @Test fun `conflicting fixed fraction point rolls back without partial movement`() {
        val input = baseline(relation(ConstraintType.POINT_FRACTION, "P", "AB").copy(numerator = 1, denominator = 2))
            .let { it.copy(constraints = it.constraints + fix("P", 4.0, 0.0)) }
        val result = solver.solve(input)
        assertFalse(result.success)
        assertEquals(input, result.scene)
        assertTrue(result.conflictingConstraintIds.contains("relation"))
    }

    @Test fun `collapsed rays and ratio lines cannot fake valid relations`() {
        val points = listOf(p("A", 0.0, 0.0), p("B", 0.0, 0.0), p("C", 2.0, 0.0), p("D", 2.0, 0.0))
        val fixed = points.map { fix(it.id, it.x, it.y) }
        val ratio = ConstructionScene(points, listOf(s("AB", "A", "B"), s("CD", "C", "D")),
            constraints = fixed + relation(ConstraintType.LENGTH_RATIO, "AB", "CD", value = 2.0))
        assertFalse(solver.solve(ratio).success)
        val angle = ConstructionScene(points, constraints = fixed + relation(ConstraintType.INTERIOR_ANGLE, "A", "B", "C", value = 0.0))
        assertFalse(solver.solve(angle).success)
    }

    @Test fun `invalid rational and angle references are rejected even while disabled`() {
        val fraction = relation(ConstraintType.POINT_FRACTION, "P", "AB").copy(numerator = 1, denominator = 3)
        listOf(fraction.copy(numerator = 0), fraction.copy(numerator = 3), fraction.copy(denominator = 1),
            fraction.copy(denominator = SceneValidator.MAX_DIVISIONS + 1), fraction.copy(numerator = null),
            fraction.copy(fromEnd = true), fraction.copy(entityIds = listOf("A", "AB"))).forEach { invalid ->
            assertFalse(solver.solve(baseline(invalid.copy(enabled = false))).success)
        }
        val shared = relation(ConstraintType.EQUAL_ANGLE, "A", "B", "P", "P", "B", "A")
        assertFalse(solver.solve(baseline(shared)).success)
        val collapsedTriple = relation(ConstraintType.INTERIOR_ANGLE, "A", "B", "B", value = 30.0)
        assertFalse(solver.solve(baseline(collapsedTriple)).success)
    }
}
