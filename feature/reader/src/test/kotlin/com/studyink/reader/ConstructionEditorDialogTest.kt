package com.studyink.reader

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.storage.ConstructionSceneStore
import com.studyink.construction.storage.ConstructionTarget
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
        click(dialog, "예제")
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
        click(dialog, "예제")
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
        val measurement = latestAlert()
        assertTrue(measurement.findViewById<TextView>(android.R.id.message).text.toString().contains("3.8"))
        measurement.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        click(dialog, "조건 목록")
        val conditions = latestAlert().listView
        val heightIndex = (0 until conditions.adapter.count).single {
            conditions.adapter.getItem(it).toString().contains("13.3")
        }
        chooseLatestItem(heightIndex)
        chooseLatestItem(0) // Value editing, followed by the real numeric input dialog.
        val numeric = latestAlert()
        walk(numeric.window!!.decorView).filterIsInstance<EditText>().single().setText("14")
        numeric.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
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

    private fun open(): ConstructionEditorDialog {
        val dialog = ConstructionEditorDialog(activity, target, "테스트 메모")
        dialogs += dialog
        dialog.show()
        val decor = dialog.window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1000, 1400)
        awaitReady(dialog)
        return dialog
    }

    private fun canvas(dialog: ConstructionEditorDialog) = walk(dialog.window!!.decorView).filterIsInstance<ConstructionCanvasView>().single()

    private fun click(dialog: ConstructionEditorDialog, label: String) {
        val button = walk(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.toString() == label }
        assertTrue("Button $label must be enabled", button.isEnabled)
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

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
