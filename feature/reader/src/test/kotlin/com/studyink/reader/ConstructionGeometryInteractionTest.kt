package com.studyink.reader

import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ConstructionGeometryInteractionTest {
    private fun crossing() = ConstructionScene(
        points = listOf(GeometryPoint("A", -5.0, 0.0), GeometryPoint("B", 5.0, 0.0),
            GeometryPoint("C", 0.0, -5.0), GeometryPoint("D", 0.0, 5.0)),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("CD", "C", "D")),
    )

    @Test fun `intersection snap returns both line incidences without splitting original lines`() {
        val scene = crossing()
        val snap = ConstructionSnapper.resolve(scene, .04, .08, .2, true)
        assertEquals(0.0, snap.x, 1e-12); assertEquals(0.0, snap.y, 1e-12)
        assertEquals(listOf("AB", "CD"), snap.lineIds)
        assertNull(snap.pointId)
        assertEquals(2, scene.segments.size)
        assertEquals("A", scene.segments[0].startPointId)
        assertEquals("B", scene.segments[0].endPointId)
    }

    @Test fun `existing point has priority over crossing inference`() {
        val scene = crossing().let { it.copy(points = it.points + GeometryPoint("intersection-point", 0.0, 0.0, "P")) }
        val snap = ConstructionSnapper.resolve(scene, .03, .02, .2, true)
        assertEquals("intersection-point", snap.pointId)
        assertTrue(snap.lineIds.isEmpty())
    }

    @Test fun `line snap returns exact projection instead of attaching constraint to raw pointer`() {
        val scene = ConstructionScene(points = listOf(GeometryPoint("A", 1.0, 1.0), GeometryPoint("B", 9.0, 5.0)),
            segments = listOf(GeometrySegment("line", "A", "B")))
        val snap = ConstructionSnapper.resolve(scene, 5.0, 3.1, .3, true)
        assertEquals(listOf("line"), snap.lineIds)
        assertEquals(0.0, (snap.x - 1.0) * 4 - (snap.y - 1.0) * 8, 1e-12)
        assertEquals(5.04, snap.x, 1e-12)
        assertEquals(3.02, snap.y, 1e-12)
    }

    @Test fun `overlapping parallel lines do not invent a unique intersection`() {
        val scene = ConstructionScene(points = listOf(GeometryPoint("A", -5.0, 0.0), GeometryPoint("B", 5.0, 0.0),
            GeometryPoint("C", -4.0, 0.0), GeometryPoint("D", 4.0, 0.0)),
            segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("CD", "C", "D")))
        val snap = ConstructionSnapper.resolve(scene, 0.0, .02, .2, true)
        assertEquals(1, snap.lineIds.size)
        assertEquals("직선 위", snap.snapLabel)
    }

    @Test fun `snapping off preserves the raw point without silently attaching it`() {
        val snap = ConstructionSnapper.resolve(crossing(), .03, .02, .2, false)
        assertEquals(.03, snap.x, 0.0); assertEquals(.02, snap.y, 0.0)
        assertNull(snap.pointId); assertTrue(snap.lineIds.isEmpty()); assertEquals("", snap.snapLabel)
    }

    @Test fun `no circle intersection or automatic direction condition is inferred`() {
        val scene = ConstructionScene(points = listOf(GeometryPoint("O", 0.0, 0.0)), circles = listOf(GeometryCircle("circle", "O", 10.0)))
        val snap = ConstructionSnapper.resolve(scene, 10.0, .02, .2, true)
        assertEquals(10.0, snap.x, 0.0); assertEquals(.02, snap.y, 0.0)
        assertTrue(snap.lineIds.isEmpty()); assertNull(snap.pointId)
    }

    @Test fun `moving a dimension label changes only its location and preserves measured length`() {
        val scene = ConstructionScene(points = listOf(GeometryPoint("A", 0.0, 0.0), GeometryPoint("B", 3.0, 4.0)))
        val measurement = GeometryMeasurement("m", MeasurementType.DISTANCE, listOf("A", "B"))
        val original = ConstructionMeasurementGeometry.layout(scene, measurement)!!
        val moved = ConstructionMeasurementGeometry.layout(scene, measurement.copy(offsetX = 7.0, offsetY = -3.0))!!
        assertEquals(5.0, original.value, 1e-12); assertEquals(5.0, moved.value, 1e-12)
        assertEquals(original.label.x + 7, moved.label.x, 1e-12)
        assertEquals(original.label.y - 3, moved.label.y, 1e-12)
        assertEquals(original.baseAnchor, moved.baseAnchor)
    }

    @Test fun `angle arc is based on the explicitly selected middle vertex`() {
        val scene = ConstructionScene(points = listOf(GeometryPoint("A", 7.0, 3.0), GeometryPoint("V", 2.0, 3.0), GeometryPoint("C", 2.0, 7.0)))
        val measurement = GeometryMeasurement("angle", MeasurementType.ANGLE, listOf("A", "V", "C"))
        val layout = ConstructionMeasurementGeometry.layout(scene, measurement)!!
        assertEquals(ConstructionVector(2.0, 3.0), layout.vertex)
        assertEquals(90.0, layout.value, 1e-12)
        assertEquals(0.0, layout.angleStart, 1e-12)
        assertEquals(Math.PI / 2, layout.angleSweep, 1e-12)
        val reversed = ConstructionMeasurementGeometry.layout(scene, measurement.copy(entityIds = listOf("C", "V", "A")))!!
        assertEquals(90.0, reversed.value, 1e-12)
        assertEquals(-Math.PI / 2, reversed.angleSweep, 1e-12)
    }

    @Test fun `collapsed angle is undefined rather than a misleading zero degree value`() {
        val scene = ConstructionScene(points = listOf(GeometryPoint("A", 0.0, 0.0), GeometryPoint("V", 0.0, 0.0), GeometryPoint("B", 3.0, 0.0)))
        val layout = ConstructionMeasurementGeometry.layout(scene, GeometryMeasurement("m", MeasurementType.ANGLE, listOf("A", "V", "B")))!!
        assertTrue(layout.value.isNaN())
        assertTrue(layout.label.x.isFinite() && layout.label.y.isFinite())
    }

    @Test fun `radius arrow always ends on circle despite annotation dragging`() {
        val scene = ConstructionScene(points = listOf(GeometryPoint("O", 2.0, 3.0)), circles = listOf(GeometryCircle("circle", "O", 10.0)))
        val layout = ConstructionMeasurementGeometry.layout(scene, GeometryMeasurement("m", MeasurementType.RADIUS, listOf("circle"), -12.0, 4.0))!!
        val edge = layout.second!!
        assertEquals(10.0, hypot(edge.x - 2.0, edge.y - 3.0), 1e-10)
        assertEquals(10.0, layout.value, 0.0)
    }
}
