package com.studyink.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.MeasurementType
import com.studyink.construction.storage.ConstructionConflictChoice
import com.studyink.construction.storage.ConstructionReplicaRole
import com.studyink.construction.storage.ConstructionReplicaStore
import com.studyink.construction.storage.ConstructionSceneAccess
import com.studyink.construction.storage.ConstructionSceneStore
import com.studyink.construction.storage.ConstructionSyncUiState
import com.studyink.construction.storage.ConstructionTarget
import com.studyink.construction.storage.ConstructionUiBridge
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ConstructionEmbeddedEditorTest {
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

    @Test fun `embedded editor saves reopens and undoes without a separate window`() {
        val editor = open()
        val canvas = canvas(editor)
        canvas.onPoint(ConstructionAnchor(3.5, 6.25))
        awaitReady(editor) { it.points.size == 1 }
        assertTrue(editor.canUndo)
        assertTrue(editor.undoEdit())
        awaitReady(editor) { it.points.isEmpty() }
        assertTrue(editor.redoEdit())
        awaitReady(editor) { it.points.size == 1 }
        val saved = access.load(target).scene
        editor.closeEditor()
        val reopened = open()
        assertEquals(saved, canvas(reopened).scene)
        assertEquals(3.5, saved.points.single().x, 0.0)
        assertEquals(6.25, saved.points.single().y, 0.0)
        assertTrue(canvas(reopened).width > 0)
        assertTrue(canvas(reopened).height > 0)
    }

    @Test fun `closed embedded controller ignores later drawing callbacks`() {
        val editor = open()
        val canvas = canvas(editor)
        editor.closeEditor()
        canvas.onPoint(ConstructionAnchor(9.0, 2.0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0L, access.load(target).revision)
        assertEquals(ConstructionScene(), access.load(target).scene)
    }

    @Test fun `teacher publishes only when publish is explicitly pressed`() {
        val bridge = TestBridge(access)
        val editor = open(bridge)
        canvas(editor).onPoint(ConstructionAnchor(2.0, 3.0))
        awaitReady(editor) { it.points.size == 1 }
        assertEquals(0, bridge.publishCalls)
        walk(editor).single { it.contentDescription == "작도 발행" }.performClick()
        assertEquals(1, bridge.publishCalls)
    }

    @Test fun `changed conflict token invalidates a confirmation already on screen`() {
        val bridge = TestBridge(access).apply {
            state = state.copy(conflictToken = "version-A", message = "서로 다른 도형 · 눌러 확인")
        }
        val editor = open(bridge)
        walk(editor).single { it.contentDescription == "도형 동기화 상태" }.performClick()
        val choices = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("선생 도형으로 학생 맞추기", choices.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("학생 도형으로 선생 맞추기", choices.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        choices.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val confirmation = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame(choices, confirmation)
        bridge.state = bridge.state.copy(conflictToken = "version-B")
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(bridge.resolutions.isEmpty())
        assertEquals(ConstructionScene(), access.load(target).scene)
    }

    @Test fun `student choice is sent with the exact compared conflict token`() {
        val bridge = TestBridge(access).apply { state = state.copy(conflictToken = "agreed-version") }
        val editor = open(bridge)
        walk(editor).single { it.contentDescription == "도형 동기화 상태" }.performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(ConstructionConflictChoice.USE_STUDENT to "agreed-version"), bridge.resolutions)
    }

    @Test fun `clean teacher editor follows committed student coordinates without solving on receipt`() {
        val student = ConstructionReplicaStore(File(temporary.root, "student"))
        val teacher = ConstructionReplicaStore(File(temporary.root, "teacher"))
        student.saveLocal(student.load(target, ConstructionReplicaRole.STUDENT),
            ConstructionScene(points = listOf(GeometryPoint("A", 1.0, 2.0))))
        teacher.receiveStudentSnapshot(target, student.studentSnapshot(target))
        val teacherAccess = teacher.sceneAccess(ConstructionReplicaRole.TEACHER)
        val editor = open(TestBridge(teacherAccess), teacherAccess)
        student.saveLocal(student.load(target, ConstructionReplicaRole.STUDENT),
            ConstructionScene(points = listOf(GeometryPoint("A", 7.5, -4.25))))
        teacher.receiveStudentSnapshot(target, student.studentSnapshot(target))
        awaitReady(editor) { it.points.single().x == 7.5 }
        assertEquals(-4.25, canvas(editor).scene.points.single().y, 0.0)
        assertFalse(teacher.load(target, ConstructionReplicaRole.TEACHER).draftDirty)
    }

    @Test fun `incoming student commit does not overwrite the teachers edited draft`() {
        val student = ConstructionReplicaStore(File(temporary.root, "student"))
        val teacher = ConstructionReplicaStore(File(temporary.root, "teacher"))
        student.saveLocal(student.load(target, ConstructionReplicaRole.STUDENT),
            ConstructionScene(points = listOf(GeometryPoint("A", 1.0, 2.0))))
        teacher.receiveStudentSnapshot(target, student.studentSnapshot(target))
        val teacherAccess = teacher.sceneAccess(ConstructionReplicaRole.TEACHER)
        val editor = open(TestBridge(teacherAccess), teacherAccess)
        canvas(editor).onPoint(ConstructionAnchor(4.0, 6.0))
        awaitReady(editor) { it.points.size == 2 }
        val draft = canvas(editor).scene
        student.saveLocal(student.load(target, ConstructionReplicaRole.STUDENT),
            ConstructionScene(points = listOf(GeometryPoint("A", 8.0, 9.0))))
        teacher.receiveStudentSnapshot(target, student.studentSnapshot(target))
        awaitReady(editor) { it == draft }
        assertEquals(draft, canvas(editor).scene)
        val stored = teacher.load(target, ConstructionReplicaRole.TEACHER)
        assertTrue(stored.draftDirty)
        assertEquals(8.0, stored.studentShadow!!.scene.points.single().x, 0.0)
        assertTrue("A shadow-only update must not discard teacher undo", editor.canUndo)
        canvas(editor).onPoint(ConstructionAnchor(11.0, 12.0))
        awaitReady(editor) { it.points.size == 3 }
        assertEquals(3, teacher.load(target, ConstructionReplicaRole.TEACHER).scene.points.size)
    }

    /** Layout QA only: the geometry editor is real; the ink pane is an explicitly static fixture.
     * AndroidX Ink's Android JNI is tested separately in src/androidTest on a device. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp-land-mdpi")
    fun `native composed memo layout previews tablet and narrow phone panes`() {
        val example = ConstructionEdits.trapezoid()
        val perpendicular = example.segments.single { it.label == "ㅁㅂ" }
        access.save(access.load(target), example.copy(measurements = listOf(
            GeometryMeasurement("length-note", MeasurementType.DISTANCE,
                listOf(perpendicular.startPointId, perpendicular.endPointId)),
        )))
        for ((label, width, height) in listOf(Triple("tablet", 1200, 800), Triple("phone", 420, 900))) {
            val editor = ConstructionEditorView(activity, target, "메모", embedded = true,
                store = access, replicaRole = ConstructionReplicaRole.STUDENT)
            editors += editor
            val host = MemoCompositionHost(activity)
            val ink = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = "손필기   펜   지우개   ↶   ↷"; textSize = 11f; gravity = Gravity.CENTER_VERTICAL
                    setPadding(6, 0, 0, 0)
                }, LinearLayout.LayoutParams(-1, 36))
                addView(ScrollView(activity).apply {
                    addView(StaticInkPreview(activity), FrameLayout.LayoutParams(-1, -2))
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            host.addView(editor, LinearLayout.LayoutParams(0, -1, .56f))
            host.addView(View(activity).apply { setBackgroundColor(0xffc3cabc.toInt()) }, LinearLayout.LayoutParams(1, -1))
            host.addView(ink, LinearLayout.LayoutParams(0, -1, .44f))
            val card = FrameLayout(activity).apply {
                setBackgroundColor(0xfffffdf5.toInt())
                addView(host, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 44 })
                addView(TextView(activity).apply {
                    text = "메모 · 1회                         도형 포함    —"
                    textSize = 13f; gravity = Gravity.CENTER_VERTICAL; setPadding(14, 0, 0, 0)
                    setBackgroundColor(0xfff6f2e6.toInt())
                }, FrameLayout.LayoutParams(-1, 44))
            }
            val root = FrameLayout(activity).apply {
                setBackgroundColor(0xffdeded9.toInt())
                addView(card, FrameLayout.LayoutParams((width * .8).toInt(), (height * .8).toInt(), Gravity.CENTER))
            }
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            awaitReady(editor)
            // The awaited ViewRoot traversal uses this test's 1200dp landscape qualifiers.
            // Reapply the requested capture bounds afterwards, particularly for the second,
            // narrow-phone fixture; otherwise its centered card can lie outside the bitmap.
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            canvas(editor).fitScene()
            assertEquals(width, root.width)
            assertEquals(height, root.height)
            assertTrue(card.left >= 0 && card.right <= width && card.top >= 0 && card.bottom <= height)
            assertTrue(canvas(editor).width > 0 && canvas(editor).height > 0)
            assertEquals(if (label == "phone") LinearLayout.VERTICAL else LinearLayout.HORIZONTAL, host.orientation)
            if (label == "phone") {
                assertEquals(host.width, editor.width)
                assertEquals(host.width, ink.width)
                assertTrue(ink.top > editor.bottom)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val output = File("build/outputs/memo-composition-$label-qa.png")
            try {
                root.draw(Canvas(bitmap))
                check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue(output.length() > 5_000L)
                println("Composed memo layout QA (static ink fixture): ${output.absolutePath}")
            } finally { bitmap.recycle(); editor.closeEditor() }
        }
    }

    private class StaticInkPreview(context: Context) : View(context) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(39, 61, 80); style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, (width * 2.2).toInt())
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xfffffef9.toInt())
            pen.strokeWidth = width * .004f
            val marks = listOf(
                listOf(.10f to .10f, .18f to .10f, .20f to .11f, .18f to .12f, .14f to .123f,
                    .19f to .13f, .20f to .14f, .18f to .15f, .10f to .15f),
                listOf(.24f to .148f, .241f to .149f),
                listOf(.31f to .12f, .28f to .11f, .30f to .10f, .35f to .10f, .37f to .11f,
                    .35f to .123f, .30f to .13f, .28f to .14f, .30f to .15f, .35f to .15f,
                    .37f to .14f, .35f to .13f, .31f to .12f),
                listOf(.10f to .18f, .50f to .181f),
                listOf(.12f to .36f, .36f to .25f, .73f to .37f, .12f to .36f),
                listOf(.36f to .25f, .38f to .365f),
            )
            for (mark in marks) {
                val path = Path()
                mark.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.first * width, point.second * height)
                    else path.lineTo(point.first * width, point.second * height)
                }
                canvas.drawPath(path, pen)
            }
        }
    }

    private fun open(bridge: TestBridge? = null, sceneAccess: ConstructionSceneAccess = access): ConstructionEditorView {
        val editor = ConstructionEditorView(
            activity, target, "테스트 메모", embedded = true, store = sceneAccess,
            replicaRole = if (bridge == null) ConstructionReplicaRole.STUDENT else ConstructionReplicaRole.TEACHER,
            syncBridge = bridge,
        )
        editors += editor
        activity.setContentView(FrameLayout(activity).apply {
            addView(editor, FrameLayout.LayoutParams(-1, -1))
        })
        editor.measure(View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(950, View.MeasureSpec.EXACTLY))
        editor.layout(0, 0, 520, 950)
        awaitReady(editor)
        return editor
    }

    private fun awaitReady(editor: ConstructionEditorView, predicate: (ConstructionScene) -> Boolean = { true }) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!editor.hasPendingWork && canvas(editor).editable && predicate(canvas(editor).scene)) return
            Thread.sleep(10)
        }
        fail("Embedded editor did not finish its bounded worker")
    }

    private fun canvas(editor: ConstructionEditorView) = walk(editor).filterIsInstance<ConstructionCanvasView>().single()
    private fun walk(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(walk(view.getChildAt(index)))
    }

    private class TestBridge(private val access: ConstructionSceneAccess) : ConstructionUiBridge {
        var state = ConstructionSyncUiState(ConstructionReplicaRole.TEACHER, available = true, canPublish = true)
        var publishCalls = 0
        val resolutions = mutableListOf<Pair<ConstructionConflictChoice, String>>()
        override fun registerTarget(target: ConstructionTarget, role: ConstructionReplicaRole) = Unit
        override fun state(target: ConstructionTarget) = state
        override fun addListener(target: ConstructionTarget, listener: () -> Unit) = AutoCloseable {}
        override fun requestPublish(target: ConstructionTarget) { publishCalls++ }
        override fun resolveConflict(target: ConstructionTarget, choice: ConstructionConflictChoice, expectedToken: String) {
            resolutions += choice to expectedToken
        }
        override fun sceneAccess(target: ConstructionTarget) = access
    }
}
