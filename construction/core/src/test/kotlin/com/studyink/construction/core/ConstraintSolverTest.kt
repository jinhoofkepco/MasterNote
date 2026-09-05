package com.studyink.construction.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

class ConstraintSolverTest {
    private val solver = ConstraintSolver()
    private fun p(id: String, x: Double, y: Double) = GeometryPoint(id, x, y, id)
    private fun s(id: String, a: String, b: String) = GeometrySegment(id, a, b)
    private fun fix(id: String, x: Double, y: Double) = GeometryConstraint("fixed-$id", ConstraintType.FIXED_POINT, listOf(id), targetX = x, targetY = y)
    private fun c(id: String, type: ConstraintType, vararg refs: String, value: Double? = null) = GeometryConstraint(id, type, refs.toList(), value)
    private fun length(scene: ConstructionScene, a: String, b: String): Double {
        val x = scene.point(a)!!; val y = scene.point(b)!!
        return hypot(x.x - y.x, x.y - y.y)
    }
    private fun checked(result: SolveResult): ConstructionScene {
        assertTrue(result.message + " " + result.conflictingConstraintIds + " residual=" + result.maxResidual, result.success)
        assertTrue(result.maxResidual <= ConstraintSolver.HARD_TOLERANCE)
        assertTrue(SceneValidator.validate(result.scene).isEmpty())
        return result.scene
    }

    private fun chain() = ConstructionScene(
        points = listOf(p("O", 0.0, 0.0), p("P", 6.0, 8.0), p("Q", 6.0, 8.0), p("R", 12.0, 8.0)),
        segments = listOf(s("OP", "O", "P"), s("QR", "Q", "R")),
        constraints = listOf(fix("O", 0.0, 0.0), c("10cm", ConstraintType.LENGTH, "OP", value = 10.0),
            c("joined", ConstraintType.COINCIDENT, "P", "Q"), c("6cm", ConstraintType.LENGTH, "QR", value = 6.0)),
    )

    @Test fun `ten plus six chain moves both connected points and preserves lengths`() {
        val original = chain()
        val result = solver.solve(original, DragTarget("R", 10.0, 8.0))
        val scene = checked(result)
        assertEquals(10.0, length(scene, "O", "P"), 1e-5)
        assertEquals(6.0, length(scene, "Q", "R"), 1e-5)
        assertEquals(0.0, length(scene, "P", "Q"), 1e-5)
        assertEquals(10.0, scene.point("R")!!.x, 2e-3)
        assertTrue(lengthBetween(original.point("P")!!, scene.point("P")!!) > 0.1)
        assertEquals(6.0, original.point("P")!!.x, 0.0)
    }

    @Test fun `unreachable chain drag never stretches its fixed lengths`() {
        val result = solver.solve(chain(), DragTarget("R", 18.0, 0.0))
        val scene = checked(result)
        assertTrue(result.dragLimited)
        assertEquals(10.0, length(scene, "O", "P"), 1e-5)
        assertEquals(6.0, length(scene, "Q", "R"), 1e-5)
        assertTrue(length(scene, "O", "R") <= 16.00001)
        assertTrue(length(scene, "O", "R") >= 4.0 - 1e-5)
        assertEquals(16.0, length(scene, "O", "R"), 0.02)
    }

    @Test fun `point stays on fixed radius circle when pointer is outside it`() {
        val input = ConstructionScene(
            points = listOf(p("O", 0.0, 0.0), p("P", 6.0, 8.0)),
            circles = listOf(GeometryCircle("circle", "O", 10.0)),
            constraints = listOf(fix("O", 0.0, 0.0), c("radius", ConstraintType.RADIUS, "circle", value = 10.0),
                c("on", ConstraintType.POINT_ON_CIRCLE, "P", "circle")),
        )
        val result = solver.solve(input, DragTarget("P", 18.0, 9.0))
        val scene = checked(result)
        assertTrue(result.dragLimited)
        assertEquals(10.0, length(scene, "O", "P"), 1e-6)
        assertEquals(10.0, scene.circle("circle")!!.radius, 1e-6)
        assertEquals(2.0, scene.point("P")!!.x / scene.point("P")!!.y, 0.01)
    }

