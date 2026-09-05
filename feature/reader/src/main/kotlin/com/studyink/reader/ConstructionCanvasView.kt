package com.studyink.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal enum class ConstructionTool { SELECT, POINT, SEGMENT, CIRCLE }
internal enum class ConstructionDragPhase { START, MOVE, END, CANCEL }
internal data class ConstructionAnchor(
    val x: Double,
    val y: Double,
    val pointId: String? = null,
    val lineIds: List<String> = emptyList(),
    val snapLabel: String = "",
)

/** The viewport is presentation only: one uniform cm-to-pixel scale preserves mathematical angles. */
internal class ConstructionCanvasView(context: Context) : View(context) {
    var scene = ConstructionScene()
        set(value) { field = value; annotationHits = emptyList(); invalidate(); updateDescription() }
    var selectedIds: Set<String> = emptySet()
        set(value) { field = value; invalidate() }
    var tool = ConstructionTool.SELECT
        set(value) { field = value; firstAnchor = null; snapPreview = null; publishHint(); invalidate() }
    var editable = true
    var snapEnabled = true
        set(value) { field = value; snapPreview = null; publishHint(); invalidate() }
    var selectedMeasurementId: String? = null
        set(value) { field = value; invalidate() }
    var selectedConstraintId: String? = null
        set(value) { field = value; invalidate() }
    var onSelectionChanged: (Set<String>) -> Unit = {}
    var onPoint: (ConstructionAnchor) -> Unit = {}
    var onSegment: (ConstructionAnchor, ConstructionAnchor) -> Unit = { _, _ -> }
    var onCircle: (ConstructionAnchor, Double) -> Unit = { _, _ -> }
    var onDragPoint: (String, Double, Double, ConstructionDragPhase) -> Unit = { _, _, _, _ -> }
    var onMeasurementSelected: (String) -> Unit = {}
    var onMeasurementDrag: (String, Double, Double, ConstructionDragPhase) -> Unit = { _, _, _, _ -> }
    var onConstraintSelected: (String) -> Unit = {}
    var onToolHintChanged: (String) -> Unit = {}
        set(value) { field = value; lastHint = ""; publishHint() }

