package com.studyink.reader

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.storage.ConstructionReplicaRole
import com.studyink.construction.storage.ConstructionReplicaStore
import com.studyink.construction.storage.ConstructionSceneStore
import com.studyink.construction.storage.ConstructionTarget
import com.studyink.memo.core.MemoAnchor
import com.studyink.memo.core.MemoPoint
import com.studyink.memo.core.MemoStroke
import com.studyink.memo.core.MemoTarget
import com.studyink.memo.core.MemoTool
import com.studyink.memo.core.StudentMemo
import com.studyink.memo.core.StudentMemoRepository
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test-APK-only host. No production book, memo or backup root is ever opened by these tests. */
class MemoCompositionTestActivity : Activity()

@RunWith(AndroidJUnit4::class)
class AttemptMemoCompositionIntegrationTest {
    private lateinit var scenario: ActivityScenario<MemoCompositionTestActivity>
    private lateinit var activity: Activity
    private lateinit var memoView: AttemptMemoOverlayView
    private lateinit var memos: StudentMemoRepository
    private lateinit var replicas: ConstructionReplicaStore
    private lateinit var memo: StudentMemo
    private lateinit var dataRoot: File
    private val memoTarget = MemoTarget("isolated-test-book", 2, 1)
    private val constructionTarget: ConstructionTarget get() = geometryTarget(memo)

    @Before fun setup() {
        scenario = ActivityScenario.launch(MemoCompositionTestActivity::class.java)
        scenario.onActivity { activity = it }
        dataRoot = File(activity.cacheDir, "memo-composition-test-${UUID.randomUUID()}")
        memos = StudentMemoRepository(dataRoot)
        replicas = ConstructionReplicaStore(dataRoot)
        val created = memos.create(memoTarget, MemoAnchor(.3f, .4f))
        memo = memos.replaceStrokes(memoTarget, created.id, created.revision, listOf(
            MemoStroke(tool = MemoTool.PEN, colorArgb = 0xff123456.toInt(), widthFraction = .003f,
                points = listOf(MemoPoint(.1f, .2f), MemoPoint(.7f, .8f))),
        ))
        onMain {
            memoView = AttemptMemoOverlayView(activity).apply {
                onReplaceStrokes = { target, id, revision, strokes -> memos.replaceStrokes(target, id, revision, strokes) }
                hasConstructionAttachment = { value, role -> replicas.hasAttachment(geometryTarget(value), role) }
                ensureConstructionAttachment = { value, role -> replicas.ensureAttachment(geometryTarget(value), role) }
                createConstructionEditor = { value, role ->
                    ConstructionEditorView(activity, geometryTarget(value), "메모", embedded = true,
                        store = replicas.sceneAccess(role), replicaRole = role)
                }
            }
            activity.setContentView(FrameLayout(activity).apply { addView(memoView, FrameLayout.LayoutParams(-1, -1)) })
            layout()
        }
    }

    @After fun cleanup() {
        if (::memoView.isInitialized) onMain { memoView.clearMemos() }
        if (::scenario.isInitialized) scenario.close()
        // Fresh per-test cache subtree only. Existing user filesDir/masternote is never targeted.
        if (::dataRoot.isInitialized && dataRoot.parentFile?.canonicalFile == activity.cacheDir.canonicalFile &&
            dataRoot.name.startsWith("memo-composition-test-")) dataRoot.deleteRecursively()
    }

    @Test fun existingDrawingOpensBesideReadOnlyTeacherInkWithoutChangingBytesOrAspect() {
        val legacy = ConstructionSceneStore(dataRoot)
        legacy.save(legacy.load(constructionTarget), ConstructionScene(points = listOf(GeometryPoint("A", 3.0, 4.0))))
        val originalInk = memos.exportMemo(memoTarget, memo.id)
        onMain {
            memoView.updateConstructionRole(ConstructionReplicaRole.TEACHER)
            memoView.showMemos(memoTarget, listOf(memo), studentWritable = false)
            assertTrue(memoView.openMemo(memo.id))
        }
        await { editor()?.hasPendingWork == false }
        onMain {
            val ink = walk(memoView).filterIsInstance<InkInputView>().single()
            assertFalse(ink.isEnabled)
            val sheet = ink.parent as View
            assertTrue(sheet.width > 0)
            assertEquals(2.2, sheet.height.toDouble() / sheet.width, .01)
            canvas().onPoint(ConstructionAnchor(8.0, 9.0))
        }
        await { editor()?.hasPendingWork == false && canvas().scene.points.size == 2 }
        onMain { assertFalse(walk(memoView).filterIsInstance<InkInputView>().single().isEnabled) }
        assertArrayEquals(originalInk, memos.exportMemo(memoTarget, memo.id))
        assertTrue(replicas.load(constructionTarget, ConstructionReplicaRole.TEACHER).draftDirty)
    }

    @Test fun addingAnEmptyDrawingIsDurableAndPreservesOriginalStudentInk() {
        val originalInk = memos.exportMemo(memoTarget, memo.id)
        onMain { memoView.showMemos(memoTarget, listOf(memo), studentWritable = true); memoView.openMemo(memo.id) }
        await { walk(memoView).single { it.contentDescription == "메모에 도형 작도판 넣기" }.isEnabled }
        onMain {
            assertNull(editor())
            walk(memoView).single { it.contentDescription == "메모에 도형 작도판 넣기" }.performClick()
        }
        await { editor()?.hasPendingWork == false }
        assertTrue(replicas.hasAttachment(constructionTarget, ConstructionReplicaRole.STUDENT))
        assertEquals(ConstructionScene(), replicas.load(constructionTarget, ConstructionReplicaRole.STUDENT).scene)
        onMain { assertTrue(memoView.minimizeEditor()); assertTrue(memoView.openMemo(memo.id)) }
        await { editor()?.hasPendingWork == false }
        assertArrayEquals(originalInk, memos.exportMemo(memoTarget, memo.id))
    }

    @Test fun authoritativeMemoDeletionClosesEditorEvenWhileGeometryWorkIsQueued() {
        replicas.ensureAttachment(constructionTarget, ConstructionReplicaRole.STUDENT)
        onMain { memoView.showMemos(memoTarget, listOf(memo), studentWritable = true); memoView.openMemo(memo.id) }
        await { editor()?.hasPendingWork == false }
        onMain {
            canvas().onPoint(ConstructionAnchor(2.0, 4.0))
            memoView.showMemos(memoTarget, emptyList(), studentWritable = false)
            assertFalse(memoView.editorVisible)
            assertNull(memoView.activeMemoId)
            assertNull(editor())
        }
    }

    private fun geometryTarget(value: StudentMemo) = ConstructionTarget(
        value.target.bookId, value.target.pageNumber, value.target.attemptNo, value.id,
    )
    private fun editor() = walk(memoView).filterIsInstance<ConstructionEditorView>().singleOrNull()
    private fun canvas() = walk(memoView).filterIsInstance<ConstructionCanvasView>().single()
    private fun layout() {
        val width = activity.resources.displayMetrics.widthPixels
        val height = activity.resources.displayMetrics.heightPixels
        memoView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        memoView.layout(0, 0, width, height)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (onMain { layout(); condition() }) return
            Thread.sleep(10)
        }
        fail("Memo composition did not reach a stable UI state")
    }
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun walk(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(walk(view.getChildAt(index)))
    }
}
