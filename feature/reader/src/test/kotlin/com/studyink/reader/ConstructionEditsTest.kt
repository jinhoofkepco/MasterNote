package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintSolver
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.DragTarget
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.SceneValidator
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ConstructionEditsTest {
    private val solver = ConstraintSolver()

    @Test fun `linked bars example keeps radius coincidence and length while dragging its end`() {
        val initial = ConstructionEdits.linkedBars()
        val ready = solved(initial)
        val o = point(ready, "O")
        val r = point(ready, "R")
        val moved = solver.solve(ready, DragTarget(r.id, 10.0, 10.0))
        assertTrue(moved.message, moved.success)
        assertFalse(moved.dragLimited)
        val p = point(moved.scene, "P")
        val q = point(moved.scene, "Q")
        assertEquals(0.0, point(moved.scene, "O").x, EPSILON)
        assertEquals(0.0, point(moved.scene, "O").y, EPSILON)
        assertEquals(10.0, distance(o, p), EPSILON)
        assertEquals(10.0, moved.scene.circles.single().radius, EPSILON)
        assertEquals(0.0, distance(p, q), EPSILON)
        assertEquals(6.0, distance(q, point(moved.scene, "R")), EPSILON)
        assertNotEquals("Coincidence must not merge the editable identities", p.id, q.id)
        assertEquals(initial.points.map { it.id }, moved.scene.points.map { it.id })
        assertTrue(moved.maxResidual < 1e-5)
    }

    @Test fun `trapezoid reproduces the given problem and changing only height produces four cm`() {
        val initial = solved(ConstructionEdits.trapezoid())
        assertEquals(3.8, distance(point(initial, "ㅁ"), point(initial, "ㅂ")), EPSILON)
        val height = initial.constraints.single { it.type == ConstraintType.DISTANCE_POINT_LINE }
        val edited = ConstructionEdits.addConstraint(initial, height.copy(id = "new-height-request", value = 14.0))
        assertEquals(initial.constraints.size, edited.constraints.size)
        assertEquals(height.id, edited.constraints.single { it.type == ConstraintType.DISTANCE_POINT_LINE }.id)
        val result = solved(edited)
        assertEquals(4.0, distance(point(result, "ㅁ"), point(result, "ㅂ")), EPSILON)
        assertEquals(15.0, distance(point(result, "ㄱ"), point(result, "ㄴ")), EPSILON)
        assertEquals(15.0, distance(point(result, "ㄴ"), point(result, "ㄷ")), EPSILON)
        assertEquals(6.0, distance(point(result, "ㄱ"), point(result, "ㄹ")), EPSILON)
        assertEquals(initial.points.map { it.id }, result.points.map { it.id })
    }

    @Test fun `trapezoid height cannot exceed its fixed fifteen cm side`() {
        val initial = ConstructionEdits.trapezoid()
        val height = initial.constraints.single { it.type == ConstraintType.DISTANCE_POINT_LINE }
        val impossible = ConstructionEdits.addConstraint(initial, height.copy(id = "height-request", value = 16.0))
        val result = solver.solve(impossible)
        assertFalse("The editor must not accept stretched givens as a solution", result.success)
        assertEquals(13.3, initial.constraints.single { it.id == height.id }.value!!, 0.0)
        assertEquals(3.8, distance(point(initial, "ㅁ"), point(initial, "ㅂ")), EPSILON)
    }

    @Test fun `deleting the center removes dependent entities and constraints but retains unrelated bar`() {
        val initial = ConstructionEdits.linkedBars()
        val center = point(initial, "O")
        val qr = initial.segments.single { it.startPointId == point(initial, "Q").id }
        val remaining = ConstructionEdits.remove(initial, setOf(center.id))
        assertEquals(setOf("P", "Q", "R"), remaining.points.map { it.label }.toSet())
        assertEquals(listOf(qr), remaining.segments)
        assertTrue(remaining.circles.isEmpty())
        assertEquals(setOf(ConstraintType.COINCIDENT, ConstraintType.LENGTH), remaining.constraints.map { it.type }.toSet())
        assertTrue(SceneValidator.validate(remaining).toString(), SceneValidator.validate(remaining).isEmpty())
        assertEquals(remaining, solved(remaining))
        assertEquals(4, initial.points.size)
        assertEquals(1, initial.circles.size)
    }

    @Test fun `deleting a circle releases its relations without deleting its center or separate lines`() {
        val initial = ConstructionEdits.linkedBars()
        val remaining = ConstructionEdits.remove(initial, setOf(initial.circles.single().id))
        assertEquals(initial.points, remaining.points)
        assertEquals(initial.segments, remaining.segments)
        assertTrue(remaining.circles.isEmpty())
        assertFalse(remaining.constraints.any { it.type == ConstraintType.RADIUS || it.type == ConstraintType.POINT_ON_CIRCLE })
        assertTrue(SceneValidator.validate(remaining).isEmpty())
        solved(remaining)
    }

    @Test fun `removing a coincidence leaves two original point identities that can separate again`() {
        val initial = solved(ConstructionEdits.linkedBars())
        val p = point(initial, "P")
        val q = point(initial, "Q")
        // This is the same explicit relation deletion issued by the condition-list UI.
        val released = initial.copy(constraints = initial.constraints.filterNot { it.type == ConstraintType.COINCIDENT })
        val moved = solver.solve(released, DragTarget(q.id, 8.0, 8.0))
        assertTrue(moved.message, moved.success)
        assertEquals(initial.points.map { it.id }, moved.scene.points.map { it.id })
        assertEquals(p.id, point(moved.scene, "P").id)
        assertEquals(q.id, point(moved.scene, "Q").id)
        assertTrue(distance(point(moved.scene, "P"), point(moved.scene, "Q")) > 1.0)
        assertEquals(10.0, distance(point(moved.scene, "O"), point(moved.scene, "P")), EPSILON)
        assertEquals(6.0, distance(point(moved.scene, "Q"), point(moved.scene, "R")), EPSILON)
    }

    @Test fun `numeric dimension entry replaces the existing driving value and re-enables it`() {
        val initial = ConstructionEdits.linkedBars()
        val length = initial.constraints.single { it.type == ConstraintType.LENGTH }
        val disabled = initial.copy(constraints = initial.constraints.map {
            if (it.id == length.id) it.copy(enabled = false) else it
        })
        val edited = ConstructionEdits.addConstraint(disabled, length.copy(id = "new-length-request", value = 7.0))
        val value = edited.constraints.single { it.type == ConstraintType.LENGTH }
        assertEquals(length.id, value.id)
        assertTrue(value.enabled)
        assertEquals(initial.constraints.size, edited.constraints.size)
        assertEquals(7.0, value.value!!, 0.0)
        val result = solved(edited)
        assertEquals(7.0, distance(point(result, "Q"), point(result, "R")), EPSILON)
        assertEquals(10.0, result.circles.single().radius, EPSILON)
    }

    @Test fun `radius angle and height edits retain their existing condition identity`() {
        val bars = ConstructionEdits.linkedBars()
        val radius = bars.constraints.single { it.type == ConstraintType.RADIUS }
        val angle = GeometryConstraint("angle", ConstraintType.ANGLE, bars.segments.map { it.id }, value = 40.0)
        val heightScene = ConstructionEdits.trapezoid()
        val height = heightScene.constraints.single { it.type == ConstraintType.DISTANCE_POINT_LINE }
        val cases = listOf(
            Triple(bars, radius, 12.0),
            Triple(ConstructionEdits.addConstraint(bars, angle), angle, 60.0),
            Triple(heightScene, height, 14.0),
        )
        cases.forEach { (scene, original, value) ->
            val result = ConstructionEdits.addConstraint(scene, original.copy(id = "request-${original.id}", value = value))
            assertEquals(scene.constraints.size, result.constraints.size)
            assertEquals(value, result.constraints.single { it.id == original.id }.value!!, 0.0)
            assertFalse(result.constraints.any { it.id == "request-${original.id}" })
            solved(result)
        }
    }

    @Test fun `explicit existing anchor reuses its identity rather than its supplied pointer coordinates`() {
        val base = ConstructionScene(points = listOf(GeometryPoint("origin", 0.0, 0.0, "O")))
        val segmentScene = ConstructionEdits.addSegment(
            base, ConstructionAnchor(500.0, 500.0, "origin"), ConstructionAnchor(10.0, 0.0),
        )
        assertEquals(2, segmentScene.points.size)
        assertEquals("origin", segmentScene.segments.single().startPointId)
        assertEquals(base.points.single(), segmentScene.point("origin"))
        val circleScene = ConstructionEdits.addCircle(segmentScene, ConstructionAnchor(-500.0, -500.0, "origin"), 10.0)
        assertEquals(2, circleScene.points.size)
        assertEquals("origin", circleScene.circles.single().centerPointId)
        assertTrue(SceneValidator.validate(circleScene).isEmpty())
    }

    @Test fun `same coordinates do not implicitly connect a separately created segment`() {
        val base = ConstructionScene(points = listOf(GeometryPoint("origin", 0.0, 0.0, "O")))
        val scene = ConstructionEdits.addSegment(base, ConstructionAnchor(0.0, 0.0), ConstructionAnchor(10.0, 0.0))
        assertEquals(3, scene.points.size)
        val startId = scene.segments.single().startPointId
        assertNotEquals("origin", startId)
        assertEquals(0.0, distance(scene.point("origin")!!, scene.point(startId)!!), 0.0)
        assertTrue(scene.constraints.isEmpty())
        val withRelation = ConstructionEdits.addConstraint(scene,
            GeometryConstraint("join", ConstraintType.COINCIDENT, listOf("origin", startId)))
        assertEquals(1, withRelation.constraints.size)
        assertEquals(3, solved(withRelation).points.size)
    }

    @Test fun `same explicit point cannot form both ends of a line and rejected edit leaves source intact`() {
        val base = ConstructionScene(points = listOf(GeometryPoint("origin", 0.0, 0.0, "O")))
        assertThrows(IllegalArgumentException::class.java) {
            ConstructionEdits.addSegment(base, ConstructionAnchor(0.0, 0.0, "origin"), ConstructionAnchor(10.0, 0.0, "origin"))
        }
        assertEquals(1, base.points.size)
        assertTrue(base.segments.isEmpty())
    }

    @Test fun `labels remain independent from geometry identity and relations`() {
        assertEquals("A", ConstructionEdits.pointLabel(0))
        assertEquals("Z", ConstructionEdits.pointLabel(25))
        assertEquals("A1", ConstructionEdits.pointLabel(26))
        assertEquals("B1", ConstructionEdits.pointLabel(27))
        val initial = ConstructionEdits.linkedBars()
        val p = point(initial, "P")
        val renamed = initial.copy(points = initial.points.map { if (it.id == p.id) it.copy(label = "ㄱ") else it })
        val result = solved(renamed)
        assertEquals(p.id, point(result, "ㄱ").id)
        assertEquals(initial.constraints, result.constraints)
        assertEquals(10.0, distance(point(result, "O"), point(result, "ㄱ")), EPSILON)
    }

    @Test fun `adding a point after deleting a middle label does not duplicate remaining labels`() {
        val a = ConstructionEdits.addPoint(ConstructionScene(), 0.0, 0.0)
        val b = ConstructionEdits.addPoint(a, 2.0, 0.0)
        val c = ConstructionEdits.addPoint(b, 4.0, 0.0)
        val removed = ConstructionEdits.remove(c, setOf(point(c, "B").id))
        val result = ConstructionEdits.addPoint(removed, 3.0, 2.0)
        assertEquals(listOf("A", "C"), removed.points.map { it.label })
        assertEquals(3, result.points.map { it.label }.distinct().size)
        assertEquals(point(c, "A").id, point(result, "A").id)
        assertEquals(point(c, "C").id, point(result, "C").id)
    }

    @Test fun `fixing a moved point again uses its new position and retains the relation identity`() {
        val initial = ConstructionScene(points = listOf(GeometryPoint("p", 0.0, 0.0, "P")))
        val fixed = ConstructionEdits.addConstraint(initial,
            GeometryConstraint("fixed", ConstraintType.FIXED_POINT, listOf("p"), targetX = 0.0, targetY = 0.0))
        val released = fixed.copy(constraints = fixed.constraints.map { it.copy(enabled = false) })
        val moved = solver.solve(released, DragTarget("p", 3.0, 4.0))
        assertTrue(moved.message, moved.success)
        val point = moved.scene.point("p")!!
        val refixed = ConstructionEdits.addConstraint(moved.scene,
            GeometryConstraint("fix-again", ConstraintType.FIXED_POINT, listOf("p"), targetX = point.x, targetY = point.y))
        assertEquals("fixed", refixed.constraints.single().id)
        assertEquals(1, refixed.constraints.size)
        val result = solved(refixed)
        assertEquals(3.0, result.point("p")!!.x, EPSILON)
        assertEquals(4.0, result.point("p")!!.y, EPSILON)
    }

    private fun solved(scene: ConstructionScene): ConstructionScene {
        val result = solver.solve(scene)
        assertTrue(result.message, result.success)
        assertTrue("Hard equations must remain within tolerance", result.maxResidual < 1e-5)
        return result.scene
    }

    private fun point(scene: ConstructionScene, label: String) = scene.points.single { it.label == label }
    private fun distance(a: GeometryPoint, b: GeometryPoint) = hypot(a.x - b.x, a.y - b.y)

    companion object { private const val EPSILON = 1e-4 }
}