    private val density = resources.displayMetrics.density
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val annotations = ConstructionAnnotationRenderer(density)
    private var annotationHits = emptyList<ConstructionAnnotationHit>()
    private var scale = 28f * density
    private var originX = 48f * density
    private var originY = 400f * density
    private var initialized = false
    private var firstAnchor: ConstructionAnchor? = null
    private var snapPreview: ConstructionAnchor? = null
    private var lastHint = ""
    private var down = PointF()
    private var movingPoint: String? = null
    private var movingMeasurement: String? = null
    private var tappedAnnotation: ConstructionAnnotationHit? = null
    private var measurementStartOffset = ConstructionVector(0.0, 0.0)
    private var pointerStartWorld = ConstructionVector(0.0, 0.0)
    private var pointGrabOffset = ConstructionVector(0.0, 0.0)
    private var dragging = false
    private var panning = false
    private var hadMultiTouch = false
    private var lastPan = PointF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    init {
        setBackgroundColor(Color.rgb(255, 254, 249))
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    private fun updateDescription() {
        contentDescription = "작도 영역. 점 ${scene.points.size}개, 선분 ${scene.segments.size}개, 원 ${scene.circles.size}개, 측정 ${scene.measurements.size}개. 대상을 선택해 조건을 확인하고 측정 글자를 끌어 표시 위치를 바꿉니다."
    }

    private fun publishHint() {
        val stage = when (tool) {
            ConstructionTool.SELECT -> "대상 선택"
            ConstructionTool.POINT -> "점 · 위치 선택"
            ConstructionTool.SEGMENT -> if (firstAnchor == null) "선분 · 시작점 선택" else "선분 · 끝점 선택"
            ConstructionTool.CIRCLE -> if (firstAnchor == null) "원 · 중심 선택" else "원 · 반지름 위치 선택"
        }
        val snap = snapPreview?.snapLabel.orEmpty()
        val hint = stage + if (snap.isNotEmpty()) " · $snap" else ""
        if (lastHint != hint) { lastHint = hint; onToolHintChanged(hint) }
    }

    internal fun pointScreenPosition(id: String): PointF? = point(id)?.let { PointF(sx(it.x), sy(it.y)) }
    internal fun measurementScreenPosition(id: String): PointF? = scene.measurements.firstOrNull { it.id == id }
        ?.let { ConstructionMeasurementGeometry.layout(scene, it) }?.let { PointF(sx(it.label.x), sy(it.label.y)) }

    fun clearSelection() { selectedIds = emptySet(); selectedMeasurementId = null; selectedConstraintId = null; onSelectionChanged(selectedIds) }
    fun zoom(factor: Float) = zoomAt(factor, width / 2f, height / 2f)
    private fun zoomAt(factor: Float, x: Float, y: Float) {
        val next = (scale * factor).coerceIn(3f * density, 160f * density)
        val ratio = next / scale
        originX = x - (x - originX) * ratio
        originY = y - (y - originY) * ratio
        scale = next
        annotationHits = emptyList()
        invalidate()
    }

    fun fitScene() {
        if (width <= 0 || height <= 0) return
        if (scene.points.isEmpty()) {
            scale = min(width / 23f, height / 18f).coerceAtLeast(3f * density)
            originX = width * .12f
            originY = height * .86f
        } else {
            var left = scene.points.minOf { it.x }; var right = scene.points.maxOf { it.x }
            var bottom = scene.points.minOf { it.y }; var top = scene.points.maxOf { it.y }
            for (circle in scene.circles) {
                val center = point(circle.centerPointId) ?: continue
                left = min(left, center.x - circle.radius); right = max(right, center.x + circle.radius)
                bottom = min(bottom, center.y - circle.radius); top = max(top, center.y + circle.radius)
            }
            scene.measurements.forEach { measurement ->
                ConstructionMeasurementGeometry.layout(scene, measurement)?.label?.let {
                    left = min(left, it.x); right = max(right, it.x); bottom = min(bottom, it.y); top = max(top, it.y)
                }
            }
            val padding = min(56f * density, min(width, height) * .18f)
            scale = min((width - 2 * padding) / max(right - left, 5.0), (height - 2 * padding) / max(top - bottom, 5.0))
                .toFloat().coerceIn(3f * density, 100f * density)
            originX = (width / 2.0 - (left + right) * .5 * scale).toFloat()
            originY = (height / 2.0 + (bottom + top) * .5 * scale).toFloat()
        }
        initialized = true
        annotationHits = emptyList()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized) fitScene() else { originX += (w - oldw) / 2f; originY += (h - oldh) / 2f }
        annotationHits = emptyList()
    }

