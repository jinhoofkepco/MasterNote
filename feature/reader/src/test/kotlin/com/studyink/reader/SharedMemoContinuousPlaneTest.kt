package com.studyink.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import com.studyink.memo.core.MemoAnchor
import com.studyink.memo.core.MemoTarget
import com.studyink.memo.core.StudentMemoRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedMemoContinuousPlaneTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `zoomed out corners remain white and real dry ink renders beyond old paper`() {
        val repository = StudentMemoRepository(temporary.newFolder())
        val target = MemoTarget("book", 0, 1)
        val created = repository.create(target, MemoAnchor(.2f, .3f))
        val document = AnnotationDocument(created.toAnnotationSnapshot())
        document.addStroke(stroke(listOf(PagePoint(-500f, -250f), PagePoint(-350f, -250f))))
        val saved = repository.replaceStrokes(target, created.id, created.revision, document.snapshot().toMemoStrokes())
        val context = RuntimeEnvironment.getApplication()
        val host = SharedMemoCanvasHost(context)
        val dry = DryInkView(context).apply {
            viewport = host.viewport
            isolatePageBackground = false
            activePage = 0
            visibleAttemptNo = 1
            snapshot = saved.toAnnotationSnapshot()
        }
        host.addView(dry, FrameLayout.LayoutParams(-1, -1))
        host.inkLayer = dry
        host.measure(exact(900), exact(1200))
        host.layout(0, 0, 900, 1200)
        host.zoomBy(.35f)
        val midpoint = host.viewport.canonicalToView(0, PagePoint(-425f, -250f))!!
        assertTrue(midpoint.x < host.viewport.paperBounds.left)
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(bitmap))
        listOf(0 to 0, 899 to 0, 0 to 1199, 899 to 1199).forEach { (x, y) ->
            assertEquals("No gray or transparent margin after zoom-out", Color.WHITE, bitmap.getPixel(x, y))
        }
        assertTrue("Persisted exterior ink is rendered", Color.red(bitmap.getPixel(midpoint.x.toInt(), midpoint.y.toInt())) < 150)
        bitmap.recycle()
    }

    @Test fun `outside ink survives save reopen remap and erase undo without moving legacy ink`() {
        val directory = temporary.newFolder()
        val repository = StudentMemoRepository(directory)
        val target = MemoTarget("book", 0, 1)
        val created = repository.create(target, MemoAnchor(.2f, .3f))
        val document = AnnotationDocument(created.toAnnotationSnapshot())
        val original = stroke(listOf(PagePoint(100f, 100f), PagePoint(150f, 100f)))
        document.addStroke(original)
        val viewport = SharedMemoViewport().apply { updateSize(900, 1200); zoom(.35f, 450f, 600f) }
        // These real input conversions are visibly outside the original sheet after zoom-out.
        val outside = listOf(135f to 310f, 180f to 310f).map { (x, y) -> viewport.viewToCanonical(x, y)!!.point }
        assertTrue(outside.all { it.x < 0f && it.y < 0f })
        val exterior = stroke(outside)
        document.addStroke(exterior)
        val saved = repository.replaceStrokes(target, created.id, created.revision, document.snapshot().toMemoStrokes())
        assertTrue(saved.usesExtendedCanvas)
        val reopened = StudentMemoRepository(directory).memo(target, created.id)!!
        assertEquals(saved, reopened)
        val restored = reopened.toAnnotationSnapshot()
        val oldPoints = restored.activeStrokes.single { it.id == original.id }.points
        assertEquals(original.points, oldPoints)
        val outsidePoints = restored.activeStrokes.single { it.id == exterior.id }.points
        outside.zip(outsidePoints).forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, .001f)
            assertEquals(expected.y, actual.y, .001f)
        }
        val editor = AnnotationDocument(restored)
        val center = outsidePoints.first()
        val erased = editor.erase(0, listOf(center), 5f, true, "student", 1, "memo-local")!!
        assertEquals(listOf(original.id), erased.snapshot.activeStrokes.map { it.id })
        val afterErase = repository.replaceStrokes(target, created.id, saved.revision, erased.snapshot.toMemoStrokes())
        assertFalse(afterErase.usesExtendedCanvas)
        val undone = editor.undo("memo-local")!!
        val afterUndo = repository.replaceStrokes(target, created.id, afterErase.revision, undone.snapshot.toMemoStrokes())
        assertEquals(reopened.strokes, StudentMemoRepository(directory).memo(target, created.id)!!.strokes)
        assertEquals(afterUndo, repository.decodeMemo(repository.exportMemo(target, created.id)))
        viewport.zoom(2f, 450f, 600f)
        viewport.pan(100f, -70f)
        val world = viewport.worldToView(-3.0 + center.x * .03, 24.0 - center.y * .03)
        val ink = viewport.canonicalToView(0, center)!!
        assertEquals(world.x, ink.x, .002f)
        assertEquals(world.y, ink.y, .002f)
    }

    private fun stroke(points: List<PagePoint>) = StrokeAsset(
        pageNumber = 0, tool = StrokeTool.PEN, colorArgb = Color.BLACK, width = 12f,
        points = points, authorId = "student", attemptNo = 1, deviceId = "memo-local",
    )
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
