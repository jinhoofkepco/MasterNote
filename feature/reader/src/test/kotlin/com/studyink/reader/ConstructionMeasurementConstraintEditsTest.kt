package com.studyink.reader

import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class ConstructionMeasurementConstraintEditsTest {
    @Test fun `unjoined distance creates exact point condition without a segment or moved label`() {
        val original = scene()
        val fixed = ConstructionEdits.constrainMeasurement(original, "ab")
        assertEquals(ConstraintType.DISTANCE_POINTS, fixed.constraints.single().type)
        assertEquals(sqrt(2.0), fixed.constraints.single().value!!, 0.0)
        assertEquals(listOf("A", "B"), fixed.constraints.single().entityIds)
        assertEquals(original.points, fixed.points)
        assertTrue(fixed.segments.isEmpty())
        assertEquals(original.measurements, fixed.measurements)
        assertEquals(fixed, ConstructionEdits.constrainMeasurement(fixed, "ab"))
    }

    @Test fun `existing reversed segment uses LENGTH and preserves its drawing style`() {
        val segment = GeometrySegment("BA", "B", "A", colorArgb = 0xff123456.toInt())
        val original = scene().copy(segments = listOf(segment))
        val fixed = ConstructionEdits.constrainMeasurement(original, "ab", 8.0)
        assertEquals(ConstraintType.LENGTH, fixed.constraints.single().type)
        assertEquals(listOf("BA"), fixed.constraints.single().entityIds)
        assertEquals(8.0, fixed.constraints.single().value!!, 0.0)
        assertEquals(original.segments, fixed.segments)
        assertEquals(original.measurements, fixed.measurements)
    }

    @Test fun `point distance remains one condition after a visible segment is added later`() {
        val fixed = ConstructionEdits.constrainMeasurement(scene(), "ab")
        val withSegment = fixed.copy(segments = listOf(GeometrySegment("AB", "A", "B")))
        val updated = ConstructionEdits.constrainMeasurement(withSegment, "ab", 3.0)
        assertEquals(1, updated.constraints.size)
        assertEquals(fixed.constraints.single().id, updated.constraints.single().id)
        assertEquals(ConstraintType.DISTANCE_POINTS, updated.constraints.single().type)
        assertEquals(3.0, updated.constraints.single().value!!, 0.0)
    }

    @Test fun `updating a paused driving condition keeps id and paused state`() {
        val condition = GeometryConstraint("paused", ConstraintType.DISTANCE_POINTS, listOf("B", "A"), 2.0, enabled = false)
        val original = scene().copy(constraints = listOf(condition))
        val updated = ConstructionEdits.constrainMeasurement(original, "ab", 4.0)
        assertEquals(condition.copy(value = 4.0), updated.constraints.single())
        assertEquals(original.measurements, updated.measurements)
    }

    @Test fun `distance from endpoint keeps its collinearity extension and pause options`() {
        val original = scene().copy(
            points = listOf(GeometryPoint("A", 0.0, 0.0), GeometryPoint("B", 0.0, 1.0), GeometryPoint("C", 0.0, 2.0), GeometryPoint("D", 2.0, 2.0)),
            segments = listOf(GeometrySegment("CA", "C", "A")),
            constraints = listOf(GeometryConstraint("position", ConstraintType.POINT_DISTANCE, listOf("B", "CA"),
                1.0, enabled = false, fromEnd = true, allowExtension = true)),
        )
        val updated = ConstructionEdits.constrainMeasurement(original, "ab", 1.5)
        assertEquals(original.constraints.single().copy(value = 1.5), updated.constraints.single())
    }

    @Test fun `radius and interior angle convert to their existing exact driving types`() {
        val original = scene().copy(circles = listOf(GeometryCircle("circle", "A", 2.3456789)),
            measurements = scene().measurements + GeometryMeasurement("radius", MeasurementType.RADIUS, listOf("circle"), 1.2, -1.3))
        val radius = ConstructionEdits.constrainMeasurement(original, "radius")
        assertEquals(ConstraintType.RADIUS, radius.constraints.single().type)
        assertEquals(2.3456789, radius.constraints.single().value!!, 0.0)
        val angle = ConstructionEdits.constrainMeasurement(original, "angle1")
        assertEquals(ConstraintType.INTERIOR_ANGLE, angle.constraints.single().type)
        assertEquals(listOf("A", "B", "C"), angle.constraints.single().entityIds)
        assertEquals(ConstructionMeasurementGeometry.layout(original, original.measurements.first { it.id == "angle1" })!!.value,
            angle.constraints.single().value!!, 0.0)
        assertEquals(original.measurements, angle.measurements)
    }

    @Test fun `distance measurements share a point without requiring drawn segments`() {
        val original = scene()
        val equal = ConstructionEdits.equalMeasurements(original, "ab", "ac")
        assertEquals(ConstraintType.EQUAL_DISTANCE_POINTS, equal.constraints.single().type)
        assertEquals(listOf("A", "B", "A", "C"), equal.constraints.single().entityIds)
        assertTrue(equal.segments.isEmpty())
        assertEquals(original.measurements, equal.measurements)
        assertEquals(equal, ConstructionEdits.equalMeasurements(equal, "ac", "ab"))
    }

    @Test fun `two measured segments use existing equal length relation and preserve pause`() {
        val original = scene().copy(segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("CA", "C", "A")))
        val equal = ConstructionEdits.equalMeasurements(original, "ab", "ac")
        assertEquals(ConstraintType.EQUAL_LENGTH, equal.constraints.single().type)
        val paused = equal.copy(constraints = equal.constraints.map { it.copy(enabled = false) })
        assertEquals(paused, ConstructionEdits.equalMeasurements(paused, "ac", "ab"))
        assertEquals(paused.constraints.single(), ConstructionEdits.matchingEqualityConstraint(paused, "ac", "ab"))
        val noSegments = paused.copy(segments = emptyList(), constraints = listOf(
            GeometryConstraint("point-equality", ConstraintType.EQUAL_DISTANCE_POINTS, listOf("B", "A", "C", "A"), enabled = false)))
        val addedSegments = noSegments.copy(segments = original.segments)
        assertEquals(addedSegments, ConstructionEdits.equalMeasurements(addedSegments, "ab", "ac"))
        assertEquals(noSegments.constraints.single(), ConstructionEdits.matchingEqualityConstraint(addedSegments, "ab", "ac"))
    }

    @Test fun `angle measurements become equality without fixed angle values or duplicate reversed pairs`() {
        val original = scene()
        val equal = ConstructionEdits.equalMeasurements(original, "angle1", "angle2")
        assertEquals(ConstraintType.EQUAL_ANGLE, equal.constraints.single().type)
        assertNull(equal.constraints.single().value)
        assertEquals(original.measurements, equal.measurements)
        assertEquals(equal, ConstructionEdits.equalMeasurements(equal, "angle2", "angle1"))
        assertEquals(equal.constraints.single(), ConstructionEdits.matchingEqualityConstraint(equal, "angle2", "angle1"))
    }

    @Test fun `same measurement mixed types unsupported area and invalid numeric inputs fail without mutation`() {
        val original = scene()
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ConstructionEdits.constrainMeasurement(original, "ab", value) }
        }
        assertThrows(IllegalArgumentException::class.java) { ConstructionEdits.constrainMeasurement(original, "missing") }
        assertThrows(IllegalArgumentException::class.java) { ConstructionEdits.constrainMeasurement(original, "area") }
        assertThrows(IllegalArgumentException::class.java) { ConstructionEdits.equalMeasurements(original, "ab", "ab") }
        assertThrows(IllegalArgumentException::class.java) { ConstructionEdits.equalMeasurements(original, "ab", "angle1") }
        assertThrows(IllegalArgumentException::class.java) { ConstructionEdits.equalMeasurements(original, "area", "area") }
        assertTrue(original.constraints.isEmpty())
        assertTrue(original.segments.isEmpty())
    }

    @Test fun `new condition labels are available and equality lookup never mistakes mixed or same measurements`() {
        assertEquals("두 점 거리", ConstraintType.DISTANCE_POINTS.koreanName())
        assertEquals("같은 거리", ConstraintType.EQUAL_DISTANCE_POINTS.koreanName())
        assertTrue(ConstraintType.entries.all { it.koreanName().isNotBlank() })
        val equal = ConstructionEdits.equalMeasurements(scene(), "ab", "ac")
        assertNull(ConstructionEdits.matchingEqualityConstraint(equal, "ab", "ab"))
        assertNull(ConstructionEdits.matchingEqualityConstraint(equal, "ab", "angle1"))
        assertNull(ConstructionEdits.matchingEqualityConstraint(equal, "missing", "ac"))
    }

    private fun scene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0), GeometryPoint("B", 1.0, 1.0), GeometryPoint("C", 0.0, 2.0), GeometryPoint("D", 2.0, 2.0)),
        measurements = listOf(
            GeometryMeasurement("ab", MeasurementType.DISTANCE, listOf("A", "B"), 1.25, -2.75),
            GeometryMeasurement("ac", MeasurementType.DISTANCE, listOf("A", "C"), -1.1, 2.2),
            GeometryMeasurement("angle1", MeasurementType.ANGLE, listOf("A", "B", "C"), 2.25, 3.25),
            GeometryMeasurement("angle2", MeasurementType.ANGLE, listOf("B", "C", "D"), -2.25, 3.25),
            GeometryMeasurement("area", MeasurementType.AREA, listOf("A", "B", "C")),
        ),
    )
}
