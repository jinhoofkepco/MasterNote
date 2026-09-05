package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintSolver
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.DragTarget
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryLineStyle
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import org.junit.Assert.*
import org.junit.Test

class ConstructionLineStyleEditsTest {
    @Test fun `selected line style changes only selected strokes and never their geometry or conditions`() {
        val base = scene()
        val styled = ConstructionEdits.setLineStyle(base, setOf("a", "ab", "circle"), GeometryLineStyle.DASHED)
        assertEquals(base.points, styled.points)
        assertEquals(base.constraints, styled.constraints)
        assertEquals(base.measurements, styled.measurements)
        assertEquals(base.segment("ac"), styled.segment("ac"))
        assertEquals(base.segment("ab")!!.copy(lineStyle = GeometryLineStyle.DASHED), styled.segment("ab"))
        assertEquals(base.circle("circle")!!.copy(lineStyle = GeometryLineStyle.DASHED), styled.circle("circle"))
        assertEquals(base, ConstructionEdits.setLineStyle(base, setOf("a", "missing"), GeometryLineStyle.DOTTED))
        assertEquals(base, ConstructionEdits.setLineStyle(base, emptySet(), GeometryLineStyle.DOTTED))
    }

    @Test fun `new segments and circles inherit chosen style while reused endpoints stay unchanged`() {
        val base = scene()
        val withLine = ConstructionEdits.addSegment(base, ConstructionAnchor(0.0, 0.0, pointId = "a"),
            ConstructionAnchor(6.0, 4.0), BLUE, GeometryLineStyle.DOTTED)
        assertEquals(GeometryLineStyle.DOTTED, withLine.segments.last().lineStyle)
        assertEquals(BLUE, withLine.segments.last().colorArgb)
        assertEquals(base.point("a"), withLine.point("a"))
        assertEquals(base.segments, withLine.segments.dropLast(1))
        val withCircle = ConstructionEdits.addCircle(withLine, ConstructionAnchor(0.0, 0.0, pointId = "a"),
            4.0, BLUE, GeometryLineStyle.DASHED)
        assertEquals(GeometryLineStyle.DASHED, withCircle.circles.last().lineStyle)
        assertEquals(BLUE, withCircle.circles.last().colorArgb)
        assertEquals(base.point("a"), withCircle.point("a"))
        assertEquals(base.constraints, withCircle.constraints)
    }

    @Test fun `legacy creation defaults to solid and colors preserve existing line style`() {
        val line = ConstructionEdits.addSegment(ConstructionScene(), ConstructionAnchor(0.0, 0.0), ConstructionAnchor(3.0, 4.0))
        assertEquals(GeometryLineStyle.SOLID, line.segments.single().lineStyle)
        val circle = ConstructionEdits.addCircle(ConstructionScene(), ConstructionAnchor(0.0, 0.0), 3.0)
        assertEquals(GeometryLineStyle.SOLID, circle.circles.single().lineStyle)
        val styled = ConstructionEdits.setLineStyle(scene(), setOf("ab", "circle"), GeometryLineStyle.DOTTED)
        val recolored = ConstructionEdits.setColor(styled, setOf("ab", "circle"), BLUE)
        assertEquals(styled.segment("ab")!!.copy(colorArgb = BLUE), recolored.segment("ab"))
        assertEquals(styled.circle("circle")!!.copy(colorArgb = BLUE), recolored.circle("circle"))
        assertEquals(styled.points, recolored.points)
    }

    @Test fun `solver preserves styles while linked endpoints move`() {
        val styled = ConstructionEdits.setLineStyle(scene(), setOf("ab", "circle"), GeometryLineStyle.DASHED)
        val moved = ConstraintSolver().solve(styled, DragTarget("b", 0.0, 5.0))
        assertTrue(moved.message, moved.success)
        assertEquals(GeometryLineStyle.DASHED, moved.scene.segment("ab")!!.lineStyle)
        assertEquals(GeometryLineStyle.DASHED, moved.scene.circle("circle")!!.lineStyle)
        assertEquals(styled.constraints, moved.scene.constraints)
        assertEquals(styled.measurements, moved.scene.measurements)
        assertEquals(styled.segment("ab")!!.colorArgb, moved.scene.segment("ab")!!.colorArgb)
    }

    private fun scene() = ConstructionScene(
        points = listOf(GeometryPoint("a", 0.0, 0.0), GeometryPoint("b", 5.0, 0.0), GeometryPoint("c", 3.0, 4.0)),
        segments = listOf(GeometrySegment("ab", "a", "b", colorArgb = RED), GeometrySegment("ac", "a", "c")),
        circles = listOf(GeometryCircle("circle", "a", 3.0, colorArgb = RED)),
        constraints = listOf(
            GeometryConstraint("fix", ConstraintType.FIXED_POINT, listOf("a"), targetX = 0.0, targetY = 0.0),
            GeometryConstraint("length", ConstraintType.LENGTH, listOf("ab"), value = 5.0),
        ),
        measurements = listOf(GeometryMeasurement("measure", MeasurementType.DISTANCE, listOf("a", "b"))),
    )

    companion object {
        private const val RED = -0x36c6c7
        private const val BLUE = -0xe2913a
    }
}
