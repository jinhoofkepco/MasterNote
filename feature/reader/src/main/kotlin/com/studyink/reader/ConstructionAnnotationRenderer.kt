package com.studyink.reader

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.MeasurementType
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal enum class ConstructionAnnotationKind { MEASUREMENT, CONSTRAINT }
internal data class ConstructionAnnotationHit(val id: String, val kind: ConstructionAnnotationKind, val bounds: RectF)

/** CAD-style annotations have their own hit targets and never become geometric lines. */
internal class ConstructionAnnotationRenderer(private val density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hits = mutableListOf<ConstructionAnnotationHit>()
    private lateinit var sx: (Double) -> Float
    private lateinit var sy: (Double) -> Float
    private var scale = 1f

    fun draw(canvas: Canvas, scene: ConstructionScene, selected: Set<String>, selectedMeasurement: String?,
             selectedConstraint: String?, scale: Float, sx: (Double) -> Float, sy: (Double) -> Float): List<ConstructionAnnotationHit> {
        this.scale = scale; this.sx = sx; this.sy = sy; hits.clear()
        for (measurement in scene.measurements) {
            val layout = ConstructionMeasurementGeometry.layout(scene, measurement) ?: continue
            drawDimension(canvas, layout, measurement.id, ConstructionAnnotationKind.MEASUREMENT,
                measurement.id == selectedMeasurement, reference = true)
        }
        for (constraint in scene.constraints.filter { it.enabled }) {
            val synthetic = dimensionForConstraint(scene, constraint) ?: continue
            val duplicate = scene.measurements.any { measurement ->
                when (constraint.type) {
                    ConstraintType.LENGTH -> measurement.type == MeasurementType.DISTANCE &&
                        measurement.entityIds.toSet() == scene.segment(constraint.entityIds[0])?.let { setOf(it.startPointId, it.endPointId) }
                    ConstraintType.RADIUS -> measurement.type == MeasurementType.RADIUS && measurement.entityIds == constraint.entityIds
                    else -> false
                }
            }
            if (!duplicate) drawDimension(canvas, synthetic, constraint.id, ConstructionAnnotationKind.CONSTRAINT,
                constraint.id == selectedConstraint, reference = false)
        }
        val related = scene.constraints.filter { c ->
            c.id == selectedConstraint || c.entityIds.any { id ->
                id in selected || scene.segment(id)?.let { it.startPointId in selected || it.endPointId in selected } == true ||
                    scene.circle(id)?.centerPointId in selected
            }
        }
        related.forEach { drawBadge(canvas, scene, it, it.id == selectedConstraint) }
        return hits.toList()
    }

    private fun dimensionForConstraint(scene: ConstructionScene, c: GeometryConstraint): ConstructionMeasurementLayout? = when (c.type) {
        ConstraintType.LENGTH -> scene.segment(c.entityIds[0])?.let { s ->
            ConstructionMeasurementGeometry.layout(scene, GeometryMeasurement(c.id, MeasurementType.DISTANCE, listOf(s.startPointId, s.endPointId)))
        }
        ConstraintType.RADIUS -> ConstructionMeasurementGeometry.layout(scene, GeometryMeasurement(c.id, MeasurementType.RADIUS, c.entityIds))
        ConstraintType.DISTANCE_POINT_LINE -> {
            val p = scene.point(c.entityIds[0]); val line = scene.segment(c.entityIds[1])
            val a = line?.let { scene.point(it.startPointId) }; val b = line?.let { scene.point(it.endPointId) }
            if (p == null || a == null || b == null) null else {
                val delta = ConstructionVector(b.x - a.x, b.y - a.y)
                val norm = delta.x * delta.x + delta.y * delta.y
                if (norm < 1e-16) null else {
                    val point = ConstructionVector(p.x, p.y)
                    val amount = ((p.x - a.x) * delta.x + (p.y - a.y) * delta.y) / norm
                    val foot = ConstructionVector(a.x, a.y) + delta * amount
                    val base = (point + foot) * .5 + delta.unit() * .9
                    ConstructionMeasurementLayout(c.id, MeasurementType.DISTANCE, base, base, (point - foot).length(), point, foot)
                }
            }
        }
        else -> null
    }

    private fun drawDimension(canvas: Canvas, layout: ConstructionMeasurementLayout, id: String,
                              kind: ConstructionAnnotationKind, selected: Boolean, reference: Boolean) {
        paint.reset(); paint.isAntiAlias = true; paint.strokeWidth = density * 1.15f
        paint.color = if (reference) Color.rgb(88, 105, 125) else Color.rgb(46, 61, 77)
        paint.style = Paint.Style.STROKE
        when (layout.type) {
            MeasurementType.DISTANCE -> {
                val a = layout.first; val b = layout.second!!
                val delta = b - a
                if (delta.length() > 1e-8) {
                    val direction = delta.unit(); val normal = ConstructionVector(-direction.y, direction.x)
                    val middle = (a + b) * .5
                    val gap = (layout.label.x - middle.x) * normal.x + (layout.label.y - middle.y) * normal.y
                    val sign = if (gap < 0) -1.0 else 1.0
                    val da = a + normal * gap; val db = b + normal * gap
                    line(canvas, a + normal * (.08 * sign), da + normal * (.15 * sign))
                    line(canvas, b + normal * (.08 * sign), db + normal * (.15 * sign))
                    line(canvas, da, db)
                    arrow(canvas, da, direction); arrow(canvas, db, direction * -1.0)
                    val projected = middle + normal * gap
                    if ((layout.label - projected).length() > delta.length() / 2) line(canvas, projected, layout.label)
                }
            }
            MeasurementType.RADIUS -> {
                val edge = layout.second!!
                line(canvas, layout.first, edge)
                arrow(canvas, edge, (layout.first - edge).unit())
                line(canvas, edge, layout.label)
            }
            MeasurementType.ANGLE -> if (layout.value.isFinite()) {
                val center = layout.vertex!!; val r = layout.arcRadius
                val start = center + ConstructionVector(cos(layout.angleStart), sin(layout.angleStart)) * r
                val endAngle = layout.angleStart + layout.angleSweep
                val end = center + ConstructionVector(cos(endAngle), sin(endAngle)) * r
                canvas.drawArc(RectF(sx(center.x - r), sy(center.y + r), sx(center.x + r), sy(center.y - r)),
                    -Math.toDegrees(layout.angleStart).toFloat(), -Math.toDegrees(layout.angleSweep).toFloat(), false, paint)
                // Rays identify the actual vertex even when the segment itself is short.
                line(canvas, center, start); line(canvas, center, end)
                val sign = if (layout.angleSweep >= 0) 1.0 else -1.0
                arrow(canvas, start, ConstructionVector(-sin(layout.angleStart), cos(layout.angleStart)) * sign)
                arrow(canvas, end, ConstructionVector(sin(endAngle), -cos(endAngle)) * sign)
                val mid = center + ConstructionVector(cos(layout.angleStart + layout.angleSweep / 2), sin(layout.angleStart + layout.angleSweep / 2)) * r
                if ((layout.label - mid).length() > .6) line(canvas, mid, layout.label)
            }
            MeasurementType.AREA -> if (selected) {
                val second = layout.second!!; val third = layout.third!!
                val path = Path().apply {
                    moveTo(sx(layout.first.x), sy(layout.first.y))
                    lineTo(sx(second.x), sy(second.y))
                    lineTo(sx(third.x), sy(third.y)); close()
                }
                paint.style = Paint.Style.FILL; paint.color = 0x162563EB
                canvas.drawPath(path, paint)
            }
        }
        val text = when (layout.type) {
            MeasurementType.DISTANCE -> "${formatGeometry(layout.value)} cm"
            MeasurementType.RADIUS -> "r ${formatGeometry(layout.value)} cm"
            MeasurementType.ANGLE -> if (layout.value.isFinite()) "${formatGeometry(layout.value)}°" else "각도 미정"
            MeasurementType.AREA -> "${formatGeometry(layout.value)} cm²"
        }
        caption(canvas, (if (reference) "≈ " else "") + text, sx(layout.label.x), sy(layout.label.y), id, kind, selected)
    }

    private fun line(canvas: Canvas, a: ConstructionVector, b: ConstructionVector) = canvas.drawLine(sx(a.x), sy(a.y), sx(b.x), sy(b.y), paint)
    private fun arrow(canvas: Canvas, tip: ConstructionVector, inward: ConstructionVector) {
        val x = sx(tip.x); val y = sy(tip.y)
        val u = inward.unit(); val vx = u.x.toFloat(); val vy = -u.y.toFloat()
        val size = 6f * density
        canvas.drawLine(x, y, x + (vx * size - vy * size * .4f), y + (vy * size + vx * size * .4f), paint)
        canvas.drawLine(x, y, x + (vx * size + vy * size * .4f), y + (vy * size - vx * size * .4f), paint)
    }
    private fun caption(canvas: Canvas, text: String, x: Float, y: Float, id: String, kind: ConstructionAnnotationKind, selected: Boolean) {
        paint.reset(); paint.isAntiAlias = true; paint.textSize = 12f * density
        val half = paint.measureText(text) / 2
        val bounds = RectF(x - half - 5 * density, y - 10 * density, x + half + 5 * density, y + 10 * density)
        paint.color = if (selected) 0xFFE5EDFF.toInt() else 0xF7FFFEF9.toInt()
        canvas.drawRoundRect(bounds, 4 * density, 4 * density, paint)
        if (selected) {
            paint.color = 0xFF4776C5.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = density
            canvas.drawRoundRect(bounds, 4 * density, 4 * density, paint); paint.style = Paint.Style.FILL
        }
        paint.color = if (kind == ConstructionAnnotationKind.CONSTRAINT) 0xFF293A4D.toInt() else 0xFF58697D.toInt()
        canvas.drawText(text, x - half, y - (paint.ascent() + paint.descent()) / 2, paint)
        hits += ConstructionAnnotationHit(id, kind, RectF(bounds).apply { inset(-5 * density, -10 * density) })
    }

    private fun drawBadge(canvas: Canvas, scene: ConstructionScene, constraint: GeometryConstraint, selected: Boolean) {
        val target = constraint.entityIds.firstOrNull() ?: return
        val anchor = scene.point(target)?.let { ConstructionVector(it.x, it.y) }
            ?: scene.segment(target)?.let { s ->
                val a = scene.point(s.startPointId); val b = scene.point(s.endPointId)
                if (a == null || b == null) null else ConstructionVector((a.x + b.x) / 2, (a.y + b.y) / 2)
            }
            ?: scene.circle(target)?.let { c -> scene.point(c.centerPointId)?.let { ConstructionVector(it.x + c.radius, it.y) } }
            ?: return
        val half = 12f * density
        var bounds = RectF()
        for (attempt in 0..40) {
            val x = (sx(anchor.x) + (attempt % 5 - 2) * 29 * density).coerceIn(half + 3 * density, max(half + 3 * density, canvas.width - half - 3 * density))
            val y = (sy(anchor.y) + 27 * density + (attempt / 5) * 29 * density).coerceIn(half + 3 * density, max(half + 3 * density, canvas.height - half - 3 * density))
            bounds = RectF(x - half, y - half, x + half, y + half)
            if (hits.none { RectF.intersects(it.bounds, bounds) }) break
        }
        paint.reset(); paint.isAntiAlias = true; paint.color = if (selected) 0xFFDFEAFE.toInt() else 0xF7F3F6FB.toInt()
        canvas.drawRoundRect(bounds, 6 * density, 6 * density, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        paint.color = if (constraint.enabled) 0xFF526A84.toInt() else 0xFF9A9FA8.toInt()
        canvas.drawRoundRect(bounds, 6 * density, 6 * density, paint)
        canvas.save(); canvas.translate(bounds.centerX(), bounds.centerY()); canvas.scale(density, density)
        paint.strokeWidth = 1.4f
        symbol(canvas, constraint.type)
        if (!constraint.enabled) canvas.drawLine(-8f, 8f, 8f, -8f, paint)
        canvas.restore()
        hits += ConstructionAnnotationHit(constraint.id, ConstructionAnnotationKind.CONSTRAINT, RectF(bounds).apply { inset(-8 * density, -8 * density) })
    }

    private fun symbol(c: Canvas, type: ConstraintType) {
        fun line(ax: Float, ay: Float, bx: Float, by: Float) = c.drawLine(ax, ay, bx, by, paint)
        when (type) {
            ConstraintType.COINCIDENT -> { c.drawCircle(-2f, 0f, 4f, paint); c.drawCircle(2f, 0f, 4f, paint) }
            ConstraintType.POINT_ON_LINE -> { line(-7f, 0f, 7f, 0f); c.drawCircle(0f, 0f, 3f, paint) }
            ConstraintType.POINT_ON_CIRCLE -> { c.drawCircle(0f, 0f, 6f, paint); c.drawCircle(4f, -4f, 2f, paint) }
            ConstraintType.PARALLEL -> { line(-6f, 6f, 0f, -6f); line(0f, 6f, 6f, -6f) }
            ConstraintType.PERPENDICULAR -> { line(-6f, 5f, 6f, 5f); line(0f, -6f, 0f, 5f) }
            ConstraintType.HORIZONTAL -> line(-7f, 0f, 7f, 0f)
            ConstraintType.VERTICAL -> line(0f, -7f, 0f, 7f)
            ConstraintType.EQUAL_LENGTH -> { line(-6f, -3f, 6f, -3f); line(-6f, 3f, 6f, 3f) }
            ConstraintType.ANGLE -> { line(-6f, 5f, 7f, 5f); line(-6f, 5f, 3f, -6f); c.drawArc(RectF(-12f, -1f, 0f, 11f), -50f, 50f, false, paint) }
            ConstraintType.FIXED_POINT -> { c.drawRect(-5f, -1f, 5f, 7f, paint); c.drawArc(RectF(-3f, -7f, 3f, 3f), 180f, 180f, false, paint) }
            ConstraintType.LENGTH -> { line(-7f, 0f, 7f, 0f); line(-7f, 0f, -3f, -3f); line(-7f, 0f, -3f, 3f); line(7f, 0f, 3f, -3f); line(7f, 0f, 3f, 3f) }
            ConstraintType.DISTANCE_POINT_LINE -> { line(-7f, 6f, 7f, 6f); line(0f, -7f, 0f, 6f); c.drawCircle(0f, -7f, 2f, paint) }
            ConstraintType.RADIUS -> { paint.style = Paint.Style.FILL; paint.textSize = 16f; c.drawText("r", -3f, 5f, paint); paint.style = Paint.Style.STROKE }
        }
    }
}
