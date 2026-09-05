package com.studyink.reader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import com.studyink.construction.storage.ConstructionSceneStore
import com.studyink.construction.storage.ConstructionTarget
import com.studyink.core.model.MasterNoteDataRootBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Real dialog + worker + durable sidecar tests. No production fields are reflected or replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ConstructionEditorDialogTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var target: ConstructionTarget
    private lateinit var store: ConstructionSceneStore
    private val dialogs = mutableListOf<ConstructionEditorDialog>()

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        target = ConstructionTarget("dialog-test-book", 0, 1, UUID.randomUUID().toString())
        store = ConstructionSceneStore(File(activity.applicationContext.filesDir, "masternote"))
    }

    @After fun tearDown() {
        dialogs.forEach { it.dismiss() }
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test fun `drawing persists and reopening retains the same entities`() {
        val dialog = open()
        val canvas = canvas(dialog)
        canvas.onPoint(ConstructionAnchor(2.0, 3.0))
        awaitReady(dialog) { it.points.size == 1 }
        val a = canvas.scene.points.single()
        canvas.onSegment(ConstructionAnchor(a.x, a.y, a.id), ConstructionAnchor(8.0, 3.0))
        awaitReady(dialog) { it.points.size == 2 && it.segments.size == 1 }
        val durable = store.load(target).scene
        assertEquals(canvas.scene, durable)
        assertEquals(6.0, distance(durable, durable.segments.single().startPointId, durable.segments.single().endPointId), 1e-6)

        dialog.dismiss()
        val reopened = open()
        assertEquals(durable, canvas(reopened).scene)
        assertEquals(2L, store.load(target).revision)
    }

    @Test fun `linked bars example keeps coincidence and lengths after a real drag transaction`() {
        val dialog = open()
        clickMore(dialog, "예제")
        chooseLatestItem(0)
        awaitReady(dialog) { it.points.size == 4 && it.circles.size == 1 }
        val canvas = canvas(dialog)
        val original = canvas.scene
        val r = original.points.single { it.label == "R" }
        val revision = store.load(target).revision
        canvas.onDragPoint(r.id, r.x, r.y, ConstructionDragPhase.START)
        canvas.onDragPoint(r.id, 10.0, 8.0, ConstructionDragPhase.MOVE)
        canvas.onDragPoint(r.id, 10.0, 8.0, ConstructionDragPhase.END)
        assertFalse("A finished gesture must block a second gesture until commit", canvas.editable)
        awaitReady(dialog) { store.load(target).revision == revision + 1 }

        val moved = canvas.scene
        fun id(label: String) = moved.points.single { it.label == label }.id
        assertEquals(10.0, distance(moved, id("O"), id("P")), 1e-5)
        assertEquals(6.0, distance(moved, id("Q"), id("R")), 1e-5)
        assertEquals(0.0, distance(moved, id("P"), id("Q")), 1e-5)
        assertEquals(10.0, moved.point(r.id)!!.x, 2e-3)
        assertEquals(moved, store.load(target).scene)

        click(dialog, "되돌리기")
        awaitReady(dialog) { it == original }
        assertEquals(original, store.load(target).scene)
        click(dialog, "다시")
        awaitReady(dialog) { it == moved }
    }

    @Test fun `trapezoid example and numeric height change show the expected mathematical result`() {
        val dialog = open()
        clickMore(dialog, "예제")
        chooseLatestItem(1)
        awaitReady(dialog) { it.points.size == 6 && it.constraints.size == 11 }
        val canvas = canvas(dialog)
        fun perpendicularLength(): Double {
            val segment = canvas.scene.segments.single { it.label == "ㅁㅂ" }
            return distance(canvas.scene, segment.startPointId, segment.endPointId)
        }
        assertEquals(3.8, perpendicularLength(), 1e-5)

        canvas.selectedIds = setOf(canvas.scene.segments.single { it.label == "ㅁㅂ" }.id)
        canvas.onSelectionChanged(canvas.selectedIds)
        click(dialog, "측정")
        assertTrue(panelText(dialog).contains("3.8"))
        assertNoVisibleAlert()
        clickPanelContaining(dialog, "그림에 표시 ·")
        awaitReady(dialog) { it.measurements.size == 1 }
        assertEquals(MeasurementType.DISTANCE, canvas.scene.measurements.single().type)

        click(dialog, "조건 목록")
        clickPanelContaining(dialog, "13.3")
        click(dialog, "값 바꾸기")
        walk(overlay(dialog)).filterIsInstance<EditText>().single().setText("14")
        assertNoVisibleAlert()
        click(dialog, "적용")
        awaitReady(dialog) { it.constraints.any { c -> c.value == 14.0 } }
        assertEquals(4.0, perpendicularLength(), 1e-5)
        assertEquals(canvas.scene, store.load(target).scene)
    }

    @Test fun `stale writer reloads concurrent save and subsequent edits can be saved`() {
        val dialog = open()
        val canvas = canvas(dialog)
        val external = ConstructionScene(points = listOf(GeometryPoint("external-point", 11.0, 12.0, "다른 편집")))
        store.save(store.load(target), external)

        // The visible editor still has revision 0; this edit must not overwrite the other save.
        canvas.onPoint(ConstructionAnchor(1.0, 2.0))
        awaitReady(dialog) { it == external }
        assertEquals(external, store.load(target).scene)

        canvas.onPoint(ConstructionAnchor(15.0, 12.0))
        awaitReady(dialog) { it.points.size == 2 }
        assertNotNull(canvas.scene.point("external-point"))
        assertEquals(canvas.scene, store.load(target).scene)
        assertEquals(2L, store.load(target).revision)
    }

    @Test fun `backup restoration dismisses stale delete dialog and invalidates its captured callback`() {
        val dialog = open()
        val canvas = canvas(dialog)
        canvas.onPoint(ConstructionAnchor(2.0, 3.0))
        awaitReady(dialog) { it.points.size == 1 }
        val restoredScene = canvas.scene
        val restoredPointId = restoredScene.points.single().id
        val dataRoot = File(activity.applicationContext.filesDir, "masternote")
        val sceneFile = File(dataRoot, ConstructionSceneStore.FEATURE_DIRECTORY).walkTopDown().single {
            it.isFile && it.extension == "json" && it.readText().contains(target.memoId)
        }
        val backupBytes = sceneFile.readBytes()

        canvas.onPoint(ConstructionAnchor(8.0, 3.0))
        awaitReady(dialog) { it.points.size == 2 }
        val discardedPointId = canvas.scene.points.single { it.id != restoredPointId }.id
        canvas.selectedIds = setOf(restoredPointId)
        canvas.onSelectionChanged(canvas.selectedIds)
        clickMore(dialog, "삭제")
        val staleDelete = latestAlert()
        val staleDeleteButton = staleDelete.getButton(AlertDialog.BUTTON_POSITIVE)
        assertTrue(staleDelete.isShowing)

        // Restore an actual older, valid durable document under the same root lock and event
        // used by backup restoration. Its old revision must not authorize the stale dialog.
        MasterNoteOptionalDataRootGuard.withStableDataRoot(dataRoot) {
            sceneFile.writeBytes(backupBytes)
            MasterNoteDataRootBus.dataRootReplaced()
        }
        awaitReady(dialog) { it == restoredScene }
        assertFalse("Restore must close confirmation dialogs based on the old scene", staleDelete.isShowing)
        assertArrayEquals(backupBytes, sceneFile.readBytes())

        // Simulate an already queued click arriving after dismissal. This callback captured a
        // deletion candidate from before restoration and must not overwrite the restored file.
        staleDeleteButton.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        awaitReady(dialog)
        assertEquals(restoredScene, canvas.scene)
        assertArrayEquals(backupBytes, sceneFile.readBytes())

        canvas.onPoint(ConstructionAnchor(12.0, 3.0))
        awaitReady(dialog) { it.points.size == 2 }
        assertNotNull(canvas.scene.point(restoredPointId))
        assertNull(canvas.scene.point(discardedPointId))
        assertEquals(canvas.scene, store.load(target).scene)
    }

    @Test fun `motion events drag the selected overlapping point instead of the first point`() {
        val original = ConstructionScene(points = listOf(
            GeometryPoint("first-overlap", 0.0, 0.0, "첫 점"),
            GeometryPoint("selected-overlap", 0.0, 0.0, "선택 점"),
        ))
        store.save(store.load(target), original)
        val dialog = open()
        val canvas = canvas(dialog)
        canvas.selectedIds = setOf("selected-overlap")
        canvas.onSelectionChanged(canvas.selectedIds)
        canvas.fitScene()
        assertTrue(canvas.width > 0 && canvas.height > 0)

        // Read the public viewport mapping; no private viewport fields or drag callbacks are
        // accessed. Layout changes must not change which of two coincident points is dragged.
        val screen = requireNotNull(canvas.pointScreenPosition("selected-overlap"))
        val x = screen.x
        val y = screen.y
        val displacement = maxOf(100f, ViewConfiguration.get(activity).scaledTouchSlop * 3f)
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, eventTime: Long, px: Float) {
            val event = MotionEvent.obtain(downTime, eventTime, action, px, y, 0)
            try { assertTrue(canvas.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        val revision = store.load(target).revision
        touch(MotionEvent.ACTION_DOWN, downTime, x)
        touch(MotionEvent.ACTION_MOVE, downTime + 16, x + displacement)
        touch(MotionEvent.ACTION_UP, downTime + 32, x + displacement)
        awaitReady(dialog) { store.load(target).revision == revision + 1 }

        val untouched = canvas.scene.point("first-overlap")!!
        assertEquals(original.point("first-overlap")!!.label, untouched.label)
        assertEquals(0.0, untouched.x, 1e-9)
        assertEquals(0.0, untouched.y, 1e-9)
        assertTrue(canvas.scene.point("selected-overlap")!!.x > .1)
        assertEquals(0.0, canvas.scene.point("selected-overlap")!!.y, 1e-5)
        assertEquals(canvas.scene, store.load(target).scene)
    }

    @Test fun `condition measurement and numeric overlays never resize or move the drawing viewport`() {
        val original = measuredSegmentScene()
        store.save(store.load(target), original)
        val dialog = open()
        val canvas = canvas(dialog)
        canvas.selectedIds = setOf("ab")
        canvas.onSelectionChanged(canvas.selectedIds)
        relayout(dialog)
        val beforeBounds = Rect(canvas.left, canvas.top, canvas.right, canvas.bottom)
        val beforePositions = original.points.associate { it.id to requireNotNull(canvas.pointScreenPosition(it.id)) }
        val hint = walk(dialog.window!!.decorView).single { it.contentDescription == "현재 작도 동작" }
        assertTrue(canvas.parent is FrameLayout)
        assertSame("The instruction must float above the canvas rather than take another layout row", canvas.parent, hint.parent)

        fun assertViewportUnchanged() {
            relayout(dialog)
            assertEquals(beforeBounds, Rect(canvas.left, canvas.top, canvas.right, canvas.bottom))
            beforePositions.forEach { (id, expected) ->
                val actual = requireNotNull(canvas.pointScreenPosition(id))
                assertEquals("Point $id must not shift when a panel changes", expected.x, actual.x, .001f)
                assertEquals("Point $id must not shift when a panel changes", expected.y, actual.y, .001f)
            }
            assertEquals(original, canvas.scene)
            assertEquals(1L, store.load(target).revision)
            assertNoVisibleAlert()
        }

        listOf("조건 추가", "측정", "조건 목록").forEach { name ->
            click(dialog, name)
            assertEquals(View.VISIBLE, overlay(dialog).visibility)
            assertSame(canvas.parent, overlay(dialog).parent)
            assertViewportUnchanged()
            click(dialog, name)
            assertEquals(View.GONE, overlay(dialog).visibility)
            assertViewportUnchanged()
        }
        click(dialog, "조건 목록")
        clickPanelContaining(dialog, "10 cm")
        click(dialog, "값 바꾸기")
        walk(overlay(dialog)).filterIsInstance<EditText>().single().requestFocus()
        assertViewportUnchanged()
        click(dialog, "메뉴 닫기")
        assertViewportUnchanged()
    }

    @Test fun `active line tool and floating instruction remain visible after a durable draw`() {
        val dialog = open()
        val canvas = canvas(dialog)
        click(dialog, "선분")
        assertEquals(ConstructionTool.SEGMENT, canvas.tool)
        assertTrue(button(dialog, "선분").isSelected)
        assertFalse(button(dialog, "선택").isSelected)
        canvas.onSegment(ConstructionAnchor(1.0, 2.0), ConstructionAnchor(7.0, 2.0))
        awaitReady(dialog) { it.segments.size == 1 }
        relayout(dialog)
        assertTrue(button(dialog, "선분").isSelected)
        assertEquals(ConstructionTool.SEGMENT, canvas.tool)
        val hint = walk(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.contentDescription == "현재 작도 동작" }
        assertTrue(hint.text.toString(), hint.text.toString().contains("선분"))
        assertTrue(hint.isShown)
        assertSame(canvas.parent, hint.parent)
        assertEquals(canvas.scene, store.load(target).scene)
    }

    @Test fun `new drawing color survives durable save and reopening`() {
        val dialog = open()
        val canvas = canvas(dialog)
        clickDescription(dialog, "새 도형 색 파랑")
        canvas.onSegment(ConstructionAnchor(1.0, 1.0), ConstructionAnchor(7.0, 1.0))
        awaitReady(dialog) { it.segments.size == 1 }
        val blue = Color.rgb(53, 113, 176)
        assertEquals(blue, canvas.scene.segments.single().colorArgb)
        assertTrue(canvas.scene.points.all { it.colorArgb == blue })
        val center = canvas.scene.points.first()
        canvas.onCircle(ConstructionAnchor(center.x, center.y, center.id), 2.0)
        awaitReady(dialog) { it.circles.size == 1 }
        assertEquals(blue, canvas.scene.circles.single().colorArgb)
        val durable = store.load(target).scene
        dialog.dismiss()
        assertEquals(durable, canvas(open()).scene)
    }

    @Test fun `dragging a dimension saves once and undo delete undo preserve geometry and hard conditions`() {
        val original = measuredSegmentScene()
        store.save(store.load(target), original)
        val dialog = open()
        val canvas = canvas(dialog)
        val dimension = original.measurements.single()
        render(canvas)
        val position = requireNotNull(canvas.measurementScreenPosition(dimension.id))
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, offsetMillis: Long, dx: Float, dy: Float) {
            val event = MotionEvent.obtain(downTime, downTime + offsetMillis, action, position.x + dx, position.y + dy, 0)
            try { assertTrue(canvas.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        val revision = store.load(target).revision
        touch(MotionEvent.ACTION_DOWN, 0, 0f, 0f)
        touch(MotionEvent.ACTION_MOVE, 16, 50f, -35f)
        touch(MotionEvent.ACTION_MOVE, 32, 100f, -70f)
        assertEquals("Pointer moves only preview the label; they must not write repeated revisions", revision, store.load(target).revision)
        touch(MotionEvent.ACTION_UP, 48, 100f, -70f)
        awaitReady(dialog) { store.load(target).revision == revision + 1 }
        val moved = canvas.scene
        assertNotEquals(dimension, moved.measurements.single())
        assertEquals(original.points, moved.points)
        assertEquals(original.segments, moved.segments)
        assertEquals(original.constraints, moved.constraints)
        assertEquals(moved, store.load(target).scene)

        click(dialog, "되돌리기")
        awaitReady(dialog) { it == original }
        click(dialog, "다시")
        awaitReady(dialog) { it == moved }
        canvas.onMeasurementSelected(dimension.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, overlay(dialog).visibility)
        click(dialog, "표시 지우기")
        awaitReady(dialog) { it.measurements.isEmpty() }
        assertEquals(original.points, canvas.scene.points)
        assertEquals(original.constraints, canvas.scene.constraints)
        click(dialog, "되돌리기")
        awaitReady(dialog) { it == moved }
        assertEquals(moved, store.load(target).scene)
        dialog.dismiss()
        assertEquals(moved, canvas(open()).scene)
    }

    @Test fun `a rejected relation refreshes panel actions so a dimension can still be opened`() {
        val original = ConstructionScene(
            points = listOf(GeometryPoint("a", 0.0, 0.0, "A"), GeometryPoint("b", 2.0, 3.0, "B")),
            segments = listOf(GeometrySegment("ab", "a", "b")),
            constraints = listOf(
                GeometryConstraint("fix-a", ConstraintType.FIXED_POINT, listOf("a"), targetX = 0.0, targetY = 0.0),
                GeometryConstraint("fix-b", ConstraintType.FIXED_POINT, listOf("b"), targetX = 2.0, targetY = 3.0),
            ),
        )
        store.save(store.load(target), original)
        val dialog = open()
        val canvas = canvas(dialog)
        canvas.selectedIds = setOf("ab")
        canvas.onSelectionChanged(canvas.selectedIds)
        click(dialog, "조건 추가")
        click(dialog, "수평으로 유지")
        awaitReady(dialog) { it == original }
        assertEquals(1L, store.load(target).revision)
        assertEquals(View.VISIBLE, overlay(dialog).visibility)
        click(dialog, "선분 길이 (cm)")
        assertEquals("A failed solve must not leave all existing overlay actions stale", 1,
            walk(overlay(dialog)).filterIsInstance<EditText>().count())
        assertNoVisibleAlert()
        assertEquals(original, store.load(target).scene)
    }

    /** Kept separate by graphics mode so unsupported native platforms can exclude only this QA. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp-land-mdpi")
    fun `native landscape preview writes a reviewable trapezoid and measurement overlay PNG`() {
        val example = ConstructionEdits.trapezoid()
        val perpendicular = example.segments.single { it.label == "ㅁㅂ" }
        val displayed = example.copy(measurements = listOf(
            GeometryMeasurement("qa-perpendicular", MeasurementType.DISTANCE,
                listOf(perpendicular.startPointId, perpendicular.endPointId)),
        ))
        store.save(store.load(target), displayed)
        val dialog = open(1200, 800)
        val canvas = canvas(dialog)
        canvas.fitScene()
        canvas.selectedIds = setOf(perpendicular.id)
        canvas.onSelectionChanged(canvas.selectedIds)
        click(dialog, "측정")
        relayout(dialog, 1200, 800)
        assertEquals(View.VISIBLE, overlay(dialog).visibility)
        assertTrue(panelText(dialog).contains("3.8"))
        assertNoVisibleAlert()

        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val output = File("build/outputs/construction-ui-qa.png")
        try {
            dialog.window!!.decorView.draw(Canvas(bitmap))
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.isFile && output.length() > 5_000)
            println("Construction native UI review image: ${output.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun open(width: Int = 1000, height: Int = 1400): ConstructionEditorDialog {
        val dialog = ConstructionEditorDialog(activity, target, "테스트 메모")
        dialogs += dialog
        dialog.show()
        relayout(dialog, width, height)
        awaitReady(dialog)
        return dialog
    }

    private fun canvas(dialog: ConstructionEditorDialog) = walk(dialog.window!!.decorView).filterIsInstance<ConstructionCanvasView>().single()

    private fun click(dialog: ConstructionEditorDialog, label: String) {
        val target = button(dialog, label)
        assertTrue("Button $label must be enabled", target.isEnabled)
        target.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun button(dialog: ConstructionEditorDialog, label: String): Button =
        walk(dialog.window!!.decorView).filterIsInstance<Button>().filter { it.isShown }.single {
            it.text.toString() == label || it.contentDescription?.toString() == "작도 $label"
        }

    private fun clickDescription(dialog: ConstructionEditorDialog, description: String) {
        val target = walk(dialog.window!!.decorView).filterIsInstance<Button>().single { it.contentDescription == description }
        assertTrue(target.isEnabled)
        target.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clickMore(dialog: ConstructionEditorDialog, label: String) {
        click(dialog, "더보기")
        click(dialog, label)
    }

    private fun overlay(dialog: ConstructionEditorDialog): View =
        walk(dialog.window!!.decorView).single { it.tag == "construction-overlay" }

    private fun panelText(dialog: ConstructionEditorDialog): String =
        walk(overlay(dialog)).filterIsInstance<TextView>().map { it.text.toString() }.joinToString(" | ")

    private fun clickPanelContaining(dialog: ConstructionEditorDialog, text: String) {
        val target = walk(overlay(dialog)).filterIsInstance<Button>().single { it.text.toString().contains(text) }
        assertTrue(target.isEnabled)
        target.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertNoVisibleAlert() {
        assertFalse("Conditions, values, and measurements belong to the overlay, not a modal dialog",
            ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
    }

    private fun relayout(dialog: ConstructionEditorDialog, width: Int = 1000, height: Int = 1400) {
        shadowOf(Looper.getMainLooper()).idle()
        val decor = dialog.window!!.decorView
        repeat(2) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun render(canvas: ConstructionCanvasView) {
        val bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        try { canvas.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }

    private fun measuredSegmentScene() = ConstructionScene(
        points = listOf(GeometryPoint("a", 0.0, 0.0, "A"), GeometryPoint("b", 10.0, 0.0, "B")),
        segments = listOf(GeometrySegment("ab", "a", "b")),
        constraints = listOf(GeometryConstraint("length", ConstraintType.LENGTH, listOf("ab"), value = 10.0)),
        measurements = listOf(GeometryMeasurement("distance", MeasurementType.DISTANCE, listOf("a", "b"))),
    )

    private fun latestAlert(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(ShadowAlertDialog.getLatestAlertDialog()) { "Expected a visible selection/input dialog" }
    }

    private fun chooseLatestItem(index: Int) {
        val list = latestAlert().listView
        list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitReady(dialog: ConstructionEditorDialog, condition: (ConstructionScene) -> Boolean = { true }) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val canvas = canvas(dialog)
            if (canvas.editable && condition(canvas.scene)) return
            Thread.sleep(10)
        }
        val visibleText = walk(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.joinToString(" | ")
        fail("Dialog did not finish its bounded background operation: $visibleText")
    }

    private fun walk(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(walk(view.getChildAt(index)))
    }

    private fun distance(scene: ConstructionScene, first: String, second: String): Double {
        val a = scene.point(first)!!; val b = scene.point(second)!!
        return hypot(a.x - b.x, a.y - b.y)
    }
}
