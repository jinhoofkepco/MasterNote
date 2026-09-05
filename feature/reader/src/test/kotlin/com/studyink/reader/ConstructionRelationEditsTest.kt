package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintSolver
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.DragTarget
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryLineStyle
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.SceneValidator
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ConstructionRelationEditsTest {
    private val solver = ConstraintSolver()

    @Test fun `division retains original segment styles length rule and exact fraction definitions`() {
        val initial = lineScene().copy(constraints = listOf(GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 12.0)))
        val divided = ConstructionEdits.divideSegment(initial, "AB", 3, 0xFF356FA8.toInt())
        assertEquals(initial.segments, divided.segments)
        assertEquals(initial.constraints.single(), divided.constraints.first())
        assertEquals(listOf(1, 2), divided.constraints.drop(1).map { it.numerator })
        assertTrue(divided.constraints.drop(1).all { it.denominator == 3 })
        assertEquals(listOf(4.0, 8.0), divided.points.drop(2).map { it.x })
        assertTrue(divided.points.drop(2).all { it.colorArgb == 0xFF356FA8.toInt() })
        assertEquals(divided, ConstructionEdits.divideSegment(divided, "AB", 3))
        assertTrue(SceneValidator.validate(divided).isEmpty())
    }

    @Test fun `equivalent midpoint fraction is reused and paused relation reenabled`() {
        val initial = ConstructionEdits.divideSegment(lineScene(), "AB", 2)
        val rule = initial.constraints.single().copy(numerator = 2, denominator = 4, enabled = false)
        val paused = initial.copy(constraints = listOf(rule))
        val result = ConstructionEdits.divideSegment(paused, "AB", 2)
        assertEquals(initial.points, result.points)
        assertEquals(rule.id, result.constraints.single().id)
        assertTrue(result.constraints.single().enabled)
        assertEquals(2, result.constraints.single().numerator)
        assertEquals(4, result.constraints.single().denominator)
    }

    @Test fun `over capacity division rejects the entire command without modifying its source`() {
        val initial = lineScene().copy(points = lineScene().points + (2 until SceneValidator.MAX_POINTS - 1).map {
            GeometryPoint("extra$it", it.toDouble(), 1.0)
        })
        assertEquals(SceneValidator.MAX_POINTS - 1, initial.points.size)
        assertThrows(IllegalArgumentException::class.java) { ConstructionEdits.divideSegment(initial, "AB", 3) }
        assertEquals(SceneValidator.MAX_POINTS - 1, initial.points.size)
        assertTrue(initial.constraints.isEmpty())
    }

    @Test fun `multi equal uses only missing independent links and reuses reversed paused pair`() {
        val initial = ConstructionScene(
            points = (0..4).map { GeometryPoint("p$it", it.toDouble(), 0.0) },
            segments = (0..3).map { GeometrySegment("s$it", "p$it", "p${it + 1}") },
            constraints = listOf(
                GeometryConstraint("existing", ConstraintType.EQUAL_LENGTH, listOf("s1", "s0")),
                GeometryConstraint("paused", ConstraintType.EQUAL_LENGTH, listOf("s2", "s0"), enabled = false),
            ),
        )
        val result = ConstructionEdits.multiEqualLength(initial, initial.segments.map { it.id })
        assertEquals(3, result.constraints.size)
        assertEquals(setOf("existing", "paused"), result.constraints.take(2).map { it.id }.toSet())
        assertTrue(result.constraints.all { it.enabled })
        assertEquals(result, ConstructionEdits.multiEqualLength(result, initial.segments.map { it.id }.reversed()))
        solved(result)
    }

    @Test fun `presets are ordinary free primitives with only minimal shape relations`() {
        for (preset in ConstructionPreset.entries) {
            val scene = ConstructionEdits.createPreset(ConstructionScene(), preset, 7.0, 9.0,
                colorArgb = 0xFF345678.toInt(), lineStyle = GeometryLineStyle.DOTTED)
            assertTrue(scene.points.all { it.colorArgb == 0xFF345678.toInt() })
            assertTrue(scene.segments.all { it.lineStyle == GeometryLineStyle.DOTTED })
            assertTrue(scene.constraints.none { it.type in setOf(ConstraintType.FIXED_POINT, ConstraintType.LENGTH, ConstraintType.ANGLE, ConstraintType.INTERIOR_ANGLE) })
            assertEquals(when (preset) {
                ConstructionPreset.SQUARE -> 4
                ConstructionPreset.RECTANGLE -> 3
                ConstructionPreset.EQUILATERAL_TRIANGLE -> 2
                else -> 1
            }, scene.constraints.size)
            val result = solver.solve(scene)
            assertTrue("$preset: ${result.message}", result.success)
            assertTrue("$preset must remain movable", result.degreesOfFreedom >= 3)
        }
    }

    @Test fun `reversed interior arms and exchanged equal angles reuse the original relation identity`() {
        val scene = ConstructionScene(points = listOf(GeometryPoint("A", 1.0, 0.0), GeometryPoint("B", 0.0, 0.0),
            GeometryPoint("C", 0.0, 1.0), GeometryPoint("P", 1.0, 1.0)))
        val angle = GeometryConstraint("angle", ConstraintType.INTERIOR_ANGLE, listOf("A", "B", "C"), value = 90.0)
        val withAngle = ConstructionEdits.addConstraint(scene, angle)
        val edited = ConstructionEdits.addConstraint(withAngle,
            angle.copy(id = "request", entityIds = listOf("C", "B", "A"), value = 60.0))
        assertEquals(1, edited.constraints.size)
        assertEquals("angle", edited.constraints.single().id)
        assertEquals(60.0, edited.constraints.single().value!!, 0.0)
        val equal = GeometryConstraint("equal", ConstraintType.EQUAL_ANGLE, listOf("A", "B", "P", "P", "B", "C"), enabled = false)
        val initial = scene.copy(constraints = listOf(equal))
        val repeated = ConstructionEdits.addConstraint(initial,
            equal.copy(id = "request", entityIds = listOf("C", "B", "P", "P", "B", "A"), enabled = true))
        assertEquals(1, repeated.constraints.size)
        assertEquals(equal.id, repeated.constraints.single().id)
        assertTrue(repeated.constraints.single().enabled)
    }

    @Test fun `square and midpoint textbook construction follows one changing side length`() {
        var scene = ConstructionEdits.createPreset(ConstructionScene(), ConstructionPreset.SQUARE, 3.0, 3.0)
        val first = scene.points.first()
        val side = scene.segments.first()
        scene = ConstructionEdits.addConstraint(scene, GeometryConstraint("origin", ConstraintType.FIXED_POINT,
            listOf(first.id), targetX = first.x, targetY = first.y))
        scene = ConstructionEdits.addConstraint(scene, GeometryConstraint("horizontal", ConstraintType.HORIZONTAL, listOf(side.id)))
        scene = ConstructionEdits.addConstraint(scene, GeometryConstraint("side", ConstraintType.LENGTH, listOf(side.id), value = 6.0))
        scene = ConstructionEdits.divideSegment(scene, side.id, 2)
        val midpointId = scene.points.last().id
        val ready = solved(scene)
        val changed = ConstructionEdits.addConstraint(ready, GeometryConstraint("request", ConstraintType.LENGTH, listOf(side.id), value = 10.0))
        val result = solved(changed)
        assertEquals(ready.points.map { it.id }, result.points.map { it.id })
        for (segment in result.segments) assertEquals(10.0, length(result, segment), 1e-4)
        val a = result.point(side.startPointId)!!; val b = result.point(side.endPointId)!!
        val midpoint = result.point(midpointId)!!
        assertEquals((a.x + b.x) / 2, midpoint.x, 1e-4)
        assertEquals((a.y + b.y) / 2, midpoint.y, 1e-4)
        assertEquals(4, result.segments.size)
        assertEquals("side", result.constraints.single { it.type == ConstraintType.LENGTH }.id)
    }

    @Test fun `two equal interior angles form a bisector and follow the opposite side intersection`() {
        val scene = ConstructionScene(
            points = listOf(GeometryPoint("A", 0.0, 6.0), GeometryPoint("B", 0.0, 0.0),
                GeometryPoint("C", 6.0, 0.0), GeometryPoint("P", 3.0, 3.0)),
            segments = listOf(GeometrySegment("AC", "A", "C"), GeometrySegment("BP", "B", "P")),
            constraints = listOf(
                GeometryConstraint("b", ConstraintType.FIXED_POINT, listOf("B"), targetX = 0.0, targetY = 0.0),
                GeometryConstraint("c", ConstraintType.FIXED_POINT, listOf("C"), targetX = 6.0, targetY = 0.0),
                GeometryConstraint("on", ConstraintType.POINT_ON_SEGMENT, listOf("P", "AC")),
                GeometryConstraint("equal", ConstraintType.EQUAL_ANGLE, listOf("A", "B", "P", "P", "B", "C")),
            ),
        )
        val moved = solver.solve(solved(scene), DragTarget("A", 0.0, 8.0))
        assertTrue(moved.message, moved.success)
        assertFalse(moved.dragLimited)
        val p = moved.scene.point("P")!!
        assertEquals(24.0 / 7, p.x, 1e-4)
        assertEquals(24.0 / 7, p.y, 1e-4)
        val arcs = ConstructionMeasurementGeometry.equalAngleLayouts(moved.scene, moved.scene.constraints.last())
        assertEquals(2, arcs.size)
        assertEquals(arcs[0].value, arcs[1].value, 1e-4)
        assertEquals(45.0, arcs[0].value, 1e-4)
    }

    private fun lineScene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0, "A"), GeometryPoint("B", 12.0, 0.0, "B")),
        segments = listOf(GeometrySegment("AB", "A", "B", colorArgb = 0xFF345678.toInt(), lineStyle = GeometryLineStyle.DASHED)),
    )
    private fun solved(scene: ConstructionScene): ConstructionScene = solver.solve(scene).let {
        assertTrue(it.message, it.success)
        assertTrue(it.maxResidual < 1e-5)
        it.scene
    }
    private fun length(scene: ConstructionScene, segment: GeometrySegment): Double {
        val a = scene.point(segment.startPointId)!!; val b = scene.point(segment.endPointId)!!
        return hypot(b.x - a.x, b.y - a.y)
    }
}
