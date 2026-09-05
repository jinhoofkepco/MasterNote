package com.studyink.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryCircle
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
import kotlin.math.abs
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

/** Real controls, a worker-backed editor and durable scene storage; never an attached device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h700dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConstructionInspectorUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var store: ConstructionSceneStore
    private lateinit var target: ConstructionTarget
    private val editors = mutableListOf<ConstructionEditorView>()

    @Before fun setup() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        store = ConstructionSceneStore(File(temporary.root, "inspector-scenes"))
        target = ConstructionTarget("inspector-book", 0, 1, UUID.randomUUID().toString())
    }

    @After fun cleanup() {
        editors.forEach(ConstructionEditorView::closeEditor)
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `phone and tablet inspectors remain identical while dimension text geometry and camera change`() {
        for ((device, width, height) in listOf(Triple("phone", 420, 900), Triple("tablet", 1000, 700))) {
            save(constrainedScene())
            val editor = open(width, height)
            val canvas = canvas(editor)
            val host = SharedMemoCanvasHost(activity)
            val ink = FrameLayout(activity)
            host.addView(ink, FrameLayout.LayoutParams(-1, -1)); host.inkLayer = ink
            editor.attachSharedCanvas(host)
            settle(editor)
            assertTrue(host.fitContent())
            draw(editor)
            val drawingBounds = bounds(canvas)
            val originalLabel = requireNotNull(canvas.constraintScreenBounds("length"))
            tap(canvas, originalLabel.centerX(), originalLabel.centerY())
            settle(editor); draw(editor)
            assertEquals("length", canvas.selectedConstraintId)
            val rectangle = bounds(panel(editor))
            assertFixedFrame(editor)
            val selectedLabel = requireNotNull(canvas.constraintScreenBounds("length"))

            tag(editor, "condition-plus-length").performClick()
            awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.value == 10.0 }
            settle(editor); draw(editor)
            assertEquals(rectangle, bounds(panel(editor)))
            val expandedLabel = requireNotNull(canvas.constraintScreenBounds("length"))
            assertTrue("The fixture really changes the 9.9 to 10 label width", abs(selectedLabel.width() - expandedLabel.width()) > .5f)
            assertEquals(drawingBounds, bounds(canvas))
            assertEquals(drawingBounds, bounds(ink))

            tag(editor, "condition-minus-length").performClick()
            awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.value == 9.9 }
            settle(editor); draw(editor)
            assertEquals(rectangle, bounds(panel(editor)))
            (tag(editor, "condition-value-length") as EditText).setText("10.125")
            tag(editor, "condition-apply-length").performClick()
            awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.value == 10.125 }
            settle(editor); draw(editor)
            assertEquals(rectangle, bounds(panel(editor)))

            (tag(editor, "condition-enabled-length") as CheckBox).performClick()
            awaitReady(editor) { !it.constraints.single { c -> c.id == "length" }.enabled }
            settle(editor); draw(editor)
            assertEquals(rectangle, bounds(panel(editor)))
            val pausedPoints = canvas.scene.points
            tag(editor, "condition-plus-length").performClick()
            awaitReady(editor) { it.constraints.single { c -> c.id == "length" }.value == 10.225 }
            assertEquals(pausedPoints, canvas.scene.points)
            assertFalse(canvas.scene.constraints.single { it.id == "length" }.enabled)
            settle(editor); draw(editor)
            assertEquals(rectangle, bounds(panel(editor)))

            host.viewport.zoom(.7f, canvas.width * .35f, canvas.height * .65f)
            host.viewport.pan(-47f, 31f)
            canvas.notifyViewportChanged()
            settle(editor); draw(editor)
            assertEquals(rectangle, bounds(panel(editor)))
            for (id in listOf("radius", "angle", "length")) {
                canvas.onConstraintSelected(id)
                settle(editor); draw(editor)
                assertEquals("Changing the inspector subject does not change its outer frame", rectangle, bounds(panel(editor)))
            }
            canvas.onMeasurementSelected("distance-cd")
            settle(editor); draw(editor)
            assertEquals(rectangle, bounds(panel(editor)))
            for (menu in listOf("조건 목록", "조건 추가", "측정")) {
                click(editor, menu); settle(editor)
                assertEquals(rectangle, bounds(panel(editor)))
            }
            click(editor, "메뉴 닫기"); settle(editor)
            assertEquals(View.GONE, panel(editor).visibility)
            canvas.onConstraintSelected("length"); settle(editor); draw(editor)
            assertEquals(rectangle, bounds(panel(editor)))
            assertEquals(drawingBounds, bounds(canvas))
            savePreview(editor, "build/outputs/inspector-fixed-$device-qa.png")
            editor.detachSharedCanvas(); editor.closeEditor()
        }
    }

    @Test fun `small viewport clamps the fixed frame and title dragging never moves it`() {
        save(constrainedScene())
        val editor = open(250, 280)
        canvas(editor).onConstraintSelected("length")
        settle(editor)
        assertFixedFrame(editor)
        val rectangle = bounds(panel(editor))
        val title = description(editor, "고정 조절 메뉴 제목")
        drag(title, title.width / 2f, title.height / 2f, 70f, 45f)
        settle(editor)
        assertEquals(rectangle, bounds(panel(editor)))
        assertEquals(0f, panel(editor).translationX, 0f)
        assertEquals(0f, panel(editor).translationY, 0f)
        assertTrue(walk(panel(editor)).filterIsInstance<ScrollView>().single().height < 300)
        canvas(editor).onMeasurementSelected("distance-cd"); settle(editor)
        assertEquals(rectangle, bounds(panel(editor)))
    }

    @Test fun `measured distance radius and angle can become driving conditions without replacing their labels`() {
        val initial = measurementScene()
        save(initial)
        val editor = open()
        val canvas = canvas(editor)
        for (id in listOf("distance-ab", "radius-o", "angle-bac")) {
            val measured = canvas.scene.measurements.single { it.id == id }
            val value = requireNotNull(ConstructionMeasurementGeometry.layout(canvas.scene, measured)).value
            val beforeCount = canvas.scene.constraints.size
            canvas.onMeasurementSelected(id)
            tag(editor, "measurement-fix-current-$id").performClick()
            awaitReady(editor) { it.constraints.size == beforeCount + 1 }
            val condition = canvas.scene.constraints.single { ConstructionMeasurementGeometry.matchesConstraint(canvas.scene, measured, it) }
            assertTrue(condition.enabled)
            assertEquals(value, condition.value!!, 1e-8)
            assertEquals(initial.measurements, canvas.scene.measurements)
            assertEquals(canvas.scene, store.load(target).scene)
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
        }
        assertEquals(initial.points, canvas.scene.points)
        assertTrue(editor.undoEdit())
        awaitReady(editor) { it.constraints.size == 2 }
        assertEquals(initial.measurements, canvas.scene.measurements)
        assertTrue(editor.redoEdit())
        awaitReady(editor) { it.constraints.size == 3 }
        assertEquals(initial.measurements, store.load(target).scene.measurements)
    }

    @Test fun `measurement numeric input drives the selected distance and persists across reopen`() {
        save(measurementScene())
        val editor = open()
        canvas(editor).onMeasurementSelected("distance-cd")
        settle(editor)
        val rectangle = bounds(panel(editor))
        tag(editor, "measurement-fix-value-distance-cd").performClick()
        settle(editor)
        assertEquals(rectangle, bounds(panel(editor)))
        (description(editor, "작도 치수 값") as EditText).setText("7.25")
        click(editor, "적용")
        awaitReady(editor) { it.constraints.singleOrNull()?.value == 7.25 }
        val saved = store.load(target).scene
        val measurement = saved.measurements.single { it.id == "distance-cd" }
        assertEquals(7.25, requireNotNull(ConstructionMeasurementGeometry.layout(saved, measurement)).value, 1e-5)
        assertEquals(measurementScene().measurements, saved.measurements)
        editor.closeEditor()
        val reopened = open()
        assertEquals(saved, canvas(reopened).scene)
    }

    @Test fun `opening the matching paused condition never reenables it or duplicates its measurement`() {
        val initial = measurementScene().copy(constraints = listOf(
            GeometryConstraint("paused-length", ConstraintType.LENGTH, listOf("AB"), value = 12.0, enabled = false),
        ))
        save(initial)
        val editor = open()
        val canvas = canvas(editor)
        val revision = store.load(target).revision
        canvas.onMeasurementSelected("distance-ab")
        assertFalse(walk(editor).any { it.tag == "measurement-fix-current-distance-ab" })
        tag(editor, "measurement-existing-condition-distance-ab").performClick()
        settle(editor)
        assertFalse((tag(editor, "condition-enabled-paused-length") as CheckBox).isChecked)
        assertEquals(initial, store.load(target).scene)
        assertEquals(revision, store.load(target).revision)
        tag(editor, "condition-plus-paused-length").performClick()
        awaitReady(editor) { it.constraints.single().value == 12.1 }
        assertFalse(canvas.scene.constraints.single().enabled)
        assertEquals(initial.points, canvas.scene.points)
        assertEquals(initial.measurements, canvas.scene.measurements)
    }

    @Test fun `two measured angles can be chosen consecutively and remain equal after durable solve`() {
        val initial = angleScene()
        save(initial)
        val editor = open()
        val canvas = canvas(editor)
        canvas.onMeasurementSelected("left")
        tag(editor, "measurement-equal-start-left").performClick()
        assertEquals(View.GONE, panel(editor).visibility)
        canvas.onMeasurementSelected("right")
        tag(editor, "measurement-equal-apply-right").performClick()
        awaitReady(editor) { it.constraints.singleOrNull()?.type == ConstraintType.EQUAL_ANGLE }
        assertEquals(listOf("A", "B", "P", "P", "B", "C"), canvas.scene.constraints.single().entityIds)
        assertMeasurementsEqual(canvas.scene, "left", "right")
        assertEquals(initial.measurements, canvas.scene.measurements)
        assertEquals(canvas.scene, store.load(target).scene)
        assertTrue(editor.undoEdit())
        awaitReady(editor) { it.constraints.isEmpty() }
        assertEquals(initial, store.load(target).scene)
    }

    @Test fun `measured lengths can be paired but a tool change cancels an unfinished pairing`() {
        save(measurementScene())
        val editor = open()
        val canvas = canvas(editor)
        canvas.onMeasurementSelected("distance-ab")
        tag(editor, "measurement-equal-start-distance-ab").performClick()
        click(editor, "점"); click(editor, "선택")
        canvas.onMeasurementSelected("distance-cd")
        assertFalse(walk(editor).any { it.tag == "measurement-equal-apply-distance-cd" })
        assertNotNull(tag(editor, "measurement-equal-start-distance-cd"))
        assertTrue(store.load(target).scene.constraints.isEmpty())
        tag(editor, "measurement-equal-start-distance-cd").performClick()
        canvas.onMeasurementSelected("distance-ab")
        tag(editor, "measurement-equal-apply-distance-ab").performClick()
        awaitReady(editor) { it.constraints.size == 1 }
        assertMeasurementsEqual(canvas.scene, "distance-ab", "distance-cd")
        assertEquals(measurementScene().measurements, canvas.scene.measurements)
        assertEquals(canvas.scene, store.load(target).scene)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a driving label remains selectable as the second equal measurement while active or paused`() {
        for (type in listOf(MeasurementType.DISTANCE, MeasurementType.ANGLE)) {
            for (enabled in listOf(true, false)) {
                val base = if (type == MeasurementType.DISTANCE) measurementScene() else angleScene()
                val firstId = if (type == MeasurementType.DISTANCE) "distance-ab" else "left"
                val secondId = if (type == MeasurementType.DISTANCE) "distance-cd" else "right"
                val second = base.measurements.single { it.id == secondId }
                val current = requireNotNull(ConstructionMeasurementGeometry.layout(base, second)).value
                val driving = GeometryConstraint("second-driving",
                    if (type == MeasurementType.DISTANCE) ConstraintType.LENGTH else ConstraintType.INTERIOR_ANGLE,
                    if (type == MeasurementType.DISTANCE) listOf("CD") else second.entityIds,
                    value = if (enabled) current else current + 2.0, enabled = enabled)
                val initial = base.copy(constraints = listOf(driving))
                save(initial)
                val editor = open()
                val canvas = canvas(editor)
                canvas.fitScene()
                canvas.onMeasurementSelected(firstId)
                tag(editor, "measurement-equal-start-$firstId").performClick()
                settle(editor); draw(editor)
                assertEquals(View.GONE, panel(editor).visibility)
                assertEquals(firstId, canvas.referenceMeasurementId)
                val label = requireNotNull(canvas.constraintScreenBounds(driving.id))
                assertTrue("The real driving label is visible for $type enabled=$enabled",
                    RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()).contains(label.centerX(), label.centerY()))
                val beforeTap = store.load(target)
                tap(canvas, label.centerX(), label.centerY())
                settle(editor)

                assertEquals("The condition owns the visible annotation hit", driving.id, canvas.selectedConstraintId)
                assertEquals("The same label still selects its saved measurement", secondId, canvas.selectedMeasurementId)
                assertEquals(firstId, canvas.referenceMeasurementId)
                assertTrue(canvas.selectedIds.isEmpty())
                assertTrue(tag(editor, "measurement-equal-apply-$secondId").isEnabled)
                assertNotNull(tag(editor, "measurement-existing-condition-$secondId"))
                assertFalse(walk(editor).any { it.tag == "measurement-fix-current-$secondId" })
                assertFixedFrame(editor)
                assertEquals("Selecting a paused or active label never rewrites its scene", initial, store.load(target).scene)
                assertEquals(beforeTap.revision, store.load(target).revision)

                tag(editor, "measurement-equal-apply-$secondId").performClick()
                awaitReady(editor) { it.constraints.size == 2 }
                assertMeasurementsEqual(canvas.scene, firstId, secondId)
                assertEquals("Equality adds a separate relation, preserving the existing value and enabled flag",
                    driving, canvas.scene.constraints.single { it.id == driving.id })
                assertEquals(base.measurements, canvas.scene.measurements)
                assertNull(canvas.referenceMeasurementId)
                assertEquals(canvas.scene, store.load(target).scene)
                editor.closeEditor()
            }
        }
    }

    private fun measurementScene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0, "A"), GeometryPoint("B", 9.9, 0.0, "B"),
            GeometryPoint("C", 0.0, 6.0, "C"), GeometryPoint("D", 6.0, 6.0, "D"), GeometryPoint("O", 14.0, 5.0, "O")),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("AC", "A", "C"), GeometrySegment("CD", "C", "D")),
        circles = listOf(GeometryCircle("circle", "O", 2.0)),
        measurements = listOf(GeometryMeasurement("distance-ab", MeasurementType.DISTANCE, listOf("A", "B")),
            GeometryMeasurement("distance-cd", MeasurementType.DISTANCE, listOf("C", "D")),
            GeometryMeasurement("radius-o", MeasurementType.RADIUS, listOf("circle")),
            GeometryMeasurement("angle-bac", MeasurementType.ANGLE, listOf("B", "A", "C"))),
    )

    private fun constrainedScene() = measurementScene().copy(constraints = listOf(
        GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 9.9),
        GeometryConstraint("radius", ConstraintType.RADIUS, listOf("circle"), value = 2.0),
        GeometryConstraint("angle", ConstraintType.INTERIOR_ANGLE, listOf("B", "A", "C"), value = 90.0),
    ))

    private fun angleScene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 6.0, "A"), GeometryPoint("B", 0.0, 0.0, "B"),
            GeometryPoint("C", 6.0, 0.0, "C"), GeometryPoint("P", 3.0, 2.0, "P")),
        segments = listOf(GeometrySegment("BA", "B", "A"), GeometrySegment("BP", "B", "P"), GeometrySegment("BC", "B", "C")),
        measurements = listOf(GeometryMeasurement("left", MeasurementType.ANGLE, listOf("A", "B", "P")),
            GeometryMeasurement("right", MeasurementType.ANGLE, listOf("P", "B", "C"))),
    )

    private fun save(scene: ConstructionScene) { store.save(store.load(target), scene) }
    private fun open(width: Int = 900, height: Int = 700): ConstructionEditorView {
        val editor = ConstructionEditorView(activity, target, "고정 조절 메뉴", embedded = true,
            store = store, replicaRole = ConstructionReplicaRole.STUDENT).also(editors::add)
        val root = FrameLayout(activity).apply { addView(editor, FrameLayout.LayoutParams(width, height)) }
        activity.setContentView(root); layout(root, width, height); awaitReady(editor); layout(root, width, height)
        return editor
    }
    private fun canvas(editor: View) = walk(editor).filterIsInstance<ConstructionCanvasView>().single()
    private fun panel(editor: View) = tag(editor, "construction-overlay")
    private fun tag(editor: View, value: String): View = walk(editor).singleOrNull { it.tag == value }
        ?: error("Missing tag $value; available=${walk(editor).mapNotNull { it.tag }.toList()}")
    private fun description(editor: View, value: String): View = walk(editor).singleOrNull { it.contentDescription == value }
        ?: error("Missing description $value; available=${walk(editor).mapNotNull { it.contentDescription }.toList()}")
    private fun click(editor: View, label: String) { description(editor, "작도 $label").performClick() }
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
        fail("Inspector editor did not settle: pending=${editor.hasPendingWork}, scene=${canvas(editor).scene}")
    }
    private fun settle(editor: View) {
        repeat(3) { layout(editor.parent as View, editor.width, editor.height); shadowOf(Looper.getMainLooper()).idle() }
        layout(editor.parent as View, editor.width, editor.height)
    }
    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun bounds(view: View): RectF {
        val position = IntArray(2); view.getLocationOnScreen(position)
        return RectF(position[0].toFloat(), position[1].toFloat(), (position[0] + view.width).toFloat(), (position[1] + view.height).toFloat())
    }
    private fun assertFixedFrame(editor: View) {
        val drawing = bounds(canvas(editor)); val inspector = bounds(panel(editor))
        val density = activity.resources.displayMetrics.density
        assertTrue("The inspector is inside the workplane", drawing.contains(inspector))
        assertEquals(drawing.right - 8f * density, inspector.right, 1f)
        assertEquals(drawing.top + 8f * density, inspector.top, 1f)
        assertEquals(minOf(272f * density, drawing.width() - 16f * density), inspector.width(), 1f)
        assertEquals(minOf(300f * density, drawing.height() - 16f * density), inspector.height(), 1f)
    }
    private fun assertMeasurementsEqual(scene: ConstructionScene, first: String, second: String) {
        fun value(id: String) = requireNotNull(ConstructionMeasurementGeometry.layout(scene, scene.measurements.single { it.id == id })).value
        assertEquals(value(first), value(second), 1e-5)
    }
    private fun draw(view: View) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }
    private fun tap(view: View, x: Float, y: Float) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, x, y, 0)
        try { assertTrue(view.dispatchTouchEvent(down)); assertTrue(view.dispatchTouchEvent(up)) }
        finally { down.recycle(); up.recycle() }
    }
    private fun drag(view: View, x: Float, y: Float, dx: Float, dy: Float) {
        val events = listOf(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0),
            MotionEvent.obtain(0, 32, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0),
            MotionEvent.obtain(0, 48, MotionEvent.ACTION_UP, x + dx, y + dy, 0))
        try { events.forEach(view::dispatchTouchEvent) } finally { events.forEach(MotionEvent::recycle) }
    }
    private fun savePreview(view: View, path: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val output = File(path)
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.length() > 5_000)
            println("Fixed inspector native QA: ${output.absolutePath}")
        } finally { bitmap.recycle() }
    }
}
