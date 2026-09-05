package com.studyink.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryLineStyle
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.storage.ConstructionReplicaRole
import com.studyink.construction.storage.ConstructionSceneStore
import com.studyink.construction.storage.ConstructionTarget
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
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

/** Real editor controls and durable storage, with no device installation or reflection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h700dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConstructionConditionInteractionTest {
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
        target = ConstructionTarget("book", 2, 1, UUID.randomUUID().toString())
    }

    @After fun cleanup() {
        editors.forEach(ConstructionEditorView::closeEditor)
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun `palette recolors only selected entities and undo does not change new drawing defaults`() {
        val initial = example()
        save(initial)
        val editor = open()
        val canvas = canvas(editor)
        select(canvas, "A", "AB", "circle")
        description(editor, "도형 색 파랑").performClick()
        val blue = Color.rgb(53, 113, 176)
        awaitReady(editor) { it.segment("AB")!!.colorArgb == blue }
        val changed = access.load(target).scene
        assertEquals(blue, changed.point("A")!!.colorArgb)
        assertEquals(blue, changed.circle("circle")!!.colorArgb)
        assertNull(changed.point("B")!!.colorArgb)
        assertNull(changed.segment("AC")!!.colorArgb)
        assertEquals(initial.points.map { it.x to it.y }, changed.points.map { it.x to it.y })
        assertEquals(initial.constraints, changed.constraints)
        assertTrue(editor.undoEdit())
        awaitReady(editor) { it == initial }

        canvas.clearSelection()
        val revision = access.load(target).revision
        description(editor, "도형 색 주황").performClick()
        awaitReady(editor)
        assertEquals("Choosing the next color must not rewrite the scene", revision, access.load(target).revision)
        canvas.onSegment(ConstructionAnchor(15.0, 2.0), ConstructionAnchor(19.0, 4.0))
        awaitReady(editor) { it.segments.size == 3 }
        val orange = Color.rgb(194, 91, 64)
        assertEquals(orange, canvas.scene.segments.last().colorArgb)
        assertEquals(listOf(orange, orange), canvas.scene.points.takeLast(2).map { it.colorArgb })
        assertEquals(initial.segments, canvas.scene.segments.take(2))
    }

    @Test fun `stroke icons restyle selected lines and circles and persist the default on new shapes`() {
        val initial = example()
        save(initial)
        val editor = open()
        val canvas = canvas(editor)
        select(canvas, "AB", "circle")
        description(editor, "작도 점선").performClick()
        awaitReady(editor) { it.segment("AB")!!.lineStyle == GeometryLineStyle.DASHED }
        assertEquals(GeometryLineStyle.DASHED, canvas.scene.circle("circle")!!.lineStyle)
        assertEquals(GeometryLineStyle.SOLID, canvas.scene.segment("AC")!!.lineStyle)
        assertEquals(initial.points, canvas.scene.points)
        assertEquals(initial.constraints, canvas.scene.constraints)
        assertTrue(editor.undoEdit())
        awaitReady(editor) { it == initial }

        canvas.clearSelection()
        val revision = access.load(target).revision
        description(editor, "작도 점점선").performClick()
        awaitReady(editor)
        assertEquals(revision, access.load(target).revision)
        canvas.onSegment(ConstructionAnchor(15.0, 2.0), ConstructionAnchor(19.0, 4.0))
        awaitReady(editor) { it.segments.size == 3 }
        canvas.onCircle(ConstructionAnchor(18.0, 8.0), 2.5)
        awaitReady(editor) { it.circles.size == 2 }
        assertEquals(GeometryLineStyle.DOTTED, canvas.scene.segments.last().lineStyle)
        assertEquals(GeometryLineStyle.DOTTED, canvas.scene.circles.last().lineStyle)
        val saved = access.load(target).scene
        editor.closeEditor()
        val reopened = open()
        assertEquals(saved, canvas(reopened).scene)
        select(canvas(reopened), saved.segments.last().id, saved.circles.last().id)
        description(reopened, "작도 실선").performClick()
        awaitReady(reopened) { it.segments.last().lineStyle == GeometryLineStyle.SOLID }
        assertEquals(GeometryLineStyle.SOLID, canvas(reopened).scene.circles.last().lineStyle)
    }

    @Test fun `condition titles expand inside the same list and checkbox temporarily disables and restores`() {
        save(example())
        val editor = open()
        val canvasBounds = globalBounds(canvas(editor))
        val overlay = showList(editor)
        tag(editor, "condition-expand-length").performClick()
        settleLayout(editor)
        assertSame(overlay, tag(editor, "construction-overlay"))
        assertEquals(View.VISIBLE, overlay.visibility)
        assertNotNull(tag(editor, "condition-controls-length"))
        assertAllRows(editor)
        assertEquals(canvasBounds, globalBounds(canvas(editor)))

        (tag(editor, "condition-enabled-length") as CheckBox).performClick()
        awaitReady(editor) { !it.constraints.single { c -> c.id == "length" }.enabled }
        settleLayout(editor)
        assertFalse((tag(editor, "condition-enabled-length") as CheckBox).isChecked)
        assertNotNull(tag(editor, "condition-controls-length"))
        assertAllRows(editor)
        assertSame(overlay, tag(editor, "construction-overlay"))
        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals(5.0, constraint(editor, "length").value!!, 0.0)
        assertEquals(3, access.load(target).scene.constraints.size)

        (tag(editor, "condition-enabled-length") as CheckBox).performClick()
        awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.enabled }
        assertTrue((tag(editor, "condition-enabled-length") as CheckBox).isChecked)
        assertAllRows(editor)
        assertEquals(canvasBounds, globalBounds(canvas(editor)))
        description(editor, "작도 메뉴 닫기").performClick()
        settleLayout(editor)
        assertEquals(View.GONE, overlay.visibility)
        assertEquals(canvasBounds, globalBounds(canvas(editor)))
    }

    @Test fun `inline plus minus and direct input adjust saved length angle and radius without dismissing list`() {
        save(example())
        val editor = open()
        val overlay = showList(editor)
        val bounds = globalBounds(canvas(editor))
        for ((id, start, step) in listOf(Triple("length", 5.0, .1), Triple("angle", 90.0, 1.0), Triple("radius", 2.0, .1))) {
            tag(editor, "condition-expand-$id").performClick()
            tag(editor, "condition-plus-$id").performClick()
            awaitReady(editor) { it.constraints.single { c -> c.id == id }.value == start + step }
            assertEquals(start + step, access.load(target).scene.constraints.single { it.id == id }.value!!, 1e-8)
            assertSame(overlay, tag(editor, "construction-overlay"))
            assertEquals(View.VISIBLE, overlay.visibility)
            assertAllRows(editor)
            tag(editor, "condition-minus-$id").performClick()
            awaitReady(editor) { kotlin.math.abs(it.constraints.single { c -> c.id == id }.value!! - start) < 1e-8 }
            assertEquals(start, constraint(editor, id).value!!, 1e-8)
        }
        tag(editor, "condition-expand-length").performClick()
        (tag(editor, "condition-value-length") as EditText).setText("5.5")
        tag(editor, "condition-apply-length").performClick()
        awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.value == 5.5 }
        val scene = canvas(editor).scene
        val a = scene.point("A")!!; val b = scene.point("B")!!
        assertEquals(5.5, hypot(b.x - a.x, b.y - a.y), 1e-4)
        assertEquals(View.VISIBLE, overlay.visibility)
        assertAllRows(editor)
        settleLayout(editor)
        assertEquals(bounds, globalBounds(canvas(editor)))
        assertTrue(editor.undoEdit())
        awaitReady(editor) { kotlin.math.abs(it.constraints.single { c -> c.id == "length" }.value!! - 5.0) < 1e-8 }
        assertEquals("Undo invalidates the old condition controls", View.GONE, overlay.visibility)
    }

    @Test fun `conflicting plus is rolled back without discarding the list or the prior drawing`() {
        val initial = fixedSegment(length = 5.0, enabled = true)
        save(initial)
        val editor = open()
        val overlay = showList(editor)
        tag(editor, "condition-expand-length").performClick()
        val revision = access.load(target).revision
        tag(editor, "condition-plus-length").performClick()
        awaitReady(editor)
        assertEquals(initial, canvas(editor).scene)
        assertEquals(initial, access.load(target).scene)
        assertEquals(revision, access.load(target).revision)
        assertEquals(View.VISIBLE, overlay.visibility)
        assertNotNull(tag(editor, "condition-controls-length"))
        assertEquals(5.0, (tag(editor, "condition-value-length") as EditText).text.toString().toDouble(), 0.0)
        assertFalse(editor.canUndo)
    }

    @Test fun `an impossible disabled condition cannot be silently enabled or lose its saved value`() {
        val initial = fixedSegment(length = 6.0, enabled = false)
        save(initial)
        val editor = open()
        val overlay = showList(editor)
        tag(editor, "condition-expand-length").performClick()
        val revision = access.load(target).revision
        val check = tag(editor, "condition-enabled-length") as CheckBox
        assertFalse(check.isChecked)
        check.performClick()
        awaitReady(editor)
        assertEquals(initial, canvas(editor).scene)
        assertEquals(initial, access.load(target).scene)
        assertEquals(revision, access.load(target).revision)
        assertFalse((tag(editor, "condition-enabled-length") as CheckBox).isChecked)
        assertEquals(6.0, constraint(editor, "length").value!!, 0.0)
        assertEquals(View.VISIBLE, overlay.visibility)
    }

    @Test fun `minus refuses a zero length and angle plus stays within 180 degrees`() {
        val initial = ConstructionScene(
            points = listOf(GeometryPoint("A", 0.0, 0.0), GeometryPoint("B", .1, 0.0), GeometryPoint("C", -1.0, 0.0)),
            segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("AC", "A", "C")),
            constraints = listOf(
                GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = .1),
                GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "AC"), value = 180.0),
            ),
        )
        save(initial)
        val editor = open()
        val overlay = showList(editor)
        tag(editor, "condition-expand-length").performClick()
        val revision = access.load(target).revision
        tag(editor, "condition-minus-length").performClick()
        awaitReady(editor)
        assertEquals("A minus tap cannot collapse a positive segment into zero", initial, canvas(editor).scene)
        assertEquals(revision, access.load(target).revision)
        assertEquals(.1, constraint(editor, "length").value!!, 0.0)
        assertFalse(editor.canUndo)

        tag(editor, "condition-expand-angle").performClick()
        tag(editor, "condition-plus-angle").performClick()
        awaitReady(editor)
        assertEquals(180.0, constraint(editor, "angle").value!!, 0.0)
        assertEquals(initial.points, canvas(editor).scene.points)
        assertEquals(initial, access.load(target).scene)
        assertEquals(View.VISIBLE, overlay.visibility)
    }

    @Test fun `editing a paused target changes only its saved value and never enables or moves geometry`() {
        val initial = fixedSegment(length = 6.0, enabled = false)
        save(initial)
        val editor = open()
        val overlay = showList(editor)
        tag(editor, "condition-expand-length").performClick()
        tag(editor, "condition-plus-length").performClick()
        awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.value == 6.1 }
        assertFalse(constraint(editor, "length").enabled)
        assertFalse((tag(editor, "condition-enabled-length") as CheckBox).isChecked)
        assertEquals(initial.points, canvas(editor).scene.points)
        assertEquals(initial.segments, canvas(editor).scene.segments)

        (tag(editor, "condition-value-length") as EditText).setText("7.25")
        tag(editor, "condition-apply-length").performClick()
        awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.value == 7.25 }
        assertFalse(constraint(editor, "length").enabled)
        val saved = access.load(target).scene
        assertEquals(initial.points, saved.points)
        assertEquals(initial.segments, saved.segments)
        assertEquals(initial.constraints.drop(1), saved.constraints.drop(1))
        assertEquals(7.25, saved.constraints.first().value!!, 0.0)
        assertFalse(saved.constraints.first().enabled)
        assertEquals(View.VISIBLE, overlay.visibility)

        (tag(editor, "condition-value-length") as EditText).setText("0.000001")
        tag(editor, "condition-apply-length").performClick()
        awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.value == .000001 }
        assertEquals(.000001, (tag(editor, "condition-value-length") as EditText).text.toString().toDouble(), 0.0)
        assertFalse(constraint(editor, "length").enabled)
        assertEquals(initial.points, canvas(editor).scene.points)
        assertTrue(editor.undoEdit())
        awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.value == 7.25 }
        assertFalse(constraint(editor, "length").enabled)
        assertEquals(initial.points, canvas(editor).scene.points)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `inline list keeps its checkbox and numeric controls visible over centered colored geometry`() {
        val initial = example().let { scene -> scene.copy(segments = scene.segments.map { segment ->
            if (segment.id == "AB") segment.copy(colorArgb = Color.rgb(53, 113, 176), lineStyle = GeometryLineStyle.DASHED)
            else segment.copy(colorArgb = Color.rgb(194, 91, 64), lineStyle = GeometryLineStyle.DOTTED)
        }) }
        save(initial)
        for ((label, width, height) in listOf(Triple("phone", 420, 900), Triple("tablet", 1000, 700))) {
            val editor = open(width, height)
            val host = SharedMemoCanvasHost(activity)
            val inkLayer = FrameLayout(activity)
            host.addView(inkLayer, FrameLayout.LayoutParams(-1, -1))
            host.inkLayer = inkLayer
            editor.attachSharedCanvas(host)
            settleLayout(editor, width, height)
            assertTrue(host.fitContent())
            val canvas = canvas(editor)
            select(canvas, "AB")
            draw(editor)
            val bounds = globalBounds(canvas)
            val point = requireNotNull(canvas.pointScreenPosition("A"))
            val overlay = showList(editor)
            tag(editor, "condition-expand-length").performClick()
            settleLayout(editor, width, height)
            draw(editor)
            val overlayBounds = globalBounds(overlay)
            for (id in listOf("condition-enabled-length", "condition-minus-length", "condition-plus-length", "condition-value-length")) {
                val control = tag(editor, id)
                assertTrue("$label $id must be shown in the expanded list", control.isShown)
                assertTrue("$label $id must be usable inside the popup", overlayBounds.contains(globalBounds(control)))
                assertTrue(control.isEnabled)
            }
            assertAllRows(editor)
            assertEquals(bounds, globalBounds(canvas))
            assertEquals(globalBounds(canvas), globalBounds(inkLayer))
            assertEquals(point, canvas.pointScreenPosition("A"))
            savePreview(editor, "build/outputs/condition-list-$label-qa.png")
            editor.detachSharedCanvas()
            editor.closeEditor()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `numeric canvas labels open adjacent controls clamped on phone and tablet without moving the shared plane`() {
        save(example())
        for ((label, width, height) in listOf(Triple("phone", 420, 900), Triple("tablet", 1000, 700))) {
            val editor = open(width, height)
            val host = SharedMemoCanvasHost(activity)
            val inkLayer = FrameLayout(activity)
            host.addView(inkLayer, FrameLayout.LayoutParams(-1, -1))
            host.inkLayer = inkLayer
            editor.attachSharedCanvas(host)
            settleLayout(editor, width, height)
            val canvas = canvas(editor)
            // At paper-fit scale horizontal pan is intentionally clamped. Zoom first so the
            // target really can reach the screen edge; assert that position before testing it.
            host.viewport.zoom(8f, canvas.width / 2f, canvas.height / 2f)
            canvas.notifyViewportChanged()
            draw(editor)
            val initialLabel = requireNotNull(canvas.constraintScreenBounds("length"))
            host.viewport.pan(canvas.width - 50f - initialLabel.centerX(), canvas.height - 48f - initialLabel.centerY())
            canvas.notifyViewportChanged()
            draw(editor)
            val dimension = requireNotNull(canvas.constraintScreenBounds("length"))
            assertEquals("$label fixture must reach the right edge", canvas.width - 50f, dimension.centerX(), .1f)
            assertEquals("$label fixture must reach the bottom edge", canvas.height - 48f, dimension.centerY(), .1f)
            val sharedBounds = globalBounds(host)
            val geometryPoint = requireNotNull(canvas.pointScreenPosition("A"))
            tap(canvas, dimension.centerX(), dimension.centerY())
            settleLayout(editor, width, height)
            draw(editor)
            val overlay = tag(editor, "construction-overlay")
            val panelBounds = globalBounds(overlay)
            val canvasBounds = globalBounds(canvas)
            val labelBounds = RectF(dimension).apply { offset(canvasBounds.left, canvasBounds.top) }
            assertEquals(View.VISIBLE, overlay.visibility)
            assertNotNull(tag(editor, "condition-plus-length"))
            assertEquals(sharedBounds, globalBounds(host))
            assertEquals(globalBounds(canvas), globalBounds(inkLayer))
            assertEquals(geometryPoint, canvas.pointScreenPosition("A"))
            assertTrue("$label popup must stay inside the drawing area: $panelBounds in $canvasBounds", canvasBounds.contains(panelBounds))
            val dx = max(0f, max(labelBounds.left - panelBounds.right, panelBounds.left - labelBounds.right))
            val dy = max(0f, max(labelBounds.top - panelBounds.bottom, panelBounds.top - labelBounds.bottom))
            assertTrue("$label adjustment must stay adjacent to its numeric label", hypot(dx.toDouble(), dy.toDouble()) <= 36.0)
            savePreview(editor, "build/outputs/condition-controls-$label-qa.png")
            description(editor, "작도 메뉴 닫기").performClick()
            settleLayout(editor, width, height)
            assertEquals(sharedBounds, globalBounds(host))
            editor.detachSharedCanvas()
            editor.closeEditor()
        }
    }

    private fun example() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0, "A"), GeometryPoint("B", 5.0, 0.0, "B"),
            GeometryPoint("C", 0.0, 3.0, "C"), GeometryPoint("O", 9.0, 5.0, "O")),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("AC", "A", "C")),
        circles = listOf(GeometryCircle("circle", "O", 2.0)),
        constraints = listOf(
            GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 5.0),
            GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "AC"), value = 90.0),
            GeometryConstraint("radius", ConstraintType.RADIUS, listOf("circle"), value = 2.0),
        ),
    )

    private fun fixedSegment(length: Double, enabled: Boolean) = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0, "A"), GeometryPoint("B", 5.0, 0.0, "B")),
        segments = listOf(GeometrySegment("AB", "A", "B")),
        constraints = listOf(
            GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = length, enabled = enabled),
            GeometryConstraint("fixed-A", ConstraintType.FIXED_POINT, listOf("A"), targetX = 0.0, targetY = 0.0),
            GeometryConstraint("fixed-B", ConstraintType.FIXED_POINT, listOf("B"), targetX = 5.0, targetY = 0.0),
        ),
    )

    private fun save(scene: ConstructionScene) { access.save(access.load(target), scene) }

    private fun open(width: Int = 900, height: Int = 700): ConstructionEditorView {
        val editor = ConstructionEditorView(activity, target, "조건 조절 메모", embedded = true,
            store = access, replicaRole = ConstructionReplicaRole.STUDENT).also(editors::add)
        // Pin the synthetic capture surface, as Android's qualified ViewRoot otherwise reapplies
        // its decor/inset-adjusted bounds whenever worker callbacks schedule another traversal.
        val root = FrameLayout(activity).apply { addView(editor, FrameLayout.LayoutParams(width, height)) }
        activity.setContentView(root)
        layout(root, width, height)
        awaitReady(editor)
        layout(root, width, height)
        return editor
    }

    private fun showList(editor: ConstructionEditorView): View {
        description(editor, "작도 조건 목록").performClick()
        settleLayout(editor)
        return tag(editor, "construction-overlay").also { assertEquals(View.VISIBLE, it.visibility) }
    }

    private fun assertAllRows(editor: ConstructionEditorView) {
        listOf("length", "angle", "radius").forEach { id -> assertNotNull(tag(editor, "condition-row-$id")) }
    }

    private fun select(canvas: ConstructionCanvasView, vararg ids: String) {
        canvas.selectedIds = ids.toSet()
        canvas.onSelectionChanged(canvas.selectedIds)
    }

    private fun constraint(editor: ConstructionEditorView, id: String) = canvas(editor).scene.constraints.single { it.id == id }
    private fun canvas(editor: ConstructionEditorView) = walk(editor).filterIsInstance<ConstructionCanvasView>().single()
    private fun tag(editor: View, tag: String): View = walk(editor).singleOrNull { it.tag == tag }
        ?: error("Missing unique tag $tag; present=${walk(editor).mapNotNull { it.tag }.toList()}")
    private fun description(editor: View, description: String): View = walk(editor).singleOrNull { it.contentDescription == description }
        ?: error("Missing unique description $description; present=${walk(editor).mapNotNull { it.contentDescription }.toList()}")
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
        fail("Condition editor did not settle: pending=${editor.hasPendingWork}, editable=${canvas(editor).editable}, scene=${canvas(editor).scene}")
    }

    private fun settleLayout(editor: ConstructionEditorView, width: Int = editor.width, height: Int = editor.height) {
        val root = editor.parent as View
        repeat(3) { layout(root, width, height); shadowOf(Looper.getMainLooper()).idle() }
        layout(root, width, height)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun globalBounds(view: View): RectF {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return RectF(location[0].toFloat(), location[1].toFloat(),
            (location[0] + view.width).toFloat(), (location[1] + view.height).toFloat())
    }

    private fun draw(view: View) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }

    private fun tap(view: View, x: Float, y: Float) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, x, y, 0)
        try {
            assertTrue(view.dispatchTouchEvent(down))
            assertTrue(view.dispatchTouchEvent(up))
        } finally { down.recycle(); up.recycle() }
    }

    private fun savePreview(view: View, path: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val output = File(path)
        try {
            view.draw(Canvas(bitmap))
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.length() > 5_000L)
            println("Condition adjustment native layout QA: ${output.absolutePath}")
        } finally { bitmap.recycle() }
    }
}