    @Test fun `two intersecting circles retain the chosen intersection`() {
        val input = ConstructionScene(
            points = listOf(p("A", 0.0, 0.0), p("B", 12.0, 0.0), p("P", 6.0, 8.0)),
            circles = listOf(GeometryCircle("cA", "A", 10.0), GeometryCircle("cB", "B", 10.0)),
            constraints = listOf(fix("A", 0.0, 0.0), fix("B", 12.0, 0.0),
                c("rA", ConstraintType.RADIUS, "cA", value = 10.0), c("rB", ConstraintType.RADIUS, "cB", value = 10.0),
                c("pA", ConstraintType.POINT_ON_CIRCLE, "P", "cA"), c("pB", ConstraintType.POINT_ON_CIRCLE, "P", "cB")),
        )
        val scene = checked(solver.solve(input, DragTarget("P", 6.0, -8.0)))
        assertTrue(scene.point("P")!!.y > 0)
        assertEquals(10.0, length(scene, "A", "P"), 1e-5)
        assertEquals(10.0, length(scene, "B", "P"), 1e-5)
    }

    @Test fun `new point on circle can start at center without a zero gradient dead end`() {
        val input = ConstructionScene(
            points = listOf(p("O", 0.0, 0.0), p("P", 0.0, 0.0)),
            circles = listOf(GeometryCircle("circle", "O", 10.0)),
            constraints = listOf(fix("O", 0.0, 0.0), c("radius", ConstraintType.RADIUS, "circle", value = 10.0),
                c("on", ConstraintType.POINT_ON_CIRCLE, "P", "circle")),
        )
        val scene = checked(solver.solve(input))
        assertEquals(10.0, length(scene, "O", "P"), 1e-5)
        assertEquals(0.0, scene.point("O")!!.x, 1e-5)
    }

    @Test fun `zero and straight angle constraints work across angle branch cut`() {
        fun setup(target: Double) = ConstructionScene(
            points = listOf(p("A", 0.0, 0.0), p("B", 5.0, 0.0), p("C", 8.0, 0.1)),
            segments = listOf(s("AB", "A", "B"), s("AC", "A", "C")),
            constraints = listOf(fix("A", 0.0, 0.0), fix("B", 5.0, 0.0),
                c("len", ConstraintType.LENGTH, "AC", value = 8.0), c("angle", ConstraintType.ANGLE, "AB", "AC", value = target)),
        )
        val straight = checked(solver.solve(setup(180.0)))
        assertEquals(-8.0, straight.point("C")!!.x, 1e-4)
        assertEquals(0.0, straight.point("C")!!.y, 1e-4)
        val zero = checked(solver.solve(setup(0.0)))
        assertEquals(8.0, zero.point("C")!!.x, 1e-4)
        assertEquals(0.0, zero.point("C")!!.y, 1e-4)
    }

    @Test fun `conflicting dimensions fail atomically and name residual constraints`() {
        val input = chain().copy(constraints = chain().constraints + c("contradiction", ConstraintType.LENGTH, "OP", value = 12.0))
        val result = solver.solve(input)
        assertFalse(result.success)
        assertEquals(input, result.scene)
        assertTrue(result.conflictingConstraintIds.contains("10cm"))
        assertTrue(result.conflictingConstraintIds.contains("contradiction"))
    }

    @Test fun `disabled condition is not silently active`() {
        val input = chain().copy(constraints = chain().constraints.map { if (it.id == "6cm") it.copy(enabled = false) else it })
        val scene = checked(solver.solve(input, DragTarget("R", 18.0, 8.0)))
        assertEquals(18.0, scene.point("R")!!.x, 2e-3)
        assertTrue(length(scene, "Q", "R") > 10)
    }

    @Test fun `same numeric lengths remain independent unless equal length is explicit`() {
        val input = ConstructionScene(
            points = listOf(p("A", 0.0, 0.0), p("B", 10.0, 0.0), p("C", 0.0, 5.0), p("D", 10.0, 5.0)),
            segments = listOf(s("AB", "A", "B"), s("CD", "C", "D")),
            constraints = listOf(fix("A", 0.0, 0.0), fix("C", 0.0, 5.0),
                c("ab", ConstraintType.LENGTH, "AB", value = 12.0), c("cd", ConstraintType.LENGTH, "CD", value = 10.0)),
        )
        val scene = checked(solver.solve(input))
        assertEquals(12.0, length(scene, "A", "B"), 1e-5)
        assertEquals(10.0, length(scene, "C", "D"), 1e-5)
    }

