package com.studyink.reader

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.MeasurementType
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal enum class ConstructionAnnotationKind { MEASUREMENT, CONSTRAINT }
internal data class ConstructionAnnotationHit(
    val id: String,
    val kind: ConstructionAnnotationKind,
    val bounds: RectF,
    /** Visible caption/badge outranks a nearby point; expanded touch padding does not. */
    val visualBounds: RectF = RectF(bounds),
    val label: String = "",
    val targetEntityId: String? = null,
    /** Screen-space attachment point for local relation leaders, not a model coordinate. */
    val targetAnchor: PointF? = null,
)

/** CAD-style annotations have their own hit targets and never become geometric lines. */
internal class ConstructionAnnotationRenderer(private val density: Float) {
    private companion object {
        // Dimension guides and numbers are reference information, not drawable geometry.
        // Keep both measured and driving dimensions in the same quiet blue family.
        const val DIMENSION_GUIDE = 0xFF96B4D8.toInt()
        const val DIMENSION_TEXT = 0xFF5F82AD.toInt()
        const val DIMENSION_SELECTED = 0xFF4776C5.toInt()
        const val DIMENSION_DISABLED = 0xFF9A9FA8.toInt()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hits = mutableListOf<ConstructionAnnotationHit>()
    private lateinit var sx: (Double) -> Float
    private lateinit var sy: (Double) -> Float
    private var scale = 1f

    fun draw(canvas: Canvas, scene: ConstructionScene, selected: Set<String>, selectedMeasurement: String?,
             selectedConstraint: String?, scale: Float, sx: (Double) -> Float, sy: (Double) -> Float): List<ConstructionAnnotationHit> {
        this.scale = scale; this.sx = sx; this.sy = sy; hits.clear()
        val dimensions = scene.constraints.mapNotNull { constraint ->
            ConstructionMeasurementGeometry.constraintLayout(scene, constraint)?.let { constraint to it }
        }
        for (measurement in scene.measurements) {
            // A reference measurement must never hide the editable target. Preserve its stored
            // offset in constraintLayout so adding or pausing a condition does not jump the label.
            if (dimensions.any { (constraint, _) -> ConstructionMeasurementGeometry.matchesConstraint(scene, measurement, constraint) }) continue
            val layout = ConstructionMeasurementGeometry.layout(scene, measurement) ?: continue
            drawDimension(canvas, layout, measurement.id, ConstructionAnnotationKind.MEASUREMENT,
                measurement.id == selectedMeasurement, reference = true)
        }
        for ((constraint, layout) in dimensions) {
            val placed = placeDrivingCaption(canvas, layout, constraint.enabled)
            drawDimension(canvas, placed, constraint.id, ConstructionAnnotationKind.CONSTRAINT,
                constraint.id == selectedConstraint, reference = false, enabled = constraint.enabled)
        }
        val dimensionIds = dimensions.mapTo(mutableSetOf()) { it.first.id }
        scene.constraints.filter { it.type == ConstraintType.EQUAL_ANGLE }.forEachIndexed { index, constraint ->
            val layouts = ConstructionMeasurementGeometry.equalAngleLayouts(scene, constraint)
            layouts.forEach { layout ->
                // Matching captions on both arcs identify an equality, not a fixed degree value.
                val relation = layout.copy(captionOverride = "같은 각 ${index + 1}")
                val placed = placeDrivingCaption(canvas, relation, constraint.enabled)
                drawDimension(canvas, placed, constraint.id, ConstructionAnnotationKind.CONSTRAINT,
                    constraint.id == selectedConstraint, reference = false, enabled = constraint.enabled)
            }
            if (layouts.isNotEmpty()) dimensionIds += constraint.id
        }
        scene.constraints.filter { it.type == ConstraintType.EQUAL_DISTANCE_POINTS }.forEachIndexed { index, constraint ->
            val layouts = ConstructionMeasurementGeometry.equalDistanceLayouts(scene, constraint)
            layouts.forEach { layout ->
                val relation = layout.copy(captionOverride = "같은 거리 ${index + 1}")
                drawDimension(canvas, placeDrivingCaption(canvas, relation, constraint.enabled), constraint.id,
                    ConstructionAnnotationKind.CONSTRAINT, constraint.id == selectedConstraint, reference = false, enabled = constraint.enabled)
            }
            if (layouts.isNotEmpty()) dimensionIds += constraint.id
        }
        val related = scene.constraints.filter { c ->
            c.id !in dimensionIds && (c.enabled || c.id == selectedConstraint || c.entityIds.any { id ->
                id in selected || scene.segment(id)?.let { it.startPointId in selected || it.endPointId in selected } == true ||
                    scene.circle(id)?.centerPointId in selected
            })
        }
        // Slot allocation includes hidden disabled relations. Selecting a condition must not
        // shuffle its neighbors; a slot belongs to its local entity, not to screen-wide free space.
        val visibleIds = related.mapTo(hashSetOf()) { it.id }
        val reservedCaptions = hits.mapTo(mutableListOf()) { RectF(it.visualBounds) }
        val localSlots = mutableMapOf<String, Int>()
        val groups = mutableMapOf<ConstraintType, Int>()
        scene.constraints.filter { it.id !in dimensionIds }.forEach { constraint ->
            val group = (groups[constraint.type] ?: 0) + 1
            groups[constraint.type] = group
            constraint.entityIds.distinct().forEachIndexed { member, entityId ->
                val slot = localSlots[entityId] ?: 0
                localSlots[entityId] = slot + 1
                drawBadge(canvas, scene, constraint, entityId, member, group, slot,
                    constraint.id == selectedConstraint, constraint.id in visibleIds, reservedCaptions)
            }
        }
        return hits.toList()
    }

    /** Only automatically placed driving dimensions yield to another label. A reference
     * measurement keeps its user-dragged offset. Placement is screen-space and deterministic,
     * so zooming out cannot turn two different targets into one overlapping tap rectangle. */
    private fun placeDrivingCaption(canvas: Canvas, layout: ConstructionMeasurementLayout, enabled: Boolean): ConstructionMeasurementLayout {
        val text = dimensionText(layout, reference = false, enabled = enabled)
        val x = sx(layout.label.x); val y = sy(layout.label.y)
        val original = captionBounds(text, x, y)
        val originalHit = touchBounds(original)
        if (hits.none { RectF.intersects(it.bounds, originalHit) }) return layout
        // Offscreen objects must not migrate onto the visible canvas merely to avoid each other.
        if (!RectF.intersects(original, RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()))) return layout
        val step = 24f * density
        val gap = 3f * density
        val margin = 4f * density
        for (ring in 1..3) {
            val candidates = buildList {
                for (dx in -ring..ring) for (dy in -ring..ring) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) == ring) add(dx to dy)
                }
            }.sortedBy { (dx, dy) -> dx * dx + dy * dy }
            for ((dx, dy) in candidates) {
                val offsetX = dx * step; val offsetY = dy * step
                val bounds = RectF(originalHit).apply { offset(offsetX, offsetY) }
                if (bounds.left < margin || bounds.top < margin || bounds.right > canvas.width - margin || bounds.bottom > canvas.height - margin) continue
                val spaced = RectF(bounds).apply { inset(-gap, -gap) }
                if (hits.none { RectF.intersects(it.bounds, spaced) }) {
                    return layout.copy(
                        label = layout.label + ConstructionVector(offsetX / scale.toDouble(), -offsetY / scale.toDouble()),
                        dimensionGuideAnchor = layout.dimensionGuideAnchor ?: layout.label,
                    )
                }
            }
        }
        return layout
    }

    private fun drawDimension(canvas: Canvas, layout: ConstructionMeasurementLayout, id: String,
                              kind: ConstructionAnnotationKind, selected: Boolean, reference: Boolean, enabled: Boolean = true) {
        paint.reset(); paint.isAntiAlias = true; paint.strokeWidth = density * 1.15f
        paint.color = when { !enabled -> DIMENSION_DISABLED; selected -> DIMENSION_SELECTED; else -> DIMENSION_GUIDE }
        paint.style = Paint.Style.STROKE
        if (reference) paint.pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
        when (layout.type) {
            MeasurementType.DISTANCE -> {
                val a = layout.first; val b = layout.second!!
                val delta = b - a
                if (delta.length() > 1e-8) {
                    val direction = delta.unit(); val normal = ConstructionVector(-direction.y, direction.x)
                    val middle = (a + b) * .5
                    val guide = layout.dimensionGuideAnchor ?: layout.label
                    val gap = (guide.x - middle.x) * normal.x + (guide.y - middle.y) * normal.y
                    val sign = if (gap < 0) -1.0 else 1.0
                    val da = a + normal * gap; val db = b + normal * gap
                    line(canvas, a + normal * (.08 * sign), da + normal * (.15 * sign))
                    line(canvas, b + normal * (.08 * sign), db + normal * (.15 * sign))
                    line(canvas, da, db)
                    arrow(canvas, da, direction); arrow(canvas, db, direction * -1.0)
                    val projected = middle + normal * gap
                    if (layout.dimensionGuideAnchor != null || (layout.label - projected).length() > delta.length() / 2) line(canvas, projected, layout.label)
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
                // Dashed rays show translated start->end directions, not new geometric lines.
                // A normal three-point measurement keeps its ordinary interior-angle guides.
                if (layout.directionSegments.isNotEmpty()) {
                    paint.pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
                }
                line(canvas, center, start); line(canvas, center, end)
                paint.pathEffect = null
                for ((a, b) in layout.directionSegments) {
                    val direction = (b - a).unit()
                    val middle = (a + b) * .5
                    val markerLength = minOf((b - a).length() * .15, 12.0 * density / scale.coerceAtLeast(.001f))
                    line(canvas, middle - direction * markerLength, middle + direction * markerLength)
                    arrow(canvas, middle + direction * markerLength, direction * -1.0)
                }
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
        caption(canvas, dimensionText(layout, reference, enabled), sx(layout.label.x), sy(layout.label.y), id, kind, selected, enabled)
    }

    private fun dimensionText(layout: ConstructionMeasurementLayout, reference: Boolean, enabled: Boolean): String {
        val text = layout.captionOverride ?: (layout.captionPrefix + when (layout.type) {
            MeasurementType.DISTANCE -> "${formatGeometry(layout.value)} cm"
            MeasurementType.RADIUS -> "r ${formatGeometry(layout.value)} cm"
            MeasurementType.ANGLE -> if (layout.value.isFinite())
                (if (layout.directionSegments.isNotEmpty()) "방향각 " else "") + "${formatGeometry(layout.value)}°" else "각도 미정"
            MeasurementType.AREA -> "${formatGeometry(layout.value)} cm²"
        })
        return when {
            !enabled -> "꺼짐 · $text"
            reference -> "측정 · ($text)"
            layout.captionOverride != null -> "조건 · $text"
            else -> "고정 · $text"
        }
    }

    private fun line(canvas: Canvas, a: ConstructionVector, b: ConstructionVector) = canvas.drawLine(sx(a.x), sy(a.y), sx(b.x), sy(b.y), paint)
    private fun arrow(canvas: Canvas, tip: ConstructionVector, inward: ConstructionVector) {
        val x = sx(tip.x); val y = sy(tip.y)
        val u = inward.unit(); val vx = u.x.toFloat(); val vy = -u.y.toFloat()
        val size = 6f * density
        canvas.drawLine(x, y, x + (vx * size - vy * size * .4f), y + (vy * size + vx * size * .4f), paint)
        canvas.drawLine(x, y, x + (vx * size + vy * size * .4f), y + (vy * size - vx * size * .4f), paint)
    }
    private fun caption(canvas: Canvas, text: String, x: Float, y: Float, id: String, kind: ConstructionAnnotationKind, selected: Boolean, enabled: Boolean) {
        paint.reset(); paint.isAntiAlias = true; paint.textSize = 12f * density
        val half = paint.measureText(text) / 2
        val bounds = captionBounds(text, x, y)
        val reference = kind == ConstructionAnnotationKind.MEASUREMENT
        paint.color = when { selected -> 0xFFE5EDFF.toInt(); reference -> 0xF7FFFEF9.toInt(); else -> 0xFFEAF0F7.toInt() }
        canvas.drawRoundRect(bounds, 4 * density, 4 * density, paint)
        paint.color = if (selected) DIMENSION_SELECTED else DIMENSION_GUIDE
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        if (reference) paint.pathEffect = DashPathEffect(floatArrayOf(3 * density, 3 * density), 0f)
        canvas.drawRoundRect(bounds, 4 * density, 4 * density, paint)
        paint.pathEffect = null; paint.style = Paint.Style.FILL
        paint.color = when { !enabled -> DIMENSION_DISABLED; selected -> DIMENSION_SELECTED; else -> DIMENSION_TEXT }
        canvas.drawText(text, x - half, y - (paint.ascent() + paint.descent()) / 2, paint)
        if (!reference) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = density
            val lockX = bounds.left + 6 * density
            canvas.drawRoundRect(RectF(lockX - 2.5f * density, y, lockX + 2.5f * density, y + 4 * density), density, density, paint)
            canvas.drawArc(RectF(lockX - 1.8f * density, y - 4 * density, lockX + 1.8f * density, y + 2 * density), 180f, 180f, false, paint)
            if (!enabled) canvas.drawLine(lockX - 4 * density, y + 5 * density, lockX + 4 * density, y - 5 * density, paint)
        }
        hits += ConstructionAnnotationHit(id, kind, touchBounds(bounds), RectF(bounds), label = text)
    }

    private fun captionBounds(text: String, x: Float, y: Float): RectF {
        paint.textSize = 12f * density
        val half = paint.measureText(text) / 2
        val leading = if (text.startsWith("측정")) 5f else 14f
        return RectF(x - half - leading * density, y - 10 * density, x + half + leading * density, y + 10 * density)
    }

    private fun touchBounds(visual: RectF) = RectF(visual).apply { inset(-5 * density, -10 * density) }

    private fun drawBadge(canvas: Canvas, scene: ConstructionScene, constraint: GeometryConstraint,
                          target: String, member: Int, group: Int, slot: Int, selected: Boolean,
                          visible: Boolean, reservedCaptions: MutableList<RectF>) {
        val normal: ConstructionVector
        val anchor: ConstructionVector
        val segment = scene.segment(target)
        val circle = scene.circle(target)
        when {
            segment != null -> {
                val a = scene.point(segment.startPointId) ?: return
                val b = scene.point(segment.endPointId) ?: return
                anchor = ConstructionVector((a.x + b.x) / 2, (a.y + b.y) / 2)
                val direction = ConstructionVector(b.x - a.x, b.y - a.y).unit()
                // Normal follows the same stable start/end IDs as the actual line.
                normal = ConstructionVector(-direction.y, -direction.x)
            }
            circle != null -> {
                val center = scene.point(circle.centerPointId) ?: return
                anchor = ConstructionVector(center.x + circle.radius, center.y)
                normal = ConstructionVector(1.0, 0.0)
            }
            else -> {
                val point = scene.point(target) ?: return
                anchor = ConstructionVector(point.x, point.y)
                normal = ConstructionVector(.707106781, -.707106781)
            }
        }
        val ax = sx(anchor.x); val ay = sy(anchor.y)
        if (ax < 0f || ax > canvas.width || ay < 0f || ay > canvas.height) return
        val relationText = when (constraint.type) {
            ConstraintType.FIXED_POINT -> "고정"
            ConstraintType.COINCIDENT -> "일치 $group"
            ConstraintType.POINT_ON_LINE -> "직선 위 $group"
            ConstraintType.POINT_ON_SEGMENT -> "선분 위 $group"
            ConstraintType.POINT_ON_CIRCLE -> "원 위 $group"
            ConstraintType.POINT_FRACTION -> "${constraint.numerator}/${constraint.denominator} 위치 $group"
            ConstraintType.LENGTH_RATIO -> "비$group ×${if (member == 0) formatGeometry(constraint.value ?: 1.0) else "1"}"
            ConstraintType.PARALLEL -> "평행 $group"
            ConstraintType.PERPENDICULAR -> "직각 $group"
            ConstraintType.HORIZONTAL -> "수평"
            ConstraintType.VERTICAL -> "수직"
            ConstraintType.EQUAL_LENGTH -> "같은 길이 $group"
            else -> "조건 $group"
        }.let { if (constraint.enabled) it else "꺼짐 · $it" }
        paint.reset(); paint.isAntiAlias = true; paint.textSize = 10f * density
        val halfHeight = 11f * density
        val halfWidth = (paint.measureText(relationText) + 23 * density) / 2
        // Nine bounded local slots (three outward tiers, three side offsets). Dense diagrams may
        // still overlap; their relations remain selectable in the list instead of fleeing elsewhere.
        val normalExtent = kotlin.math.abs(normal.x).toFloat() * halfWidth + kotlin.math.abs(normal.y).toFloat() * halfHeight
        fun localBounds(localSlot: Int): RectF {
            val tier = localSlot % 3
            val column = when ((localSlot / 3) % 3) { 1 -> 1; 2 -> -1; else -> 0 }
            val distance = normalExtent + (11 + tier * 24) * density
            val tangent = column * 34f * density
            val x = ax + normal.x.toFloat() * distance - normal.y.toFloat() * tangent
            val y = ay + normal.y.toFloat() * distance + normal.x.toFloat() * tangent
            return RectF(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        }
        val preferred = localBounds(slot % 9)
        val gap = 3f * density
        // Reserve every measurement caption and earlier local badge, including hidden paused
        // relations. Only these same nine nearby slots are tried; selection cannot rearrange them.
        val bounds = (0 until 9).asSequence().map { localBounds((slot + it) % 9) }.firstOrNull { candidate ->
            val spaced = RectF(candidate).apply { inset(-gap, -gap) }
            reservedCaptions.none { RectF.intersects(it, spaced) }
        } ?: preferred
        reservedCaptions += RectF(bounds)
        if (!visible) return
        val x = bounds.centerX(); val y = bounds.centerY()
        // Clip with the viewport naturally. Never clamp a badge to a distant screen edge.
        if (!RectF.intersects(bounds, RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()))) return
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        paint.color = when { !constraint.enabled -> DIMENSION_DISABLED; selected -> DIMENSION_SELECTED; else -> DIMENSION_GUIDE }
        val leaderX = x - normal.x.toFloat() * normalExtent
        val leaderY = y - normal.y.toFloat() * normalExtent
        canvas.drawLine(ax, ay, leaderX, leaderY, paint)
        canvas.drawLine(ax + normal.y.toFloat() * 3 * density, ay - normal.x.toFloat() * 3 * density,
            ax - normal.y.toFloat() * 3 * density, ay + normal.x.toFloat() * 3 * density, paint)
        paint.style = Paint.Style.FILL; paint.color = if (selected) 0xFFDFEAFE.toInt() else 0xFFEAF0F7.toInt()
        canvas.drawRoundRect(bounds, 4 * density, 4 * density, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        paint.color = if (selected) DIMENSION_SELECTED else if (constraint.enabled) DIMENSION_TEXT else DIMENSION_DISABLED
        canvas.drawRoundRect(bounds, 4 * density, 4 * density, paint)
        // A blue selection outline only indicates focus. Paused relation symbols and text stay
        // gray (with "꺼짐" and a strike) so selecting one never makes it appear enabled.
        paint.color = when { !constraint.enabled -> DIMENSION_DISABLED; selected -> DIMENSION_SELECTED; else -> DIMENSION_TEXT }
        canvas.save(); canvas.translate(bounds.left + 10 * density, y); canvas.scale(density * .8f, density * .8f)
        paint.strokeWidth = 1.3f
        symbol(canvas, constraint.type)
        if (!constraint.enabled) canvas.drawLine(-8f, 8f, 8f, -8f, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL; paint.textSize = 10f * density
        canvas.drawText(relationText, bounds.left + 20 * density, y - (paint.ascent() + paint.descent()) / 2, paint)
        hits += ConstructionAnnotationHit(constraint.id, ConstructionAnnotationKind.CONSTRAINT,
            RectF(bounds).apply { inset(-4 * density, -7 * density) }, RectF(bounds), relationText, target, PointF(ax, ay))
    }

    private fun symbol(c: Canvas, type: ConstraintType) {
        fun line(ax: Float, ay: Float, bx: Float, by: Float) = c.drawLine(ax, ay, bx, by, paint)
        when (type) {
            ConstraintType.COINCIDENT -> { c.drawCircle(-2f, 0f, 4f, paint); c.drawCircle(2f, 0f, 4f, paint) }
            ConstraintType.POINT_ON_LINE -> { line(-7f, 0f, 7f, 0f); c.drawCircle(0f, 0f, 3f, paint) }
            ConstraintType.POINT_ON_SEGMENT -> { line(-7f, 0f, 7f, 0f); line(-7f, -3f, -7f, 3f); line(7f, -3f, 7f, 3f); c.drawCircle(0f, 0f, 2f, paint) }
            ConstraintType.POINT_FRACTION -> { line(-7f, 0f, 7f, 0f); for (x in floatArrayOf(-7f, 0f, 7f)) line(x, -3f, x, 3f) }
            ConstraintType.POINT_DISTANCE -> { line(-7f, 0f, 7f, 0f); c.drawCircle(-7f, 0f, 2f, paint); line(7f, -3f, 7f, 3f) }
            ConstraintType.POINT_ON_CIRCLE -> { c.drawCircle(0f, 0f, 6f, paint); c.drawCircle(4f, -4f, 2f, paint) }
            ConstraintType.PARALLEL -> { line(-6f, 6f, 0f, -6f); line(0f, 6f, 6f, -6f) }
            ConstraintType.PERPENDICULAR -> { line(-6f, 5f, 6f, 5f); line(0f, -6f, 0f, 5f) }
            ConstraintType.HORIZONTAL -> line(-7f, 0f, 7f, 0f)
            ConstraintType.VERTICAL -> line(0f, -7f, 0f, 7f)
            ConstraintType.EQUAL_LENGTH, ConstraintType.EQUAL_DISTANCE_POINTS -> { line(-6f, -3f, 6f, -3f); line(-6f, 3f, 6f, 3f) }
            ConstraintType.LENGTH_RATIO -> { line(-7f, -3f, 7f, -3f); line(-7f, 3f, 0f, 3f) }
            ConstraintType.ANGLE, ConstraintType.INTERIOR_ANGLE -> { line(-6f, 5f, 7f, 5f); line(-6f, 5f, 3f, -6f); c.drawArc(RectF(-12f, -1f, 0f, 11f), -50f, 50f, false, paint) }
            ConstraintType.EQUAL_ANGLE -> { line(-7f, 5f, 7f, 5f); line(-7f, 5f, 0f, -5f); line(2f, -3f, 7f, -3f); line(2f, 0f, 7f, 0f) }
            ConstraintType.FIXED_POINT -> { c.drawRect(-5f, -1f, 5f, 7f, paint); c.drawArc(RectF(-3f, -7f, 3f, 3f), 180f, 180f, false, paint) }
            ConstraintType.LENGTH, ConstraintType.DISTANCE_POINTS -> { line(-7f, 0f, 7f, 0f); line(-7f, 0f, -3f, -3f); line(-7f, 0f, -3f, 3f); line(7f, 0f, 3f, -3f); line(7f, 0f, 3f, 3f) }
            ConstraintType.DISTANCE_POINT_LINE -> { line(-7f, 6f, 7f, 6f); line(0f, -7f, 0f, 6f); c.drawCircle(0f, -7f, 2f, paint) }
            ConstraintType.RADIUS -> { paint.style = Paint.Style.FILL; paint.textSize = 16f; c.drawText("r", -3f, 5f, paint); paint.style = Paint.Style.STROKE }
        }
    }
}
