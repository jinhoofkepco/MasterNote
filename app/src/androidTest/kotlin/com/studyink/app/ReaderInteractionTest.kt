package com.studyink.app

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.studyink.annotation.storage.CorruptAnnotationDataException
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.MarkColor
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.library.data.LibraryRepository
import com.studyink.reader.DryInkView
import com.studyink.reader.EraserGesture
import com.studyink.reader.EraserPreview
import com.studyink.reader.InkInputView
import com.studyink.reader.QuickShapePreview
import com.studyink.reader.ReaderActivity
import com.studyink.reader.ReaderDebugSessionStore
import com.studyink.reader.ReaderRole
import com.studyink.reader.ReaderTool
import com.studyink.reader.ReaderUiState
import com.studyink.reader.ReaderViewModel
import com.studyink.reader.ReaderWorkflow
import com.studyink.sync.lan.PairingPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class ReaderInteractionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var scenario: ActivityScenario<ReaderActivity>
    private lateinit var bookId: String

    @Before
    fun launchReader() {
        val source = createPdf(File(context.cacheDir, "reader-${System.nanoTime()}.pdf"), 3)
        val repository = LibraryRepository.get(context)
        bookId = repository.importPdf(repository.state.selectedStudentId, Uri.fromFile(source), "기기 시험 교재").id
        assertEquals(64, repository.book(bookId).contentSha256.length)
        scenario = ActivityScenario.launch(ReaderActivity.intent(context, bookId, 0))
        waitForBook()
    }

    @After
    fun closeReader() { scenario.close() }

    @Test
    fun fingerDoesNotWriteButStylusDoes() {
        val before = revision()
        dispatchStroke(InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 320f, 760f, 430f, 790f)
        SystemClock.sleep(300)
        assertEquals(before, revision())
        dispatchStroke(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 360f, 820f, 520f, 870f)
        assertTrue(waitForRevisionAfter(before))
    }

    @Test
    fun penLineHeldForTwoSecondsCommitsOneSnappedStroke() {
        val committed = mutableListOf<StrokeAsset>()
        val previews = mutableListOf<QuickShapePreview?>()
        scenario.onActivity { activity ->
            activity.findInkInputView().apply {
                tool = ReaderTool.PEN
                quickShapeEnabled = true
                onQuickShapePreview = previews::add
                onStrokeAwaitingPersistence = { stroke, complete ->
                    committed += stroke
                    complete()
                }
            }
        }

        val downAt = SystemClock.uptimeMillis()
        val events = buildList {
            add(
                motionEvent(
                    MotionEvent.ACTION_DOWN,
                    InputDevice.SOURCE_STYLUS,
                    MotionEvent.TOOL_TYPE_STYLUS,
                    360f,
                    820f,
                    eventTime = downAt,
                )
            )
            repeat(20) { index ->
                val progress = (index + 1) / 20f
                add(
                    motionEvent(
                        MotionEvent.ACTION_MOVE,
                        InputDevice.SOURCE_STYLUS,
                        MotionEvent.TOOL_TYPE_STYLUS,
                        360f + 260f * progress,
                        820f + 50f * progress,
                        eventTime = downAt + (index + 1) * 20L,
                    )
                )
            }
            // Drawing ends at +400 ms, so this UP is at least 2,000 ms after the last meaningful
            // movement on every screen density. Synchronous dispatch exercises ACTION_UP settling.
            add(
                motionEvent(
                    MotionEvent.ACTION_UP,
                    InputDevice.SOURCE_STYLUS,
                    MotionEvent.TOOL_TYPE_STYLUS,
                    620f,
                    870f,
                    eventTime = downAt + 2_400L,
                )
            )
        }
        scenario.onActivity { activity -> events.forEach(activity::dispatchTouchEvent) }
        events.forEach(MotionEvent::recycle)

        assertEquals(1, committed.size)
        assertEquals(StrokeTool.PEN, committed.single().tool)
        assertEquals(2, committed.single().points.size)
        assertTrue(previews.filterNotNull().any { it.path.size == 2 })
    }

    @Test
    fun rapidStrokesAfterSubmissionBothLandInTheSameNewAttempt() {
        val repository = LibraryRepository.get(context)
        val submitted = requireNotNull(repository.writableAttempt(bookId, 0, create = true))
        repository.lockAttempt(bookId, 0, submitted.attemptNo)
        scenario.close()
        scenario = ActivityScenario.launch(ReaderActivity.intent(context, bookId, 0))
        assertTrue(
            waitForReaderState { state ->
                state.documentReady && state.attemptNo == submitted.attemptNo &&
                    state.currentAttemptSubmitted
            }
        )

        val startedAt = SystemClock.uptimeMillis()
        val firstStroke = listOf(
            motionEvent(
                MotionEvent.ACTION_DOWN,
                InputDevice.SOURCE_STYLUS,
                MotionEvent.TOOL_TYPE_STYLUS,
                360f,
                820f,
                eventTime = startedAt,
            ),
            motionEvent(
                MotionEvent.ACTION_MOVE,
                InputDevice.SOURCE_STYLUS,
                MotionEvent.TOOL_TYPE_STYLUS,
                500f,
                850f,
                eventTime = startedAt + 20L,
            ),
            motionEvent(
                MotionEvent.ACTION_UP,
                InputDevice.SOURCE_STYLUS,
                MotionEvent.TOOL_TYPE_STYLUS,
                500f,
                850f,
                eventTime = startedAt + 40L,
            ),
        )
        val secondDown = motionEvent(
            MotionEvent.ACTION_DOWN,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            390f,
            900f,
            eventTime = startedAt + 60L,
        )
        // The UI collector cannot publish N+1 in the middle of this main-loop callback, so the
        // second gesture deterministically captures submitted N while the first append is queued.
        scenario.onActivity { activity ->
            firstStroke.forEach(activity::dispatchTouchEvent)
            activity.dispatchTouchEvent(secondDown)
        }
        firstStroke.forEach(MotionEvent::recycle)
        secondDown.recycle()

        assertTrue(
            waitForReaderState { state ->
                state.attemptNo == submitted.attemptNo + 1 && state.currentAttemptWritable
            }
        )
        scenario.onActivity { activity -> assertTrue(activity.findInkInputView().hasActiveGesture) }

        val secondFinishedAt = SystemClock.uptimeMillis()
        val secondTail = listOf(
            motionEvent(
                MotionEvent.ACTION_MOVE,
                InputDevice.SOURCE_STYLUS,
                MotionEvent.TOOL_TYPE_STYLUS,
                560f,
                930f,
                eventTime = secondFinishedAt,
            ),
            motionEvent(
                MotionEvent.ACTION_UP,
                InputDevice.SOURCE_STYLUS,
                MotionEvent.TOOL_TYPE_STYLUS,
                560f,
                930f,
                eventTime = secondFinishedAt + 20L,
            ),
        )
        scenario.onActivity { activity -> secondTail.forEach(activity::dispatchTouchEvent) }
        secondTail.forEach(MotionEvent::recycle)

        assertTrue(waitForStudentStrokeCount(attemptNo = submitted.attemptNo + 1, count = 2))
        assertEquals(listOf(submitted.attemptNo, submitted.attemptNo + 1), repository
            .attempts(bookId, 0)
            .map { it.attemptNo })
    }

    @Test
    fun stylusSideButtonOpensTheExistingFanMenu() {
        val event = motionEvent(
            MotionEvent.ACTION_BUTTON_PRESS, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS,
            520f, 920f, MotionEvent.BUTTON_STYLUS_PRIMARY,
        )
        scenario.onActivity { assertTrue(it.dispatchGenericMotionEvent(event)) }
        event.recycle()
        assertTrue(device.wait(Until.hasObject(By.desc("펜")), 3_000))
        assertTrue(device.hasObject(By.desc("형광펜")))
        assertTrue(device.hasObject(By.desc("지우개")))
        assertTrue(device.hasObject(By.desc("되돌리기")))
        assertFalse(device.hasObject(By.desc("선생 모드")))
        assertFalse(device.hasObject(By.desc("페이지 맞춤")))
    }

    @Test
    fun penCenterOpensSettingsAndBackReturnsToTheSameFan() {
        clickStylusSideButton()
        assertTrue(device.wait(Until.hasObject(By.desc("펜")), 3_000))

        val pen = device.findObject(By.desc("펜")).visibleBounds
        injectStylusTapOnScreen(pen.centerX().toFloat(), pen.centerY().toFloat())
        assertTrue(device.wait(Until.hasObject(By.desc("선 굵기 3.2")), 3_000))
        assertTrue(device.hasObject(By.desc("도구 메뉴로 돌아가기")))

        val back = device.findObject(By.desc("도구 메뉴로 돌아가기")).visibleBounds
        injectStylusTapOnScreen(back.centerX().toFloat(), back.centerY().toFloat())
        assertTrue(device.wait(Until.hasObject(By.desc("색상 팔레트")), 3_000))
    }

    @Test
    fun protrudingPenArtworkBeyondTheOldCircleOpensSettings() {
        clickStylusSideButton()
        assertTrue(device.wait(Until.hasObject(By.desc("펜")), 3_000))

        val eraser = device.findObject(By.desc("지우개")).visibleBounds
        val pen = device.findObject(By.desc("펜")).visibleBounds
        val highlighter = device.findObject(By.desc("형광펜")).visibleBounds
        val origin = circumcenter(
            eraser.exactCenterX() to eraser.exactCenterY(),
            pen.exactCenterX() to pen.exactCenterY(),
            highlighter.exactCenterX() to highlighter.exactCenterY(),
        )
        val centerX = pen.exactCenterX()
        val centerY = pen.exactCenterY()
        val radius = hypot(centerX - origin.first, centerY - origin.second)
        val directionX = (centerX - origin.first) / radius
        val directionY = (centerY - origin.second) / radius

        // The old circular hit target ends at 0.5 * width. 0.72 * width lands in the b..c
        // protrusion corridor on both compact and expanded token sets.
        val beyondOldCircle = pen.width() * 0.72f
        injectStylusTapOnScreen(
            centerX + directionX * beyondOldCircle,
            centerY + directionY * beyondOldCircle,
        )

        assertTrue(
            "돌출된 펜 그림(b..c)을 눌러도 굵기 메뉴가 열려야 합니다",
            device.wait(Until.hasObject(By.desc("선 굵기 3.2")), 3_000),
        )
    }

    @Test
    fun stylusSideButtonTogglesTheFanMenuOnEveryOtherPress() {
        // One press opens, the next closes, however many times it is repeated. This is dispatched
        // as a press followed by a release because the menu only counts a press once the previous
        // one has been let go - without that the single press that opens the menu closes it again.
        repeat(2) { round ->
            clickStylusSideButton()
            assertTrue("round $round: menu did not open", device.wait(Until.hasObject(FAN_ONLY), 3_000))
            clickStylusSideButton()
            assertTrue("round $round: menu did not close", device.wait(Until.gone(FAN_ONLY), 3_000))
        }
    }

    @Test
    fun heldHoverAndExplicitPressFromOnePhysicalClickToggleOnlyOnce() {
        val heldHover = motionEvent(
            MotionEvent.ACTION_HOVER_MOVE,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            520f,
            920f,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
        )
        scenario.onActivity { assertTrue(it.dispatchGenericMotionEvent(heldHover)) }
        heldHover.recycle()
        assertTrue(device.wait(Until.hasObject(FAN_ONLY), 3_000))

        val duplicateExplicitPress = motionEvent(
            MotionEvent.ACTION_BUTTON_PRESS,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            520f,
            920f,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
        )
        scenario.onActivity { assertTrue(it.dispatchGenericMotionEvent(duplicateExplicitPress)) }
        duplicateExplicitPress.recycle()
        assertTrue("the duplicate event must not close the menu", device.hasObject(FAN_ONLY))

        val release = motionEvent(
            MotionEvent.ACTION_BUTTON_RELEASE,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            520f,
            920f,
        )
        scenario.onActivity { assertTrue(it.dispatchGenericMotionEvent(release)) }
        release.recycle()
        clickStylusSideButton()
        assertTrue(device.wait(Until.gone(FAN_ONLY), 3_000))
    }

    @Test
    fun contactAtThePhysicalPenAnchorClosesMenuAndKeepsTheFirstStroke() {
        clickStylusSideButton()
        assertTrue(device.wait(Until.hasObject(FAN_ONLY), 3_000))
        val before = revision()

        // The physical pen coordinate is deliberately inside radius a (at 0.66a), not at the fan
        // centre and not inside the menu-owned annulus. That same DOWN must close the menu and be
        // delivered to InkInputView instead of disappearing with the overlay.
        dispatchStroke(
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            520f,
            920f,
            560f,
            955f,
        )

        assertTrue(device.wait(Until.gone(FAN_ONLY), 3_000))
        assertTrue(waitForRevisionAfter(before))
    }

    @Test
    fun debugBuildBypassesTeacherPinForFastReaderEntry() {
        assertTrue(ReaderDebugSessionStore.isEnabled(context))
        scenario.close()
        scenario = ActivityScenario.launch(ReaderActivity.intent(context, bookId, 1, ReaderRole.TEACHER_TABLET))
        waitForBook()
        val event = motionEvent(
            MotionEvent.ACTION_BUTTON_PRESS, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS,
            520f, 920f, MotionEvent.BUTTON_STYLUS_PRIMARY,
        )
        scenario.onActivity { assertTrue(it.dispatchGenericMotionEvent(event)) }
        event.recycle()
        assertTrue(device.wait(Until.hasObject(By.desc("채점")), 3_000))
        assertFalse(device.hasObject(By.desc("학생 모드")))
        assertFalse(device.hasObject(By.desc("페이지 맞춤")))
    }

    @Test
    fun eraserPublishesOnlyPreviewUntilPenUpThenEmitsOneOperation() {
        val previews = mutableListOf<EraserPreview?>()
        val operations = mutableListOf<EraserGesture>()
        scenario.onActivity { activity ->
            activity.findInkInputView().apply {
                tool = ReaderTool.PARTIAL_ERASER
                canStartErase = { true }
                onEraserPreview = previews::add
                onErase = operations::add
            }
        }

        val downAt = SystemClock.uptimeMillis()
        val down = motionEvent(
            MotionEvent.ACTION_DOWN,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            360f,
            820f,
            eventTime = downAt,
        )
        val move = motionEvent(
            MotionEvent.ACTION_MOVE,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            520f,
            870f,
            eventTime = downAt + 20,
        )
        scenario.onActivity { activity ->
            activity.dispatchTouchEvent(down)
            assertTrue("DOWN should publish the painted eraser corridor", previews.filterNotNull().isNotEmpty())
            assertTrue("DOWN must not mutate annotations", operations.isEmpty())
            activity.dispatchTouchEvent(move)
            assertTrue("MOVE must not mutate annotations", operations.isEmpty())
        }
        down.recycle()
        move.recycle()

        val previewCountBeforeUp = previews.filterNotNull().size
        val up = motionEvent(
            MotionEvent.ACTION_UP,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            520f,
            870f,
            eventTime = downAt + 40,
        )
        scenario.onActivity { activity -> activity.dispatchTouchEvent(up) }
        up.recycle()

        assertEquals(1, operations.size)
        assertTrue(previews.filterNotNull().size > previewCountBeforeUp)
        val gesture = operations.single()
        assertTrue(gesture.id > 0L)
        assertEquals(gesture.id, previews.filterNotNull().last().gestureId)
        assertEquals(gesture.page, previews.filterNotNull().last().pageNumber)
    }

    @Test
    fun blockedEraserConsumesTheStylusStreamWithoutPreviewOrOperation() {
        val previews = mutableListOf<EraserPreview?>()
        val operations = mutableListOf<EraserGesture>()
        scenario.onActivity { activity ->
            activity.findInkInputView().apply {
                tool = ReaderTool.WHOLE_ERASER
                canStartErase = { false }
                onEraserPreview = previews::add
                onErase = operations::add
            }
        }

        dispatchStroke(
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            360f,
            820f,
            520f,
            870f,
        )

        assertTrue(previews.isEmpty())
        assertTrue(operations.isEmpty())
    }

    @Test
    fun interruptedEraserClearsItsCorridorWithoutCommitting() {
        val previews = mutableListOf<EraserPreview?>()
        val operations = mutableListOf<EraserGesture>()
        val downAt = SystemClock.uptimeMillis()
        val down = motionEvent(
            MotionEvent.ACTION_DOWN,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            360f,
            820f,
            eventTime = downAt,
        )
        scenario.onActivity { activity ->
            activity.findInkInputView().apply {
                tool = ReaderTool.PARTIAL_ERASER
                canStartErase = { true }
                onEraserPreview = previews::add
                onErase = operations::add
            }
            activity.dispatchTouchEvent(down)
            assertTrue(previews.filterNotNull().isNotEmpty())
            assertTrue(activity.findInkInputView().cancelActiveEraserGesture())
        }
        down.recycle()

        val up = motionEvent(
            MotionEvent.ACTION_UP,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            520f,
            870f,
            eventTime = downAt + 40,
        )
        scenario.onActivity { activity -> activity.dispatchTouchEvent(up) }
        up.recycle()

        assertNull(previews.last())
        assertTrue(operations.isEmpty())
    }

    @Test
    fun lateCompletionForOlderEraserGestureCannotClearNewerPreview() {
        var visiblePreview: EraserPreview? = null
        val pendingCompletions = mutableListOf<() -> Unit>()
        val completedGestures = mutableListOf<EraserGesture>()
        scenario.onActivity { activity ->
            activity.findInkInputView().apply {
                tool = ReaderTool.PARTIAL_ERASER
                canStartErase = { true }
                onEraserPreview = { preview -> if (preview != null) visiblePreview = preview }
                onErase = { gesture ->
                    completedGestures += gesture
                    pendingCompletions += {
                        if (visiblePreview?.gestureId == gesture.id) visiblePreview = null
                    }
                }
            }
        }

        dispatchStroke(
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            360f,
            820f,
            440f,
            850f,
        )
        dispatchStroke(
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            460f,
            840f,
            540f,
            880f,
        )

        assertEquals(2, completedGestures.size)
        assertTrue(completedGestures[0].id != completedGestures[1].id)
        assertEquals(completedGestures[1].id, visiblePreview?.gestureId)
        pendingCompletions[0]()
        assertEquals(completedGestures[1].id, visiblePreview?.gestureId)
        pendingCompletions[1]()
        assertNull(visiblePreview)
    }

    @Test
    fun compactExpandedChromeKeepsLongTitleInsideNavigationBounds() {
        LibraryRepository.get(context).renameBook(
            bookId,
            "아주 긴 영어 문제집 단원명 — 문장 구조와 어휘 연습",
        )
        scenario.close()
        scenario = ActivityScenario.launch(ReaderActivity.intent(context, bookId, 0))
        waitForBook()
        val menu = device.findObject(By.desc("상단 메뉴 열기")).visibleBounds
        dispatchTap(
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            menu.centerX().toFloat(),
            menu.centerY().toFloat(),
        )
        assertTrue(device.wait(Until.hasObject(By.desc("교재 페이지로 돌아가기")), 3_000))

        val previous = device.findObject(By.desc("이전 페이지")).visibleBounds
        val contextButton = device.findObject(By.desc("교재 페이지로 돌아가기")).visibleBounds
        val submit = device.findObject(By.desc("현재 페이지 제출")).visibleBounds
        val close = device.findObject(By.desc("상단 메뉴 닫기")).visibleBounds
        val next = device.findObject(By.desc("다음 페이지")).visibleBounds
        val layout = "previous=$previous context=$contextButton submit=$submit close=$close next=$next"
        assertTrue(layout, previous.right <= contextButton.left)
        assertTrue(layout, contextButton.right <= submit.left)
        assertTrue(layout, submit.right <= close.left)
        assertTrue(layout, close.right <= next.left)
    }

    @Test
    fun liveMonitorManualPageNavigationPausesAndCanResumeStudentFollow() {
        scenario.close()
        scenario = ActivityScenario.launch(
            ReaderActivity.intent(
                context = context,
                bookId = bookId,
                pageNumber = 0,
                role = ReaderRole.TEACHER_PHONE,
                workflow = ReaderWorkflow.LIVE_MONITOR,
            )
        )
        waitForBook()

        val menu = device.findObject(By.desc("상단 메뉴 열기")).visibleBounds
        injectStylusTapOnScreen(menu.centerX().toFloat(), menu.centerY().toFloat())
        assertTrue(device.wait(Until.hasObject(By.desc("학생 기기 연결 끊김")), 3_000))

        val next = device.findObject(By.desc("다음 페이지")).visibleBounds
        injectStylusTapOnScreen(next.centerX().toFloat(), next.centerY().toFloat())
        assertTrue(device.wait(Until.hasObject(By.desc("학생 화면 다시 따라가기")), 3_000))
        scenario.onActivity { assertEquals(1, it.findDryInkView().activePage) }

        val resume = device.findObject(By.desc("학생 화면 다시 따라가기")).visibleBounds
        injectStylusTapOnScreen(resume.centerX().toFloat(), resume.centerY().toFloat())
        assertTrue(device.wait(Until.hasObject(By.desc("학생 기기 연결 끊김")), 3_000))
    }

    @Test
    fun stylusHoverShowsCurrentToolRing() {
        val event = motionEvent(
            MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS,
            400f, 600f,
        )
        scenario.onActivity { it.dispatchGenericMotionEvent(event) }
        event.recycle()
        scenario.onActivity { assertNotNull(it.findDryInkView().hoverPreview) }
    }

    @Test
    fun gradeToolRecognizesTenOfTenTapsAndLongPressBeforePenUp() {
        val taps = AtomicInteger(0)
        val longPressed = AtomicBoolean(false)
        val before = revision()
        scenario.onActivity { activity ->
            activity.findInkInputView().apply {
                tool = ReaderTool.GRADE
                onGradeTap = { _, _, _, _, _ -> taps.incrementAndGet() }
                onGradeLongPress = { _, _, _, _ -> longPressed.set(true) }
            }
        }
        repeat(10) {
            dispatchTap(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 440f, 720f)
            SystemClock.sleep(380)
        }
        assertEquals(10, taps.get())
        assertEquals(before, revision())

        val downAt = SystemClock.uptimeMillis()
        val down = motionEvent(
            MotionEvent.ACTION_DOWN,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            480f,
            760f,
            eventTime = downAt,
        )
        scenario.onActivity { it.dispatchTouchEvent(down) }
        down.recycle()
        SystemClock.sleep(650)
        assertTrue("롱프레스는 펜을 떼기 전에 발생해야 합니다", longPressed.get())
        val up = motionEvent(
            MotionEvent.ACTION_UP,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            480f,
            760f,
            eventTime = downAt + 700,
        )
        scenario.onActivity { it.dispatchTouchEvent(up) }
        up.recycle()
        assertEquals(10, taps.get())
    }

    @Test
    fun rightmostMarkCellUsesWholeGroupHitboxAndOpensItsAttempt() {
        val repository = LibraryRepository.get(context)
        repeat(3) { index ->
            val attempt = requireNotNull(repository.writableAttempt(bookId, 0, create = true))
            assertEquals(index + 1, attempt.attemptNo)
            repository.lockAttempt(bookId, 0, attempt.attemptNo)
        }
        val first = repository.addMark(bookId, 0, 1, PagePoint(220f, 300f), MarkColor.BLUE)
        repository.addMark(bookId, 0, 2, first.anchor, MarkColor.RED, first.id)
        repository.addMark(bookId, 0, 3, first.anchor, MarkColor.BLUE, first.id)
        scenario.onActivity { activity ->
            activity.findDryInkView().apply {
                visibleAttemptNo = 3
                markGroups = repository.markGroups(bookId, 0)
                invalidate()
            }
        }
        SystemClock.sleep(250)

        var hitX = -1f
        var hitY = -1f
        scenario.onActivity { activity ->
            val dry = activity.findDryInkView()
            search@ for (y in 80 until dry.height step 3) {
                for (x in 0 until dry.width step 3) {
                    if (dry.markedAttemptAt(x.toFloat(), y.toFloat()) == 3) {
                        hitX = x.toFloat()
                        hitY = y.toFloat()
                        break@search
                    }
                }
            }
            assertEquals(first.id, dry.markGroupAt(hitX, hitY))
        }
        assertTrue(hitX >= 0f && hitY >= 0f)

        val openedAttempt = AtomicInteger(0)
        val before = revision()
        scenario.onActivity { activity ->
            activity.findInkInputView().apply {
                tool = ReaderTool.PEN
                findMarkAttempt = activity.findDryInkView()::markedAttemptAt
                onOpenMarkedAttempt = openedAttempt::set
            }
        }
        dispatchTap(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, hitX, hitY)
        SystemClock.sleep(100)
        assertEquals(3, openedAttempt.get())
        assertEquals(before, revision())
    }

    @Test
    fun teacherCanGradeAnUnstartedPageWithoutTakingTheStudentsFirstAttempt() {
        val repository = LibraryRepository.get(context)
        assertTrue(repository.attempts(bookId, 0).isEmpty())
        scenario.close()
        scenario = ActivityScenario.launch(
            ReaderActivity.intent(
                context = context,
                bookId = bookId,
                pageNumber = 0,
                role = ReaderRole.TEACHER_TABLET,
                attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
                workflow = ReaderWorkflow.REVIEW,
            )
        )
        waitForBook()
        selectToolFromFan("채점")
        scenario.onActivity { assertEquals(ReaderTool.GRADE, it.findInkInputView().tool) }

        dispatchTap(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 440f, 720f)
        repeat(30) {
            if (repository.markGroups(bookId, 0).any { group ->
                    group.marks.any { mark -> mark.attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO }
                }
            ) return@repeat
            SystemClock.sleep(100)
        }
        assertTrue(repository.attempts(bookId, 0).isEmpty())
        assertTrue(
            repository.markGroups(bookId, 0).any { group ->
                group.marks.any { mark -> mark.attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO }
            }
        )

        scenario.close()
        scenario = ActivityScenario.launch(
            ReaderActivity.intent(
                context = context,
                bookId = bookId,
                pageNumber = 0,
                role = ReaderRole.STUDENT,
                workflow = ReaderWorkflow.STUDY,
            )
        )
        waitForBook()
        val before = revision()
        dispatchStroke(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 360f, 820f, 520f, 870f)
        assertTrue(waitForRevisionAfter(before))
        assertEquals(listOf(1), repository.attempts(bookId, 0).map { it.attemptNo })
        assertTrue(
            repository.markGroups(bookId, 0).flatMap { it.marks }
                .all { it.attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO }
        )
    }

    @Test
    fun pageNavigationIgnoresFingerAndAcceptsStylus() {
        assertTrue(device.wait(Until.hasObject(By.desc("다음 페이지")), 3_000))
        val bounds = device.findObject(By.desc("다음 페이지")).visibleBounds
        var localX = bounds.centerX().toFloat()
        var localY = bounds.centerY().toFloat()
        scenario.onActivity { activity ->
            val origin = IntArray(2)
            activity.window.decorView.getLocationOnScreen(origin)
            localX -= origin[0]
            localY -= origin[1]
        }
        dispatchTap(InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, localX, localY)
        SystemClock.sleep(300)
        scenario.onActivity { assertEquals(0, it.findDryInkView().activePage) }
        dispatchDownInsideUpOutside(localX, localY)
        SystemClock.sleep(300)
        scenario.onActivity { assertEquals(0, it.findDryInkView().activePage) }
        dispatchTap(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, localX, localY)
        repeat(30) {
            var page = 0
            scenario.onActivity { page = it.findDryInkView().activePage }
            if (page == 1) return
            SystemClock.sleep(100)
        }
        throw AssertionError("S펜 페이지 버튼이 동작하지 않았습니다")
    }

    @Test
    fun currentPageMaskIsOpaqueOutsidePageAndTransparentInside() {
        scenario.onActivity { activity ->
            val dryInk = activity.findDryInkView()
            val page = requireNotNull(dryInk.viewport?.activePageBounds())
            val bitmap = Bitmap.createBitmap(dryInk.width, dryInk.height, Bitmap.Config.ARGB_8888)
            dryInk.draw(Canvas(bitmap))

            val insideX = page.centerX().toInt().coerceIn(1, bitmap.width - 2)
            val insideY = page.centerY().toInt().coerceIn(1, bitmap.height - 2)
            assertEquals(0, Color.alpha(bitmap.getPixel(insideX, insideY)))

            val outside = when {
                page.top >= 4f -> insideX to (page.top / 2f).toInt()
                page.bottom <= bitmap.height - 4f ->
                    insideX to ((page.bottom + bitmap.height) / 2f).toInt()
                page.left >= 4f -> (page.left / 2f).toInt() to insideY
                page.right <= bitmap.width - 4f ->
                    ((page.right + bitmap.width) / 2f).toInt() to insideY
                else -> error("Test PDF page unexpectedly covers the complete reader viewport: $page")
            }
            assertEquals(255, Color.alpha(bitmap.getPixel(outside.first, outside.second)))
            bitmap.recycle()
        }
    }

    @Test
    fun annotationsRestoreFromTheBookPageLog() {
        val before = revision()
        dispatchStroke(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 330f, 780f, 520f, 820f)
        assertTrue(waitForRevisionAfter(before))
        scenario.close()
        scenario = ActivityScenario.launch(ReaderActivity.intent(context, bookId, 0))
        waitForBook()
        scenario.onActivity { assertTrue(it.findDryInkView().snapshot.activeStrokes.isNotEmpty()) }
    }

    @Test
    fun corruptLogIsQuarantinedAndNeverOpenedAsEmpty() {
        val store = PageOperationLogStore(context)
        val log = store.operationLogFile("corruption-${System.nanoTime()}", 0)
        log.writeText("{not-json}\n")
        val pageDirectory = requireNotNull(log.parentFile)
        val pagesDirectory = requireNotNull(pageDirectory.parentFile)
        val bookDirectory = requireNotNull(pagesDirectory.parentFile)
        val error = runCatching { store.loadPage(bookDirectory.name, 0) }.exceptionOrNull()
        assertTrue(error is CorruptAnnotationDataException)
        assertTrue(pageDirectory.listFiles().orEmpty().any { it.name.contains(".corrupt-") })
    }

    @Test
    fun fiveSubmittedAttemptsRemainIndividuallyAddressable() {
        val repository = LibraryRepository.get(context)
        repeat(5) { index ->
            val attempt = requireNotNull(repository.writableAttempt(bookId, 1, create = true))
            assertEquals(index + 1, attempt.attemptNo)
            repository.lockAttempt(bookId, 1, attempt.attemptNo)
        }
        val attempts = repository.attempts(bookId, 1)
        assertEquals(listOf(1, 2, 3, 4, 5), attempts.map { it.attemptNo })
        assertTrue(attempts.all { it.locked })
    }

    @Test
    fun duplicateRemoteOperationIsAppliedOnlyOnce() {
        val operationBookId = "remote-${System.nanoTime()}"
        val localReplicaBookId = "local-${System.nanoTime()}"
        val sourceRoot = File(context.cacheDir, "sync-source-${System.nanoTime()}")
        val targetRoot = File(context.cacheDir, "sync-target-${System.nanoTime()}")
        val sourceStore = PageOperationLogStore(sourceRoot)
        val targetStore = PageOperationLogStore(targetRoot)
        val sourceDocument = AnnotationDocument(AnnotationSnapshot.empty(operationBookId, 0))
        sourceStore.append(
            sourceDocument.addStroke(
                StrokeAsset(
                    pageNumber = 0,
                    tool = StrokeTool.PEN,
                    colorArgb = Color.BLACK,
                    width = 4f,
                    points = listOf(PagePoint(100f, 100f), PagePoint(200f, 200f)),
                    deviceId = "student-device",
                )
            )
        )
        val encoded = sourceStore.encodedOperationsAfter(operationBookId, 0, 0L).single()
        assertEquals(1L, targetStore.appendEncodedOperation(localReplicaBookId, 0, encoded))
        assertEquals(1L, targetStore.appendEncodedOperation(localReplicaBookId, 0, encoded))
        val restored = targetStore.loadPage(localReplicaBookId, 0)
        assertEquals(1L, restored.revision)
        assertEquals(1, restored.activeStrokes.size)
        sourceRoot.deleteRecursively()
        targetRoot.deleteRecursively()
    }

    @Test
    fun teacherDraftIsNotTransmittedUntilPublishAndGcKeepsMergeHistory() {
        val syncBookId = "teacher-sync-${System.nanoTime()}"
        val teacherDevice = "teacher-device"
        val sourceRoot = File(context.cacheDir, "teacher-sync-source-${System.nanoTime()}")
        val targetRoot = File(context.cacheDir, "teacher-sync-target-${System.nanoTime()}")
        val sourceStore = PageOperationLogStore(sourceRoot)
        val targetStore = PageOperationLogStore(targetRoot)
        val document = AnnotationDocument(AnnotationSnapshot.empty(syncBookId, 0))
        val draft = document.addStroke(
            StrokeAsset(
                pageNumber = 0,
                tool = StrokeTool.PEN,
                colorArgb = Color.RED,
                width = 4f,
                points = listOf(PagePoint(100f, 100f), PagePoint(180f, 180f)),
                authorId = "teacher",
                attemptNo = 1,
                deviceId = teacherDevice,
            )
        )
        sourceStore.append(draft)

        assertTrue(sourceStore.encodedOperationsAfter(syncBookId, 0, teacherDevice, 0L, false).isEmpty())

        val published = requireNotNull(document.publishTeacherDrafts(1, teacherDevice))
        sourceStore.append(published)
        val outgoing = sourceStore.encodedOperationsAfter(syncBookId, 0, teacherDevice, 0L, false)
        assertEquals(1, outgoing.size)
        targetStore.appendEncodedOperation("teacher-sync-target", 0, outgoing.single())
        val received = targetStore.loadPage("teacher-sync-target", 0).activeStrokes.single()
        assertEquals("teacher", received.authorId)
        assertNotNull(received.publishedAtEpochMillis)

        sourceStore.garbageCollectOrphans(document.snapshot(), emptySet())
        assertEquals(2, sourceStore.encodedOperationsAfter(syncBookId, 0, 0L).size)
        sourceRoot.deleteRecursively()
        targetRoot.deleteRecursively()
    }

    @Test
    fun pairingQrPayloadRoundTripsWithoutChangingBookIdentity() {
        val expected = PairingPayload("192.168.0.12", 48123, bookId, "pair-1234")
        assertEquals(expected, PairingPayload.parse(expected.toUri()))
    }

    private fun waitForBook() {
        repeat(100) {
            var ready = false
            scenario.onActivity { ready = it.findDryInkView().snapshot.bookId == bookId }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Book UUID page did not load")
    }

    private fun revision(): Long {
        var value = -1L
        scenario.onActivity { value = it.findDryInkView().snapshot.revision }
        return value
    }

    private fun waitForRevisionAfter(before: Long): Boolean {
        repeat(60) { if (revision() > before) return true else SystemClock.sleep(100) }
        return false
    }

    private fun waitForReaderState(predicate: (ReaderUiState) -> Boolean): Boolean {
        repeat(60) {
            var matches = false
            scenario.onActivity { activity ->
                matches = predicate(ViewModelProvider(activity)[ReaderViewModel::class.java].uiState.value)
            }
            if (matches) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun waitForStudentStrokeCount(attemptNo: Int, count: Int): Boolean {
        val store = PageOperationLogStore.get(context)
        repeat(60) {
            val actual = store.loadPage(bookId, 0).activeStrokes.count { stroke ->
                stroke.authorId == "student" && stroke.attemptNo == attemptNo
            }
            if (actual == count) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun dispatchStroke(source: Int, tool: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
        val down = SystemClock.uptimeMillis()
        val events = listOf(
            motionEvent(MotionEvent.ACTION_DOWN, source, tool, x1, y1, eventTime = down),
            motionEvent(MotionEvent.ACTION_MOVE, source, tool, x2, y2, eventTime = down + 20),
            motionEvent(MotionEvent.ACTION_UP, source, tool, x2, y2, eventTime = down + 40),
        )
        scenario.onActivity { activity -> events.forEach(activity::dispatchTouchEvent) }
        events.forEach(MotionEvent::recycle)
    }

    private fun dispatchTap(source: Int, tool: Int, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        val events = listOf(
            motionEvent(MotionEvent.ACTION_DOWN, source, tool, x, y, eventTime = down),
            motionEvent(MotionEvent.ACTION_UP, source, tool, x, y, eventTime = down + 40),
        )
        scenario.onActivity { activity -> events.forEach(activity::dispatchTouchEvent) }
        events.forEach(MotionEvent::recycle)
    }

    private fun selectToolFromFan(description: String) {
        val open = motionEvent(
            MotionEvent.ACTION_BUTTON_PRESS,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            520f,
            920f,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
        )
        scenario.onActivity { assertTrue(it.dispatchGenericMotionEvent(open)) }
        open.recycle()
        assertTrue(device.wait(Until.hasObject(By.desc(description)), 3_000))
        val bounds = device.findObject(By.desc(description)).visibleBounds
        injectStylusTapOnScreen(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        SystemClock.sleep(250)
    }

    private fun injectStylusTapOnScreen(x: Float, y: Float) {
        val downAt = SystemClock.uptimeMillis()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = motionEvent(
            MotionEvent.ACTION_DOWN,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            x,
            y,
            eventTime = downAt,
        )
        val up = motionEvent(
            MotionEvent.ACTION_UP,
            InputDevice.SOURCE_STYLUS,
            MotionEvent.TOOL_TYPE_STYLUS,
            x,
            y,
            eventTime = downAt + 40,
        )
        assertTrue(automation.injectInputEvent(down, true))
        assertTrue(automation.injectInputEvent(up, true))
        down.recycle()
        up.recycle()
    }

    private fun circumcenter(
        first: Pair<Float, Float>,
        second: Pair<Float, Float>,
        third: Pair<Float, Float>,
    ): Pair<Float, Float> {
        val (ax, ay) = first
        val (bx, by) = second
        val (cx, cy) = third
        val determinant = 2f * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        val aSquared = ax * ax + ay * ay
        val bSquared = bx * bx + by * by
        val cSquared = cx * cx + cy * cy
        return ((aSquared * (by - cy) + bSquared * (cy - ay) + cSquared * (ay - by)) /
            determinant) to
            ((aSquared * (cx - bx) + bSquared * (ax - cx) + cSquared * (bx - ax)) /
                determinant)
    }

    private fun dispatchDownInsideUpOutside(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        val events = listOf(
            motionEvent(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, x, y, eventTime = down),
            motionEvent(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, x - 120f, y + 120f, eventTime = down + 20),
            motionEvent(MotionEvent.ACTION_UP, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, x - 120f, y + 120f, eventTime = down + 40),
        )
        scenario.onActivity { activity -> events.forEach(activity::dispatchTouchEvent) }
        events.forEach(MotionEvent::recycle)
    }

    private fun clickStylusSideButton() {
        listOf(
            MotionEvent.ACTION_BUTTON_PRESS to MotionEvent.BUTTON_STYLUS_PRIMARY,
            MotionEvent.ACTION_BUTTON_RELEASE to 0,
        ).forEach { (action, buttons) ->
            val event = motionEvent(
                action, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 520f, 920f, buttons,
            )
            scenario.onActivity { it.dispatchGenericMotionEvent(event) }
            event.recycle()
        }
    }

    private fun motionEvent(
        action: Int,
        source: Int,
        tool: Int,
        x: Float,
        y: Float,
        buttons: Int = 0,
        eventTime: Long = SystemClock.uptimeMillis(),
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 0.7f; size = 0.05f
        })
        return MotionEvent.obtain(
            eventTime, eventTime, action, 1, properties, coordinates, 0, buttons,
            1f, 1f, 0, 0, source, 0,
        )
    }

    private companion object {
        /** Present only while the radial menu is showing, so it reads as "the fan is open". */
        private val FAN_ONLY = By.desc("형광펜")
    }

    private fun ReaderActivity.findDryInkView(): DryInkView {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val queue = ArrayDeque<android.view.View>().apply { add(root) }
        while (queue.isNotEmpty()) when (val view = queue.removeFirst()) {
            is DryInkView -> return view
            is android.view.ViewGroup -> (0 until view.childCount).forEach { queue.add(view.getChildAt(it)) }
        }
        error("DryInkView missing")
    }

    private fun ReaderActivity.findInkInputView(): InkInputView {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val queue = ArrayDeque<android.view.View>().apply { add(root) }
        while (queue.isNotEmpty()) when (val view = queue.removeFirst()) {
            is InkInputView -> return view
            is android.view.ViewGroup -> (0 until view.childCount).forEach { queue.add(view.getChildAt(it)) }
        }
        error("InkInputView missing")
    }

    private fun createPdf(file: File, pages: Int): File {
        val document = PdfDocument()
        repeat(pages) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(840, 1188, index + 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("Page ${index + 1}", 80f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 32f
            })
            document.finishPage(page)
        }
        FileOutputStream(file).use(document::writeTo)
        document.close()
        return file
    }
}