    @Test fun `parallel perpendicular equal length and angle are actual equations`() {
        val input = ConstructionScene(
            points = listOf(p("A", 0.0, 0.0), p("B", 5.0, 0.0), p("C", 1.0, 3.0), p("D", 5.0, 4.0), p("E", 0.1, 4.5)),
            segments = listOf(s("AB", "A", "B"), s("CD", "C", "D"), s("AE", "A", "E")),
            constraints = listOf(fix("A", 0.0, 0.0), fix("B", 5.0, 0.0),
                c("parallel", ConstraintType.PARALLEL, "AB", "CD"), c("equal", ConstraintType.EQUAL_LENGTH, "AB", "CD"),
                c("perp", ConstraintType.PERPENDICULAR, "AB", "AE")),
        )
        val scene = checked(solver.solve(input))
        assertEquals(scene.point("C")!!.y, scene.point("D")!!.y, 1e-5)
        assertEquals(5.0, length(scene, "C", "D"), 1e-5)
        assertEquals(0.0, scene.point("E")!!.x, 1e-5)
        val angled = scene.copy(constraints = scene.constraints.filterNot { it.id == "perp" } + c("angle", ConstraintType.ANGLE, "AB", "AE", value = 60.0))
        val result = checked(solver.solve(angled))
        val e = result.point("E")!!
        assertEquals(60.0, Math.toDegrees(acos(e.x / hypot(e.x, e.y))), 1e-4)
    }

    @Test fun `angle can start from collinear sketch`() {
        val input = ConstructionScene(
            points = listOf(p("A", 0.0, 0.0), p("B", 5.0, 0.0), p("C", 8.0, 0.0)),
            segments = listOf(s("AB", "A", "B"), s("AC", "A", "C")),
            constraints = listOf(fix("A", 0.0, 0.0), fix("B", 5.0, 0.0),
                c("len", ConstraintType.LENGTH, "AC", value = 8.0), c("angle", ConstraintType.ANGLE, "AB", "AC", value = 90.0)),
        )
        val scene = checked(solver.solve(input))
        assertEquals(0.0, scene.point("C")!!.x, 1e-4)
        assertEquals(8.0, scene.point("C")!!.y, 1e-4)
    }

    @Test fun `trapezoid diagonals intersection and perpendicular foot give 3 point 8`() {
        val h = 13.3
        val x = sqrt(225.0 - h * h)
        val input = ConstructionScene(
            points = listOf(p("A", x, h), p("B", 0.0, 0.0), p("C", 15.0, 0.0), p("D", x + 6.0, h),
                p("M", 9.0, 9.0), p("F", 5.0, 8.0)),
            segments = listOf(s("AC", "A", "C"), s("BD", "B", "D"), s("AB", "A", "B"), s("MF", "M", "F")),
            constraints = listOf(fix("A", x, h), fix("B", 0.0, 0.0), fix("C", 15.0, 0.0), fix("D", x + 6.0, h),
                c("cross1", ConstraintType.POINT_ON_LINE, "M", "AC"), c("cross2", ConstraintType.POINT_ON_LINE, "M", "BD"),
                c("foot", ConstraintType.POINT_ON_LINE, "F", "AB"), c("right", ConstraintType.PERPENDICULAR, "MF", "AB")),
        )
        val scene = checked(solver.solve(input))
        assertEquals(3.8, length(scene, "M", "F"), 1e-5)
        val newHeight = 14.0
        val newX = sqrt(225.0 - newHeight * newHeight)
        val changed = scene.copy(constraints = scene.constraints.map {
            when (it.id) { "fixed-A" -> it.copy(targetX = newX, targetY = newHeight)
                "fixed-D" -> it.copy(targetX = newX + 6.0, targetY = newHeight)
                else -> it }
        })
        assertEquals(4.0, length(checked(solver.solve(changed)), "M", "F"), 1e-5)
    }

    @Test fun `point on supporting line may lie on extension`() {
        val input = ConstructionScene(
            points = listOf(p("A", 0.0, 0.0), p("B", 5.0, 0.0), p("P", 8.0, 0.0)),
            segments = listOf(s("AB", "A", "B")),
            constraints = listOf(fix("A", 0.0, 0.0), fix("B", 5.0, 0.0), c("on", ConstraintType.POINT_ON_LINE, "P", "AB")),
        )
        val scene = checked(solver.solve(input, DragTarget("P", 12.0, 4.0)))
        assertEquals(12.0, scene.point("P")!!.x, 2e-3)
        assertEquals(0.0, scene.point("P")!!.y, 1e-5)
    }

