package com.studyink.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
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
import com.studyink.core.model.PagePoint
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

    @Test fun `shared circle and canonical ink stay aligned after zoom pan and editor reload`() {
        access.save(access.load(target), circleExample())
        var editor = open()
        val host = SharedMemoCanvasHost(activity)
        val ink = attachInkFixture(editor, host)
        val root = editor.parent as FrameLayout
        editor.layoutParams = FrameLayout.LayoutParams(900, 700)
        layout(root, 900, 700)
        assertAligned(editor, host)
        val before = requireNotNull(canvas(editor).pointScreenPosition("A"))
        val focus = host.viewport.worldToView(9.0, 12.0)
        host.viewport.zoom(1.6f, focus.x, focus.y)
        host.viewport.pan(-22f, 17f)
        assertAligned(editor, host)
        val transformed = requireNotNull(canvas(editor).pointScreenPosition("A"))
        assertTrue(before != transformed)
        assertEquals(host.width, ink.width)
        assertEquals(host.height, ink.height)
        assertEquals(circleExample(), access.load(target).scene)

        editor.detachSharedCanvas()
        editor.closeEditor()
        root.removeView(editor)
        editor = ConstructionEditorView(activity, target, "메모", embedded = true,
            store = access, replicaRole = ConstructionReplicaRole.STUDENT).also(editors::add)
        editor.attachSharedCanvas(host)
        root.addView(editor, FrameLayout.LayoutParams(900, 700))
        layout(root, 900, 700)
        awaitReady(editor)
        layout(root, 900, 700)
        assertAligned(editor, host)
        assertPoint(transformed, requireNotNull(canvas(editor).pointScreenPosition("A")))
        assertEquals(circleExample(), canvas(editor).scene)
    }

    @Test fun `opening small condition and measurement panels never shifts shared ink or geometry`() {
        access.save(access.load(target), circleExample())
        val editor = open()
        val host = SharedMemoCanvasHost(activity)
        val ink = attachInkFixture(editor, host)
        layout(editor, 900, 700)
        val bounds = globalBounds(host)
        val point = requireNotNull(canvas(editor).pointScreenPosition("A"))
        for (label in listOf("작도 조건 추가", "작도 측정", "작도 조건 목록")) {
            walk(editor).single { it.contentDescription == label }.performClick()
            layout(editor, 900, 700)
            assertEquals(bounds, globalBounds(host))
            assertEquals(globalBounds(canvas(editor)), globalBounds(ink))
            assertPoint(point, requireNotNull(canvas(editor).pointScreenPosition("A")))
            assertAligned(editor, host)
        }
    }

    /** Layout QA only: the geometry editor is real and ink is a transparent canonical fixture.
     * AndroidX wet ink's Android JNI is tested separately in src/androidTest on an isolated device. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp-land-mdpi")
    fun `native shared memo previews handwriting on a circle at two zoom levels`() {
        access.save(access.load(target), circleExample())
        for ((label, width, height) in listOf(Triple("tablet", 1200, 800), Triple("phone", 420, 900))) {
            val editor = ConstructionEditorView(activity, target, "메모", embedded = true,
                store = access, replicaRole = ConstructionReplicaRole.STUDENT)
            editors += editor
            val host = SharedMemoCanvasHost(activity)
            val ink = attachInkFixture(editor, host)
            val root = FrameLayout(activity).apply {
                setBackgroundColor(0xfffffdf5.toInt())
                addView(editor, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 36 })
                addView(TextView(activity).apply {
                    text = "메모 · 1회    손필기 / 도형    —"
                    textSize = 13f; gravity = Gravity.CENTER_VERTICAL; setPadding(14, 0, 0, 0)
                    setBackgroundColor(0xfff6f2e6.toInt())
                }, FrameLayout.LayoutParams(-1, 36))
            }
            activity.setContentView(root)
            layout(root, width, height)
            awaitReady(editor)
            // Reapply capture bounds after the qualified ViewRoot traversal (also for portrait).
            layout(root, width, height)
            assertEquals(width, root.width)
            assertEquals(height, root.height)
            assertEquals(0, editor.left)
            assertEquals(width, editor.width)
            assertEquals(height - 36, editor.height)
            assertTrue(canvas(editor).width > 0 && canvas(editor).height > 0)
            assertEquals(globalBounds(canvas(editor)), globalBounds(ink))
            try {
                // Center the same circle + ink anchor, never fitting either layer independently.
                val center = host.viewport.worldToView(9.0, 12.0)
                host.viewport.pan(host.width / 2f - center.x, host.height / 2f - center.y)
                for ((zoomLabel, factor) in listOf("overview" to 1f, "zoomed" to 1.65f)) {
                    val circleCenter = host.viewport.worldToView(9.0, 12.0)
                    host.viewport.zoom(factor, circleCenter.x, circleCenter.y)
                    assertAligned(editor, host)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val output = File("build/outputs/memo-shared-$label-$zoomLabel-qa.png")
                    try {
                        root.draw(Canvas(bitmap))
                        check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
                        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        assertTrue(output.length() > 5_000L)
                        println("Shared memo layout QA (canonical ink fixture): ${output.absolutePath}")
                    } finally { bitmap.recycle() }
                }
            } finally { editor.detachSharedCanvas(); editor.closeEditor() }
        }
    }

    private fun attachInkFixture(editor: ConstructionEditorView, host: SharedMemoCanvasHost): View {
        val ink = StaticInkPreview(activity, host.viewport)
        val inkLayer = FrameLayout(activity).apply { addView(ink, FrameLayout.LayoutParams(-1, -1)) }
        host.addView(inkLayer, FrameLayout.LayoutParams(-1, -1))
        host.inkLayer = inkLayer
        editor.attachSharedCanvas(host)
        return ink
    }

    private fun assertAligned(editor: ConstructionEditorView, host: SharedMemoCanvasHost) {
        // World A=(14,12) maps to the existing 1000x2200 canonical memo page, without migration.
        val inkAnchor = requireNotNull(host.viewport.canonicalToView(0, PagePoint(17f / 30f * 1000f, 400f)))
        assertPoint(inkAnchor, requireNotNull(canvas(editor).pointScreenPosition("A")))
    }

    private fun assertPoint(expected: PointF, actual: PointF) {
        assertEquals(expected.x, actual.x, .002f)
        assertEquals(expected.y, actual.y, .002f)
    }

    private fun globalBounds(view: View): RectF {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return RectF(location[0].toFloat(), location[1].toFloat(),
            (location[0] + view.width).toFloat(), (location[1] + view.height).toFloat())
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun circleExample() = ConstructionScene(
        points = listOf(GeometryPoint("O", 9.0, 12.0, "O"), GeometryPoint("A", 14.0, 12.0, "A")),
        segments = listOf(GeometrySegment("OA", "O", "A", colorArgb = 0xff344454.toInt())),
        circles = listOf(GeometryCircle("circle", "O", 5.0, colorArgb = 0xff344454.toInt())),
        measurements = listOf(GeometryMeasurement("radius", MeasurementType.RADIUS, listOf("circle"))),
    )

    private class StaticInkPreview(context: Context, private val viewport: SharedMemoViewport) : View(context) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(167, 67, 85); style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            pen.strokeWidth = viewport.canonicalWidthToView(0, 3.5f)
            val marks = listOf(
                // Check mark's middle point lies exactly on circle A, making drift visible.
                listOf(530f to 440f, (17f / 30f * 1000f) to 400f, 640f to 320f),
                // Handwritten r = 5 beside the radius and a free underline through the circle.
                listOf(300f to 520f, 300f to 490f, 312f to 482f, 326f to 490f),
                listOf(342f to 494f, 370f to 494f),
                listOf(342f to 507f, 370f to 507f),
                listOf(418f to 480f, 389f to 480f, 387f to 499f, 410f to 498f,
                    420f to 510f, 411f to 523f, 387f to 521f),
                listOf(290f to 539f, 430f to 543f),
            )
            for (mark in marks) {
                val path = Path()
                mark.forEachIndexed { index, point ->
                    val mapped = requireNotNull(viewport.canonicalToView(0, PagePoint(point.first, point.second)))
                    if (index == 0) path.moveTo(mapped.x, mapped.y) else path.lineTo(mapped.x, mapped.y)
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
