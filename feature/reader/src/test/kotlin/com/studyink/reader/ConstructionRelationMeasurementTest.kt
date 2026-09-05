package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import org.junit.Assert.*
import org.junit.Test

class ConstructionRelationMeasurementTest {
    @Test fun `end distance caption points to the chosen original endpoint`() {
        val scene = scene()
        val fromStart = GeometryConstraint("d", ConstraintType.POINT_DISTANCE, listOf("P", "AB"), value = 4.0)
        val start = ConstructionMeasurementGeometry.constraintLayout(scene, fromStart)!!
        assertEquals(ConstructionVector(0.0, 0.0), start.first)
        assertEquals(ConstructionVector(4.0, 0.0), start.second)
        assertEquals("A→P ", start.captionPrefix)
        val fromEnd = fromStart.copy(value = 8.0, fromEnd = true)
        val end = ConstructionMeasurementGeometry.constraintLayout(scene, fromEnd)!!
        assertEquals(ConstructionVector(12.0, 0.0), end.first)
        assertEquals(ConstructionVector(4.0, 0.0), end.second)
        assertEquals("B→P ", end.captionPrefix)
        assertEquals(8.0, end.value, 0.0)
    }

    @Test fun `interior condition uses three point rays without the directed line supplement`() {
        val condition = GeometryConstraint("a", ConstraintType.INTERIOR_ANGLE, listOf("C", "A", "B"), value = 90.0)
        val layout = ConstructionMeasurementGeometry.constraintLayout(scene(), condition)!!
        assertEquals(ConstructionVector(0.0, 0.0), layout.vertex)
        assertEquals(90.0, layout.value, 0.0)
        assertTrue(layout.directionSegments.isEmpty())
        assertEquals(Math.PI / 2, kotlin.math.abs(layout.angleSweep), 1e-12)
    }

    @Test fun `driving interior and endpoint dimension take over corresponding reference offsets only`() {
        val angle = GeometryConstraint("a", ConstraintType.INTERIOR_ANGLE, listOf("C", "A", "B"), value = 90.0)
        val reference = GeometryMeasurement("ref", MeasurementType.ANGLE, listOf("B", "A", "C"), 1.2, 2.3)
        val distance = GeometryConstraint("d", ConstraintType.POINT_DISTANCE, listOf("P", "AB"), value = 4.0)
        val distanceRef = GeometryMeasurement("dist-ref", MeasurementType.DISTANCE, listOf("P", "A"), -1.1, 2.0)
        for ((constraint, measurement) in listOf(angle to reference, distance to distanceRef)) {
            val scene = scene().copy(constraints = listOf(constraint), measurements = listOf(measurement))
            assertTrue(ConstructionMeasurementGeometry.matchesConstraint(scene, measurement, constraint))
            assertEquals(ConstructionMeasurementGeometry.layout(scene, measurement)!!.label,
                ConstructionMeasurementGeometry.constraintLayout(scene, constraint)!!.label)
        }
        assertFalse(ConstructionMeasurementGeometry.matchesConstraint(scene(),
            reference.copy(entityIds = listOf("A", "B", "C")), angle))
        assertFalse(ConstructionMeasurementGeometry.matchesConstraint(scene(), distanceRef, distance.copy(fromEnd = true)))
    }

    private fun scene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0, "A"), GeometryPoint("B", 12.0, 0.0, "B"),
            GeometryPoint("P", 4.0, 0.0, "P"), GeometryPoint("C", 0.0, 6.0, "C")),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("CA", "C", "A")),
    )
}