    @Test fun `trapezoid is constrained by dimensions and height without fixed upper vertices`() {
        val h = 13.3
        val x = sqrt(225.0 - h * h)
        val input = ConstructionScene(
            points = listOf(p("A", x, h), p("B", 0.0, 0.0), p("C", 15.0, 0.0), p("D", x + 6.0, h),
                p("M", 9.0, 9.0), p("F", 5.0, 8.0)),
            segments = listOf(s("AC", "A", "C"), s("BD", "B", "D"), s("AB", "A", "B"),
                s("MF", "M", "F"), s("BC", "B", "C"), s("AD", "A", "D")),
            constraints = listOf(fix("B", 0.0, 0.0),
                c("bottom", ConstraintType.LENGTH, "BC", value = 15.0), c("top", ConstraintType.LENGTH, "AD", value = 6.0),
                c("left", ConstraintType.LENGTH, "AB", value = 15.0), c("horizontal", ConstraintType.HORIZONTAL, "BC"),
                c("parallel", ConstraintType.PARALLEL, "AD", "BC"), c("height", ConstraintType.DISTANCE_POINT_LINE, "A", "BC", value = h),
                c("cross1", ConstraintType.POINT_ON_LINE, "M", "AC"), c("cross2", ConstraintType.POINT_ON_LINE, "M", "BD"),
                c("foot", ConstraintType.POINT_ON_LINE, "F", "AB"), c("right", ConstraintType.PERPENDICULAR, "MF", "AB")),
        )
        val scene = checked(solver.solve(input))
        assertEquals(3.8, length(scene, "M", "F"), 1e-5)
        val changed = scene.copy(constraints = scene.constraints.map { if (it.id == "height") it.copy(value = 14.0) else it })
        val result = checked(solver.solve(changed))
        assertEquals(4.0, length(result, "M", "F"), 1e-5)
        assertEquals(15.0, length(result, "A", "B"), 1e-5)
        assertTrue(result.point("A")!!.y > 0)
        val impossible = scene.copy(constraints = scene.constraints.map { if (it.id == "height") it.copy(value = 16.0) else it })
        assertFalse(solver.solve(impossible).success)
        assertEquals(impossible, solver.solve(impossible).scene)
    }

    @Test fun `vertical and zero distance are accepted but collapsed baseline is not`() {
        val input = ConstructionScene(
            points = listOf(p("A", 0.0, 0.0), p("B", 1.0, 5.0), p("P", 3.0, 4.0)),
            segments = listOf(s("AB", "A", "B")),
            constraints = listOf(fix("A", 0.0, 0.0), c("vertical", ConstraintType.VERTICAL, "AB"),
                c("distance", ConstraintType.DISTANCE_POINT_LINE, "P", "AB", value = 0.0)),
        )
        val scene = checked(solver.solve(input))
        assertEquals(0.0, scene.point("B")!!.x, 1e-5)
        assertEquals(0.0, scene.point("P")!!.x, 1e-5)
    }

    @Test fun `fully fixed scene rejects movement but remains valid`() {
        val input = ConstructionScene(points = listOf(p("A", 2.0, 3.0)), constraints = listOf(fix("A", 2.0, 3.0)))
        val result = solver.solve(input, DragTarget("A", 8.0, 9.0))
        val scene = checked(result)
        assertTrue(result.dragLimited)
        assertEquals(0, result.degreesOfFreedom)
        assertEquals(2.0, scene.point("A")!!.x, 1e-6)
        assertEquals(3.0, scene.point("A")!!.y, 1e-6)
    }

    @Test fun `redundant consistent conditions do not count as contradictions`() {
        val input = chain().copy(constraints = chain().constraints + c("duplicate-length", ConstraintType.LENGTH, "OP", value = 10.0))
        checked(solver.solve(input, DragTarget("R", 11.0, 7.0)))
    }

    @Test fun `malformed and non finite scenes are rejected without changing them`() {
        val input = ConstructionScene(points = listOf(p("A", Double.NaN, 0.0)))
        assertFalse(solver.solve(input).success)
        assertEquals(input, solver.solve(input).scene)
        assertFalse(solver.solve(ConstructionScene(points = listOf(p("A", 0.0, 0.0)), segments = listOf(s("bad", "A", "missing")))).success)
        assertFalse(solver.solve(chain(), DragTarget("R", Double.POSITIVE_INFINITY, 0.0)).success)
    }

    @Test fun `zero length line cannot fake a perpendicular constraint`() {
        val input = ConstructionScene(
            points = listOf(p("A", 0.0, 0.0), p("B", 0.0, 0.0), p("C", 3.0, 0.0)),
            segments = listOf(s("AB", "A", "B"), s("AC", "A", "C")),
            constraints = listOf(fix("A", 0.0, 0.0), fix("B", 0.0, 0.0), c("perp", ConstraintType.PERPENDICULAR, "AB", "AC")),
        )
        val result = solver.solve(input)
        assertFalse(result.success)
        assertEquals(input, result.scene)
    }

    private fun lengthBetween(a: GeometryPoint, b: GeometryPoint) = hypot(a.x - b.x, a.y - b.y)
}
