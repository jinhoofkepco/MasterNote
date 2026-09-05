package com.studyink.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import com.studyink.construction.storage.ConstructionReplicaRole
import com.studyink.construction.storage.ConstructionSceneStore
import com.studyink.construction.storage.ConstructionTarget
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** New relation workflows exercise the real editor and durable scene, never a connected device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h700dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConstructionRelationsEditorTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var access: ConstructionSceneStore
    private lateinit var target: ConstructionTarget
    private val editors = mutableListOf<ConstructionEditorView>()

    @Before fun setup() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        access = ConstructionSceneStore(File(temporary.root, "masternote"))
        target = ConstructionTarget("relations-book", 0, 1, UUID.randomUUID().toString())
    }

    @After fun cleanup() {
        editors.forEach(ConstructionEditorView::closeEditor)
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `square division and inline fraction edits stay in one list then endpoint distance replaces only that position`() {
        val editor = open(420, 900)
        val canvas = canvas(editor)
        val originalBounds = bounds(canvas)
        click(editor, "기본 도형")
        click(editor, "정사각형")
        awaitReady(editor) { it.points.size == 4 && it.segments.size == 4 }
        val originalSegments = canvas.scene.segments
        val line = originalSegments.first()
        select(canvas, line.id)
        click(editor, "조건 추가")
        click(editor, "등분점 만들기 · 원래 선분 유지")
        click(editor, "중점 하나 만들기")
        awaitReady(editor) { it.points.size == 5 }
        assertEquals(originalSegments, canvas.scene.segments)
        val fraction = canvas.scene.constraints.single { it.type == ConstraintType.POINT_FRACTION }
        val point = fraction.entityIds.first()
        assertEquals(1, fraction.numerator); assertEquals(2, fraction.denominator)
        click(editor, "조건 목록")
        val overlay = tag(editor, "construction-overlay")
        tag(editor, "condition-expand-${fraction.id}").performClick()
        (tag(editor, "등분 위치 k") as EditText).setText("2")
        (tag(editor, "등분 수 n") as EditText).setText("3")
        click(editor, "등분 위치 적용")
        awaitReady(editor) { it.constraints.single { c -> c.id == fraction.id }.denominator == 3 }
        assertSame(overlay, tag(editor, "construction-overlay"))
        assertEquals(View.VISIBLE, overlay.visibility)
        assertTrue(description(editor, "작도 조건 목록").isSelected)
        canvas.scene.constraints.forEach { assertNotNull(tag(editor, "condition-row-${it.id}")) }
        val changed = canvas.scene
        assertFraction(changed, point, line.id, 2.0 / 3)
        settle(editor)
        val scroll = walk(overlay).filterIsInstance<ScrollView>().single()
        scroll.scrollTo(0, tag(editor, "condition-row-${fraction.id}").top)
        savePreview(editor, "build/outputs/relations-panel-phone.png")

        (tag(editor, "condition-enabled-${fraction.id}") as CheckBox).performClick()
        awaitReady(editor) { !it.constraints.single { c -> c.id == fraction.id }.enabled }
        val pausedPoints = canvas.scene.points
        (tag(editor, "등분 위치 k") as EditText).setText("1")
        click(editor, "등분 위치 적용")
        awaitReady(editor) { it.constraints.single { c -> c.id == fraction.id }.numerator == 1 }
        assertEquals(pausedPoints, canvas.scene.points)
        assertFalse(canvas.scene.constraints.single { it.id == fraction.id }.enabled)
        assertSame(overlay, tag(editor, "construction-overlay"))
        (tag(editor, "condition-enabled-${fraction.id}") as CheckBox).performClick()
        awaitReady(editor) { it.constraints.single { c -> c.id == fraction.id }.enabled }
        assertFraction(canvas.scene, point, line.id, 1.0 / 3)

        click(editor, "메뉴 닫기")
        select(canvas, point, line.id)
        click(editor, "조건 추가")
        click(editor, "선분 위 위치 · 등분 / 끝점부터 cm")
        walk(editor).filterIsInstance<CheckBox>().single { it.text.contains("부터 재기") }.performClick()
        click(editor, "선택한 끝점부터 거리 (cm)")
        (description(editor, "작도 치수 값") as EditText).setText("4")
        click(editor, "적용")
        awaitReady(editor) { it.constraints.any { c -> c.type == ConstraintType.POINT_DISTANCE } }
        val result = access.load(target).scene
        assertFalse(result.constraints.any { it.type == ConstraintType.POINT_FRACTION })
        val distance = result.constraints.single { it.type == ConstraintType.POINT_DISTANCE }
        assertTrue(distance.fromEnd); assertFalse(distance.allowExtension)
        assertEquals(4.0, distance.value!!, 0.0)
        val endpoint = result.point(line.endPointId)!!; val p = result.point(point)!!
        assertEquals(4.0, hypot(endpoint.x - p.x, endpoint.y - p.y), 1e-4)
        settle(editor)
        assertEquals(originalBounds, bounds(canvas))
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
    }

    @Test fun `division integer and internal ratio inputs reject invalid values and store an exact rational position`() {
        save(lineScene())
        val editor = open(); val canvas = canvas(editor)
        select(canvas, "AB")
        click(editor, "조건 추가"); click(editor, "등분점 만들기 · 원래 선분 유지")
        val count = tag(editor, "전체 등분 수") as EditText
        count.setText("0"); click(editor, "입력한 수로 등분점 만들기")
        assertNotNull(count.error)
        assertEquals(lineScene(), access.load(target).scene)
        count.setText("3"); click(editor, "입력한 수로 등분점 만들기")
        awaitReady(editor) { it.points.size == 6 }
        assertEquals(listOf(1, 2), canvas.scene.constraints.map { it.numerator })
        assertEquals(lineScene().segments, canvas.scene.segments)
        val point = canvas.scene.points.last().id
        select(canvas, point, "AB")
        click(editor, "조건 추가"); click(editor, "선분 위 위치 · 등분 / 끝점부터 cm")
        click(editor, "내분비 m:n으로 지정")
        (tag(editor, "내분비 m") as EditText).setText("2")
        (tag(editor, "내분비 n") as EditText).setText("3")
        click(editor, "내분비 적용")
        awaitReady(editor) { it.constraints.any { c -> c.entityIds.first() == point && c.denominator == 5 } }
        assertFraction(canvas.scene, point, "AB", .4)
        assertEquals(2, canvas.scene.constraints.size)
    }

    @Test fun `length ratio is a multiplier and its numeric editor stays inside the condition list`() {
        save(lineScene())
        val editor = open(); val canvas = canvas(editor)
        select(canvas, "AB", "CD")
        click(editor, "조건 추가"); click(editor, "두 선분 길이 비율")
        val input = description(editor, "작도 치수 값") as EditText
        input.setText("0"); click(editor, "적용")
        assertNotNull(input.error)
        assertTrue(access.load(target).scene.constraints.isEmpty())
        input.setText("1.5"); click(editor, "적용")
        awaitReady(editor) { it.constraints.singleOrNull()?.type == ConstraintType.LENGTH_RATIO }
        val ratio = canvas.scene.constraints.single()
        assertEquals(listOf("AB", "CD"), ratio.entityIds)
        assertEquals(1.5, length(canvas.scene, "AB") / length(canvas.scene, "CD"), 1e-4)
        click(editor, "조건 목록"); tag(editor, "condition-expand-${ratio.id}").performClick()
        val overlay = tag(editor, "construction-overlay")
        tag(editor, "condition-plus-${ratio.id}").performClick()
        awaitReady(editor) { it.constraints.single().value == 1.6 }
        assertSame(overlay, tag(editor, "construction-overlay"))
        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals(1.6, length(canvas.scene, "AB") / length(canvas.scene, "CD"), 1e-4)
    }

    @Test fun `three point angle can be matched to a measurement label sharing its vertex and ray`() {
        save(angleScene())
        val editor = open(); val canvas = canvas(editor)
        select(canvas, "A", "B", "P")
        click(editor, "조건 추가"); click(editor, "이 각과 다른 각을 같게…")
        assertTrue(canvas.selectedIds.isEmpty())
        assertTrue((description(editor, "현재 작도 동작") as TextView).text.contains("다른 각"))
        canvas.onMeasurementSelected("right")
        click(editor, "기준 각과 이 각을 같게")
        awaitReady(editor) { it.constraints.singleOrNull()?.type == ConstraintType.EQUAL_ANGLE }
        val relation = access.load(target).scene.constraints.single()
        assertEquals(listOf("A", "B", "P", "P", "B", "C"), relation.entityIds)
        assertEquals(2, canvas.scene.measurements.size)
        assertFalse((description(editor, "현재 작도 동작") as TextView).text.contains("다른 각"))
        val arcs = ConstructionMeasurementGeometry.equalAngleLayouts(canvas.scene, relation)
        assertEquals(arcs[0].value, arcs[1].value, 1e-5)
        assertTrue(editor.undoEdit())
        awaitReady(editor) { it.constraints.isEmpty() }
        assertEquals(angleScene(), access.load(target).scene)
    }

    @Test fun `same angle cannot match itself and changing tools cancels the staged angle`() {
        save(angleScene())
        val editor = open(); val canvas = canvas(editor)
        canvas.onMeasurementSelected("left"); tag(editor, "measurement-equal-start-left").performClick()
        canvas.onMeasurementSelected("left")
        assertFalse("A measurement cannot be paired with itself", walk(editor).any { it.tag == "measurement-equal-apply-left" })
        awaitReady(editor)
        assertTrue(access.load(target).scene.constraints.isEmpty())
        assertTrue((description(editor, "현재 작도 동작") as TextView).text.contains("다른 측정"))
        click(editor, "선분")
        assertFalse((description(editor, "현재 작도 동작") as TextView).text.contains("다른 측정"))
        click(editor, "선택")
        select(canvas, "P", "B", "C"); click(editor, "조건 추가")
        assertNotNull(description(editor, "작도 이 각과 다른 각을 같게…"))
        assertTrue(access.load(target).scene.constraints.isEmpty())
    }

    @Test fun `changing drawing color while custom division is open dismisses the stale menu`() {
        save(lineScene())
        val editor = open(); val canvas = canvas(editor)
        select(canvas, "AB")
        click(editor, "조건 추가"); click(editor, "등분점 만들기 · 원래 선분 유지")
        val oldApply = description(editor, "작도 입력한 수로 등분점 만들기")
        description(editor, "도형 색 파랑").performClick()
        awaitReady(editor) { it.segment("AB")!!.colorArgb != null }
        assertEquals(View.GONE, tag(editor, "construction-overlay").visibility)
        val saved = access.load(target)
        oldApply.performClick()
        awaitReady(editor)
        val unchanged = access.load(target)
        assertEquals(saved.target, unchanged.target)
        assertEquals(saved.revision, unchanged.revision)
        assertEquals(saved.scene, unchanged.scene)
        click(editor, "조건 추가"); click(editor, "등분점 만들기 · 원래 선분 유지")
        click(editor, "중점 하나 만들기")
        awaitReady(editor) { it.points.size == 5 }
        assertEquals(1, canvas.scene.constraints.size)
    }

    @Test fun `quick midpoint cm and internal ratio preserve an existing paused position identity`() {
        val initial = lineScene().let { scene -> scene.copy(
            points = scene.points + GeometryPoint("P", 4.0, 0.0, "P"),
            constraints = listOf(GeometryConstraint("position", ConstraintType.POINT_FRACTION,
                listOf("P", "AB"), enabled = false, numerator = 1, denominator = 3)),
        ) }
        save(initial)
        val editor = open(); val canvas = canvas(editor)
        fun openPosition() {
            select(canvas, "P", "AB")
            click(editor, "조건 추가"); click(editor, "선분 위 위치 · 등분 / 끝점부터 cm")
        }
        openPosition(); click(editor, "중점 · 1/2")
        awaitReady(editor) { it.constraints.single().denominator == 2 }
        assertEquals("position", canvas.scene.constraints.single().id)
        assertFalse(canvas.scene.constraints.single().enabled)
        assertEquals(initial.points, canvas.scene.points)
        openPosition(); click(editor, "선택한 끝점부터 거리 (cm)")
        (description(editor, "작도 치수 값") as EditText).setText("3")
        click(editor, "적용")
        awaitReady(editor) { it.constraints.single().type == ConstraintType.POINT_DISTANCE }
        assertEquals("position", canvas.scene.constraints.single().id)
        assertFalse(canvas.scene.constraints.single().enabled)
        assertEquals(initial.points, canvas.scene.points)
        openPosition(); click(editor, "내분비 m:n으로 지정")
        click(editor, "내분비 적용")
        awaitReady(editor) { it.constraints.single().type == ConstraintType.POINT_FRACTION }
        assertEquals("position", canvas.scene.constraints.single().id)
        assertFalse(canvas.scene.constraints.single().enabled)
        assertEquals(1, canvas.scene.constraints.single().numerator)
        assertEquals(3, canvas.scene.constraints.single().denominator)
        assertEquals(initial.points, canvas.scene.points)
    }

    @Test fun `endpoint and extension options expand within the condition list and keep a paused point still`() {
        val initial = lineScene().let { scene -> scene.copy(
            points = scene.points + GeometryPoint("P", 4.0, 0.0, "P"),
            constraints = listOf(GeometryConstraint("position", ConstraintType.POINT_DISTANCE,
                listOf("P", "AB"), value = 4.0, enabled = false)),
        ) }
        save(initial)
        val editor = open(); val canvas = canvas(editor)
        click(editor, "조건 목록"); tag(editor, "condition-expand-position").performClick()
        val overlay = tag(editor, "construction-overlay")
        (tag(editor, "condition-from-end-position") as CheckBox).performClick()
        awaitReady(editor) { it.constraints.single().fromEnd }
        assertSame(overlay, tag(editor, "construction-overlay"))
        assertEquals(View.VISIBLE, overlay.visibility)
        assertTrue(description(editor, "작도 조건 목록").isSelected)
        (tag(editor, "condition-extension-position") as CheckBox).performClick()
        awaitReady(editor) { it.constraints.single().allowExtension }
        assertSame(overlay, tag(editor, "construction-overlay"))
        assertEquals(View.VISIBLE, overlay.visibility)
        assertNotNull(tag(editor, "condition-row-position"))
        assertNotNull(tag(editor, "condition-controls-position"))
        assertEquals(initial.points, canvas.scene.points)
        assertFalse(canvas.scene.constraints.single().enabled)
        assertEquals(4.0, canvas.scene.constraints.single().value!!, 0.0)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `tapping the second equal angle caption selects that angle with a fixed upper right inspector`() {
        val scene = ConstructionScene(
            points = listOf(GeometryPoint("A", 0.0, 5.0, "A"), GeometryPoint("B", 0.0, 0.0, "B"), GeometryPoint("C", 5.0, 0.0, "C"),
                GeometryPoint("D", 15.0, 5.0, "D"), GeometryPoint("E", 15.0, 0.0, "E"), GeometryPoint("F", 20.0, 0.0, "F")),
            segments = listOf(GeometrySegment("BA", "B", "A"), GeometrySegment("BC", "B", "C"),
                GeometrySegment("ED", "E", "D"), GeometrySegment("EF", "E", "F")),
            constraints = listOf(GeometryConstraint("equal", ConstraintType.EQUAL_ANGLE, listOf("A", "B", "C", "D", "E", "F"))),
        )
        save(scene)
        val editor = open(420, 900); val canvas = canvas(editor)
        val bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        try {
            canvas.draw(Canvas(bitmap))
            val origin = canvas.pointScreenPosition("B")!!
            val xPoint = canvas.pointScreenPosition("C")!!
            val scale = (xPoint.x - origin.x) / 5f
            val hits = ConstructionAnnotationRenderer(activity.resources.displayMetrics.density).draw(Canvas(bitmap), scene,
                emptySet(), null, null, scale, { origin.x + it.toFloat() * scale }, { origin.y - it.toFloat() * scale })
            assertEquals(2, hits.size)
            val second = hits.last()
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, second.visualBounds.centerX(), second.visualBounds.centerY(), 0)
            val up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, second.visualBounds.centerX(), second.visualBounds.centerY(), 0)
            try { assertTrue(canvas.dispatchTouchEvent(down)); assertTrue(canvas.dispatchTouchEvent(up)) }
            finally { down.recycle(); up.recycle() }
            settle(editor)
            assertEquals("equal", canvas.selectedConstraintId)
            val anchored = canvas.constraintScreenBounds("equal")!!
            assertEquals(second.bounds.centerX(), anchored.centerX(), .02f)
            assertEquals(second.bounds.centerY(), anchored.centerY(), .02f)
            val overlay = tag(editor, "construction-overlay")
            assertEquals(View.VISIBLE, overlay.visibility)
            val density = activity.resources.displayMetrics.density
            assertEquals(canvas.width - 8f * density, overlay.right.toFloat(), 1f)
            assertEquals(8f * density, overlay.top.toFloat(), 1f)
            assertEquals(272f * density, overlay.width.toFloat(), 1f)
            assertEquals(300f * density, overlay.height.toFloat(), 1f)
            assertNotNull(tag(editor, "condition-controls-equal"))
            assertEquals(scene, access.load(target).scene)
            savePreview(editor, "build/outputs/relations-equal-angle-phone.png")
        } finally { bitmap.recycle() }
    }

    private fun lineScene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0, "A"), GeometryPoint("B", 12.0, 0.0, "B"),
            GeometryPoint("C", 0.0, 5.0, "C"), GeometryPoint("D", 6.0, 5.0, "D")),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("CD", "C", "D")),
    )
    private fun angleScene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 6.0, "A"), GeometryPoint("B", 0.0, 0.0, "B"),
            GeometryPoint("C", 6.0, 0.0, "C"), GeometryPoint("P", 3.0, 3.0, "P")),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("BP", "B", "P"), GeometrySegment("BC", "B", "C")),
        measurements = listOf(GeometryMeasurement("left", MeasurementType.ANGLE, listOf("A", "B", "P")),
            GeometryMeasurement("right", MeasurementType.ANGLE, listOf("P", "B", "C"))),
    )
    private fun save(scene: ConstructionScene) { access.save(access.load(target), scene) }
    private fun open(width: Int = 900, height: Int = 700): ConstructionEditorView {
        val editor = ConstructionEditorView(activity, target, "관계 작도", embedded = true,
            store = access, replicaRole = ConstructionReplicaRole.STUDENT).also(editors::add)
        val root = FrameLayout(activity).apply { addView(editor, FrameLayout.LayoutParams(width, height)) }
        activity.setContentView(root); layout(root, width, height); awaitReady(editor); layout(root, width, height)
        return editor
    }
    private fun select(canvas: ConstructionCanvasView, vararg ids: String) {
        canvas.selectedIds = ids.toSet(); canvas.onSelectionChanged(canvas.selectedIds)
    }
    private fun click(editor: View, label: String) { description(editor, "작도 $label").performClick() }
    private fun canvas(editor: View) = walk(editor).filterIsInstance<ConstructionCanvasView>().single()
    private fun tag(editor: View, value: String): View = walk(editor).singleOrNull { it.tag == value }
        ?: error("Missing tag $value")
    private fun description(editor: View, value: String): View = walk(editor).singleOrNull { it.contentDescription == value }
        ?: error("Missing description $value; available=${walk(editor).mapNotNull { it.contentDescription }.toList()}")
    private fun walk(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(walk(view.getChildAt(index)))
    }
    private fun awaitReady(editor: ConstructionEditorView, predicate: (ConstructionScene) -> Boolean = { true }) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!editor.hasPendingWork && canvas(editor).editable && predicate(canvas(editor).scene)) return
            Thread.sleep(10)
        }
        fail("Relation editor did not settle: pending=${editor.hasPendingWork}, scene=${canvas(editor).scene}")
    }
    private fun settle(editor: View) { repeat(3) { layout(editor.parent as View, editor.width, editor.height); shadowOf(Looper.getMainLooper()).idle() } }
    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun savePreview(view: View, path: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val output = File(path)
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.length() > 5_000)
            println("Relation editor native layout QA: ${output.absolutePath}")
        } finally { bitmap.recycle() }
    }
    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)
    private fun assertFraction(scene: ConstructionScene, pointId: String, segmentId: String, amount: Double) {
        val segment = scene.segment(segmentId)!!; val a = scene.point(segment.startPointId)!!; val b = scene.point(segment.endPointId)!!
        val point = scene.point(pointId)!!
        assertEquals(a.x + (b.x - a.x) * amount, point.x, 1e-4)
        assertEquals(a.y + (b.y - a.y) * amount, point.y, 1e-4)
    }
    private fun length(scene: ConstructionScene, segmentId: String): Double {
        val segment = scene.segment(segmentId)!!; val a = scene.point(segment.startPointId)!!; val b = scene.point(segment.endPointId)!!
        return hypot(a.x - b.x, a.y - b.y)
    }
}
