package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class ConstructionConstraintGeometryTest {
    @Test fun `incoming segment uses its original direction not the supplementary interior angle`() {
        val scene = connectedScene()
        val directionAngle = Math.toDegrees(atan2(4.0, 3.0))
        val condition = GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "BC"), value = directionAngle)
        val layout = ConstructionMeasurementGeometry.constraintLayout(scene, condition)!!
        val interior = ConstructionMeasurementGeometry.layout(scene,
            GeometryMeasurement("interior", MeasurementType.ANGLE, listOf("A", "B", "C")))!!

        assertEquals(ConstructionVector(0.0, 0.0), layout.vertex)
        assertEquals(0.0, layout.angleStart, 1e-10)
        assertEquals(directionAngle, Math.toDegrees(abs(layout.angleSweep)), 1e-10)
        assertEquals(180.0 - directionAngle, interior.value, 1e-10)
        assertNotEquals(interior.value, layout.value, 1e-3)
        assertEquals(listOf(ConstructionVector(-5.0, 0.0) to ConstructionVector(0.0, 0.0),
            ConstructionVector(0.0, 0.0) to ConstructionVector(3.0, 4.0)), layout.directionSegments)
    }

    @Test fun `reversing one segment visibly switches to its supplementary direction angle`() {
        val scene = connectedScene().copy(segments = listOf(GeometrySegment("AB", "B", "A"), GeometrySegment("BC", "B", "C")))
        val expected = 180.0 - Math.toDegrees(atan2(4.0, 3.0))
        val condition = GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "BC"), value = expected)
        val layout = ConstructionMeasurementGeometry.constraintLayout(scene, condition)!!
        assertEquals(Math.PI, layout.angleStart, 1e-10)
        assertEquals(expected, Math.toDegrees(abs(layout.angleSweep)), 1e-10)
        assertEquals(expected, layout.value, 1e-10)
    }

    @Test fun `disconnected nearly parallel angle stays near the drawing not a remote line intersection`() {
        val scene = ConstructionScene(
            points = listOf(GeometryPoint("A", 0.0, 0.0), GeometryPoint("B", 10.0, 0.0),
                GeometryPoint("C", 100.0, 100.0), GeometryPoint("D", 110.0, 100.0000001)),
            segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("CD", "C", "D")),
        )
        val layout = ConstructionMeasurementGeometry.constraintLayout(scene,
            GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "CD"), value = 0.0))!!
        assertEquals(ConstructionVector(0.0, 0.0), layout.vertex)
        assertTrue(layout.label.length() < 3.0)
        assertEquals(2, layout.directionSegments.size)
        assertTrue(layout.angleSweep.isFinite())
    }

    @Test fun `zero and straight directed angles have finite arc and label geometry`() {
        for (endX in listOf(-10.0, 10.0)) {
            val scene = ConstructionScene(
                points = listOf(GeometryPoint("A", 0.0, 0.0), GeometryPoint("B", 5.0, 0.0), GeometryPoint("C", endX, 0.0)),
                segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("AC", "A", "C")),
            )
            val expected = if (endX < 0) 180.0 else 0.0
            val layout = ConstructionMeasurementGeometry.constraintLayout(scene,
                GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "AC"), value = expected))!!
            assertEquals(expected, Math.toDegrees(abs(layout.angleSweep)), 1e-10)
            assertTrue(layout.arcRadius.isFinite() && layout.arcRadius > 0)
            assertTrue(layout.label.x.isFinite() && layout.label.y.isFinite())
        }
    }

    @Test fun `disabled length radius and angle keep saved target independently of measured value`() {
        val scene = connectedScene().copy(circles = listOf(GeometryCircle("circle", "B", 2.0)))
        val conditions = listOf(
            GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 10.0, enabled = false),
            GeometryConstraint("radius", ConstraintType.RADIUS, listOf("circle"), value = 3.0, enabled = false),
            GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "BC"), value = 45.0, enabled = false),
        )
        for (condition in conditions) {
            assertEquals(condition.value!!, ConstructionMeasurementGeometry.constraintLayout(scene, condition)!!.value, 1e-10)
        }
    }

    @Test fun `malformed and zero length angle targets have no fabricated arc`() {
        val constraint = GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "missing"), value = 60.0)
        assertNull(ConstructionMeasurementGeometry.constraintLayout(connectedScene(), constraint))
        val collapsed = connectedScene().copy(points = listOf(GeometryPoint("A", 0.0, 0.0),
            GeometryPoint("B", 0.0, 0.0), GeometryPoint("C", 3.0, 4.0)))
        assertNull(ConstructionMeasurementGeometry.constraintLayout(collapsed, constraint.copy(entityIds = listOf("AB", "BC"))))
    }

    private fun connectedScene() = ConstructionScene(
        points = listOf(GeometryPoint("A", -5.0, 0.0), GeometryPoint("B", 0.0, 0.0), GeometryPoint("C", 3.0, 4.0)),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("BC", "B", "C")),
    )
}
