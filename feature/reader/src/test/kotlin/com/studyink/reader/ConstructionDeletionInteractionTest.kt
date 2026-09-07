package com.studyink.reader

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h700dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConstructionDeletionInteractionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var store: ConstructionSceneStore
    private lateinit var target: ConstructionTarget
    private lateinit var editor: ConstructionEditorView

    @Before fun setup() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        store = ConstructionSceneStore(File(temporary.root, "geometry"))
        target = ConstructionTarget("book", 0, 1, UUID.randomUUID().toString())
        store.save(store.load(target), example())
        editor = ConstructionEditorView(activity, target, "삭제", embedded = true, store = store,
            replicaRole = ConstructionReplicaRole.STUDENT)
        val root = FrameLayout(activity).apply { addView(editor, FrameLayout.LayoutParams(900, 700)) }
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 900, 700)
        awaitReady()
    }

    @After fun cleanup() {
        if (::editor.isInitialized) editor.closeEditor()
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun `visible trash deletes the selected segment only and undo restores conditions`() {
        val original = store.load(target).scene
        assertFalse(trash().isEnabled)
        select("AB")
        assertTrue(trash().isEnabled)
        trash().performClick()
        confirm()
        awaitReady { it.segment("AB") == null }
        val result = store.load(target).scene
        assertEquals(original.points, result.points)
        assertEquals(original.circles, result.circles)
        assertEquals(listOf(original.segment("AC")), result.segments)
        assertEquals("Point-anchored measurements remain valid when only their visible segment is removed",
            original.measurements, result.measurements)
        assertTrue(result.constraints.isEmpty())
        assertFalse(trash().isEnabled)
        assertTrue(editor.undoEdit())
        awaitReady { it == original }
    }

    @Test fun `annotation highlight requires explicit entity selection and never selects incidental endpoints`() {
        val original = store.load(target).scene
        select("AC")
        canvas().onConstraintSelected("length")
        assertTrue(canvas().selectedIds.isEmpty())
        assertFalse(trash().isEnabled)
        assertEquals(original, store.load(target).scene)
        tagged("condition-select-entities-length").performClick()
        assertEquals(setOf("AB"), canvas().selectedIds)
        assertTrue(trash().isEnabled)
        trash().performClick()
        ShadowAlertDialog.getLatestAlertDialog()!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(original, store.load(target).scene)
        trash().performClick()
        confirm()
        awaitReady { it.segment("AB") == null }
        assertNotNull(canvas().scene.segment("AC"))
        assertNotNull(canvas().scene.point("A"))
        assertNotNull(canvas().scene.point("B"))
        assertNotNull(canvas().scene.circle("circle"))
        assertEquals(original.measurements, canvas().scene.measurements)
    }

    @Test fun `confirmation cannot delete a stale selection even without a scene revision change`() {
        val original = store.load(target).scene
        select("AB")
        trash().performClick()
        select("AC")
        confirm()
        awaitReady()
        assertEquals(original, store.load(target).scene)
        assertFalse(editor.canUndo)
    }

    private fun canvas() = walk(editor).filterIsInstance<ConstructionCanvasView>().single()
    private fun trash() = tagged("construction-delete-selected")
    private fun tagged(value: String): View = walk(editor).single { it.tag == value }
    private fun walk(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(walk(view.getChildAt(index)))
    }
    private fun select(vararg ids: String) {
        canvas().selectedIds = ids.toSet()
        canvas().onSelectionChanged(canvas().selectedIds)
    }
    private fun confirm() {
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun awaitReady(predicate: (ConstructionScene) -> Boolean = { true }) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!editor.hasPendingWork && canvas().editable && predicate(canvas().scene)) return
            Thread.sleep(10)
        }
        fail("Deletion editor did not settle")
    }
    private fun example() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0, "A"), GeometryPoint("B", 8.0, 0.0, "B"), GeometryPoint("C", 0.0, 6.0, "C")),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("AC", "A", "C")),
        circles = listOf(GeometryCircle("circle", "A", 2.0)),
        constraints = listOf(GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), 8.0),
            GeometryConstraint("right", ConstraintType.PERPENDICULAR, listOf("AB", "AC"))),
        measurements = listOf(GeometryMeasurement("ab", MeasurementType.DISTANCE, listOf("A", "B"), 1.2, 2.3),
            GeometryMeasurement("radius", MeasurementType.RADIUS, listOf("circle"))),
    )
}