    private fun point(id: String): GeometryPoint? = scene.points.firstOrNull { it.id == id }
    private fun sx(x: Double) = originX + (x * scale).toFloat()
    private fun sy(y: Double) = originY - (y * scale).toFloat()
    private fun world(x: Float, y: Float) = ConstructionAnchor(((x - originX) / scale).toDouble(), ((originY - y) / scale).toDouble())
    private fun label(text: String, x: Float, y: Float, color: Int = Color.rgb(40, 48, 59)) {
        pen.reset(); pen.isAntiAlias = true; pen.textSize = 12f * density; pen.color = color
        val width = pen.measureText(text)
        val px = x.coerceIn(4f * density, max(4f * density, this.width - width - 4f * density))
        val py = y.coerceIn(15f * density, max(15f * density, height - 5f * density))
        pen.setShadowLayer(3f * density, 0f, 0f, Color.WHITE)
        drawingCanvas?.drawText(text, px, py, pen)
        pen.clearShadowLayer()
    }
    private var drawingCanvas: Canvas? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawingCanvas = canvas
        pen.reset(); pen.isAntiAlias = true; pen.strokeWidth = density * .55f; pen.color = Color.rgb(231, 234, 233)
        val gridStep = when { scale < 8f * density -> 5.0; scale < 18f * density -> 2.0; else -> 1.0 }
        val x0 = floor((-originX / scale) / gridStep).toInt()
        val x1 = ceil(((width - originX) / scale) / gridStep).toInt()
        for (i in x0..min(x1, x0 + 300)) canvas.drawLine(sx(i * gridStep), 0f, sx(i * gridStep), height.toFloat(), pen)
        val y0 = floor(((originY - height) / scale) / gridStep).toInt()
        val y1 = ceil((originY / scale) / gridStep).toInt()
        for (i in y0..min(y1, y0 + 300)) canvas.drawLine(0f, sy(i * gridStep), width.toFloat(), sy(i * gridStep), pen)
        val gridLabel = "격자 ${formatGeometry(gridStep)} cm"
        pen.textSize = 12f * density
        label(gridLabel, width - pen.measureText(gridLabel) - 8f * density, height - 9f * density)
        for (circle in scene.circles) {
            val center = point(circle.centerPointId) ?: continue
            geometryStroke(circle.colorArgb ?: CIRCLE, circle.id in selectedIds, 1.6f) {
                canvas.drawCircle(sx(center.x), sy(center.y), (circle.radius * scale).toFloat(), pen)
            }
        }
        for (segment in scene.segments) {
            val a = point(segment.startPointId) ?: continue
            val b = point(segment.endPointId) ?: continue
            geometryStroke(segment.colorArgb ?: INK, segment.id in selectedIds, 1.8f) {
                canvas.drawLine(sx(a.x), sy(a.y), sx(b.x), sy(b.y), pen)
            }
        }
        annotationHits = annotations.draw(canvas, scene, selectedIds, selectedMeasurementId, selectedConstraintId, scale, ::sx, ::sy)
        for (p in scene.points) {
            val fixed = scene.constraints.any { it.enabled && it.type == ConstraintType.FIXED_POINT && p.id in it.entityIds }
            pen.reset(); pen.isAntiAlias = true; pen.style = Paint.Style.FILL
            if (p.id in selectedIds) { pen.color = SELECTION_HALO; canvas.drawCircle(sx(p.x), sy(p.y), 9f * density, pen) }
            pen.color = p.colorArgb ?: INK
            canvas.drawCircle(sx(p.x), sy(p.y), 3.5f * density, pen)
            if (fixed) {
                pen.style = Paint.Style.STROKE; pen.strokeWidth = density
                canvas.drawRect(sx(p.x) - 6 * density, sy(p.y) - 6 * density, sx(p.x) + 6 * density, sy(p.y) + 6 * density, pen)
            }
            label(p.label.ifBlank { "점" }, sx(p.x) + 7 * density, sy(p.y) - 7 * density, p.colorArgb ?: INK)
        }
        firstAnchor?.let { stored ->
            val it = stored.pointId?.let(::point)?.let { p -> stored.copy(x = p.x, y = p.y) } ?: stored
            pen.style = Paint.Style.STROKE; pen.strokeWidth = 2 * density; pen.color = SELECTED
            pen.pathEffect = DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
            canvas.drawCircle(sx(it.x), sy(it.y), 10 * density, pen)
            snapPreview?.let { end ->
                if (tool == ConstructionTool.CIRCLE) canvas.drawCircle(sx(it.x), sy(it.y), (hypot(end.x - it.x, end.y - it.y) * scale).toFloat(), pen)
                else canvas.drawLine(sx(it.x), sy(it.y), sx(end.x), sy(end.y), pen)
            }
            pen.pathEffect = null
        }
        snapPreview?.takeIf { it.snapLabel.isNotEmpty() }?.let { snap ->
            pen.reset(); pen.isAntiAlias = true; pen.style = Paint.Style.STROKE; pen.strokeWidth = 1.5f * density; pen.color = 0xFF278476.toInt()
            val x = sx(snap.x); val y = sy(snap.y)
            canvas.drawCircle(x, y, 7 * density, pen)
            canvas.drawLine(x - 10 * density, y, x + 10 * density, y, pen)
            canvas.drawLine(x, y - 10 * density, x, y + 10 * density, pen)
        }
        drawingCanvas = null
    }

    private inline fun geometryStroke(color: Int, selected: Boolean, width: Float, draw: () -> Unit) {
        pen.reset(); pen.isAntiAlias = true; pen.style = Paint.Style.STROKE
        if (selected) { pen.color = SELECTION_HALO; pen.strokeWidth = (width + 8) * density; draw() }
        pen.color = color; pen.strokeWidth = width * density; draw()
    }

    private fun nearestPoint(x: Float, y: Float): GeometryPoint? = scene.points
        .filter { hypot(sx(it.x) - x, sy(it.y) - y) <= 18f * density }
        .minByOrNull { hypot(sx(it.x) - x, sy(it.y) - y) }

    private fun anchor(x: Float, y: Float): ConstructionAnchor {
        val world = world(x, y)
        val proposal = ConstructionSnapper.resolve(scene, world.x, world.y, 18.0 * density / scale, snapEnabled)
        // The circle's second tap supplies a radius coordinate, not a persistent rim point.
        // Its center may be attached, but a circumference position must not promise incidence.
        return if (tool == ConstructionTool.CIRCLE && firstAnchor != null) proposal.copy(
            pointId = null, lineIds = emptyList(),
            snapLabel = if (proposal.snapLabel.isNotEmpty()) "반지름 위치 맞춤 · 조건 없음" else "",
        ) else proposal
    }
    private fun hit(x: Float, y: Float): String? {
        // Repeated taps cycle overlapping points so separately created endpoints remain selectable.
        val points = scene.points.filter { hypot(sx(it.x) - x, sy(it.y) - y) <= 18 * density }
        if (points.isNotEmpty()) return points.firstOrNull { it.id !in selectedIds }?.id ?: points.first().id
        val tolerance = 12 * density
        scene.segments.minByOrNull { s ->
            val a = point(s.startPointId); val b = point(s.endPointId)
            if (a == null || b == null) Float.MAX_VALUE else segmentDistance(x, y, sx(a.x), sy(a.y), sx(b.x), sy(b.y))
        }?.let { s ->
            val a = point(s.startPointId)!!; val b = point(s.endPointId)!!
            if (segmentDistance(x, y, sx(a.x), sy(a.y), sx(b.x), sy(b.y)) <= tolerance) return s.id
        }
        return scene.circles.filter { c -> point(c.centerPointId)?.let { abs(hypot(sx(it.x) - x, sy(it.y) - y) - c.radius * scale) <= tolerance } == true }
            .minByOrNull { c -> val p = point(c.centerPointId)!!; abs(hypot(sx(p.x) - x, sy(p.y) - y) - c.radius * scale) }?.id
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            cancelDrag(); hadMultiTouch = true
            val x = (event.getX(0) + event.getX(1)) / 2; val y = (event.getY(0) + event.getY(1)) / 2
            if (event.actionMasked == MotionEvent.ACTION_MOVE && panning) { originX += x - lastPan.x; originY += y - lastPan.y; invalidate() }
            lastPan = PointF(x, y); panning = true
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                down = PointF(event.x, event.y); lastPan = down
                val downWorld = world(event.x, event.y)
                pointerStartWorld = ConstructionVector(downWorld.x, downWorld.y)
                val chosenPoint = if (editable && tool == ConstructionTool.SELECT) {
                    scene.points.filter { it.id in selectedIds && hypot(sx(it.x) - event.x, sy(it.y) - event.y) <= 18f * density }
                        .minByOrNull { hypot(sx(it.x) - event.x, sy(it.y) - event.y) }
                        ?: nearestPoint(event.x, event.y)
                } else null
                tappedAnnotation = if (editable && tool == ConstructionTool.SELECT &&
                    (chosenPoint == null || (chosenPoint.id !in selectedIds && hypot(sx(chosenPoint.x) - event.x, sy(chosenPoint.y) - event.y) > 10 * density))) {
                    annotationHits.lastOrNull { it.bounds.contains(event.x, event.y) }
                } else null
                movingPoint = if (tappedAnnotation == null) chosenPoint?.id else null
                pointGrabOffset = chosenPoint?.let { ConstructionVector(it.x - downWorld.x, it.y - downWorld.y) } ?: ConstructionVector(0.0, 0.0)
                movingMeasurement = tappedAnnotation?.takeIf { it.kind == ConstructionAnnotationKind.MEASUREMENT }?.id
                movingMeasurement?.let { id -> scene.measurements.firstOrNull { it.id == id }?.let {
                    measurementStartOffset = ConstructionVector(it.offsetX, it.offsetY)
                } }
                if (editable && tool != ConstructionTool.SELECT) { snapPreview = anchor(event.x, event.y); publishHint(); invalidate() }
                dragging = false; panning = false; hadMultiTouch = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hadMultiTouch) return true
                if (hypot(event.x - down.x, event.y - down.y) > touchSlop) {
                    if (movingMeasurement != null && editable) {
                        val offset = measurementOffset(event.x, event.y)
                        if (!dragging) {
                            dragging = true
                            onMeasurementDrag(movingMeasurement!!, measurementStartOffset.x, measurementStartOffset.y, ConstructionDragPhase.START)
                            selectedMeasurementId = movingMeasurement
                        }
                        onMeasurementDrag(movingMeasurement!!, offset.x, offset.y, ConstructionDragPhase.MOVE)
                    } else if (movingPoint != null && editable) {
                        val p = pointDragTarget(event.x, event.y)
                        if (!dragging) { dragging = true; onDragPoint(movingPoint!!, p.x, p.y, ConstructionDragPhase.START) }
                        onDragPoint(movingPoint!!, p.x, p.y, ConstructionDragPhase.MOVE)
                    } else if (tool == ConstructionTool.SELECT && tappedAnnotation == null) {
                        panning = true; originX += event.x - lastPan.x; originY += event.y - lastPan.y; invalidate()
                        annotationHits = emptyList()
                    }
                }
                if (editable && tool != ConstructionTool.SELECT) { snapPreview = anchor(event.x, event.y); publishHint(); invalidate() }
                lastPan = PointF(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (hadMultiTouch) { hadMultiTouch = false; panning = false; return true }
                if (dragging && movingMeasurement != null) {
                    val offset = measurementOffset(event.x, event.y)
                    onMeasurementDrag(movingMeasurement!!, offset.x, offset.y, ConstructionDragPhase.END)
                } else if (dragging && movingPoint != null) {
                    val p = pointDragTarget(event.x, event.y); onDragPoint(movingPoint!!, p.x, p.y, ConstructionDragPhase.END)
                } else if (!panning && editable) {
                    performClick()
                    when (tool) {
                        ConstructionTool.SELECT -> {
                            val annotation = tappedAnnotation
                            if (annotation != null) {
                                if (annotation.kind == ConstructionAnnotationKind.MEASUREMENT) {
                                    selectedMeasurementId = annotation.id; onMeasurementSelected(annotation.id)
                                } else {
                                    selectedConstraintId = annotation.id; onConstraintSelected(annotation.id)
                                }
                            } else {
                                val found = hit(event.x, event.y)
                                selectedMeasurementId = null; selectedConstraintId = null
                                selectedIds = if (found == null) emptySet() else if (found in selectedIds) selectedIds - found else selectedIds + found
                                onSelectionChanged(selectedIds)
                            }
                        }
                        ConstructionTool.POINT -> onPoint(anchor(event.x, event.y))
                        ConstructionTool.SEGMENT, ConstructionTool.CIRCLE -> {
                            val end = anchor(event.x, event.y); val start = firstAnchor
                            if (start == null) firstAnchor = end else if (hypot(end.x - start.x, end.y - start.y) >= .02) {
                                firstAnchor = null
                                if (tool == ConstructionTool.SEGMENT) onSegment(start, end) else onCircle(start, hypot(end.x - start.x, end.y - start.y))
                            }
                            invalidate()
                        }
                    }
                }
                movingPoint = null; movingMeasurement = null; tappedAnnotation = null; dragging = false; panning = false
                snapPreview = null; publishHint(); invalidate()
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { cancelDrag(); parent.requestDisallowInterceptTouchEvent(false); return true }
        }
        return true
    }

    fun cancelDrag() {
        if (dragging) movingPoint?.let { id -> point(id)?.let { onDragPoint(id, it.x, it.y, ConstructionDragPhase.CANCEL) } }
        if (dragging) movingMeasurement?.let { onMeasurementDrag(it, measurementStartOffset.x, measurementStartOffset.y, ConstructionDragPhase.CANCEL) }
        movingPoint = null; movingMeasurement = null; tappedAnnotation = null; dragging = false; snapPreview = null; publishHint(); invalidate()
    }

    private fun pointDragTarget(x: Float, y: Float): ConstructionVector {
        val p = world(x, y)
        return ConstructionVector(p.x, p.y) + pointGrabOffset
    }
    private fun measurementOffset(x: Float, y: Float): ConstructionVector {
        val p = world(x, y)
        return measurementStartOffset + ConstructionVector(p.x, p.y) - pointerStartWorld
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (editable && tool != ConstructionTool.SELECT) {
            snapPreview = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null else anchor(event.x, event.y)
            publishHint(); invalidate(); return true
        }
        return super.onHoverEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        private val INK = Color.rgb(44, 59, 72)
        private val SELECTED = Color.rgb(24, 110, 198)
        private const val SELECTION_HALO = 0x454776C5
        private val CIRCLE = Color.rgb(57, 123, 112)
        internal fun segmentDistance(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay; val norm = dx * dx + dy * dy
            val t = if (norm == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / norm).coerceIn(0f, 1f)
            return hypot(x - ax - t * dx, y - ay - t * dy)
        }
    }
}

internal fun formatGeometry(value: Double): String = String.format(Locale.US, "%.4f", value)
    .trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
