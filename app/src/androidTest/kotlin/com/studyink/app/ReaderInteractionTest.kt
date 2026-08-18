package com.studyink.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
import com.studyink.library.data.LibraryRepository
import com.studyink.reader.DryInkView
import com.studyink.reader.InkInputView
import com.studyink.reader.ReaderActivity
import com.studyink.reader.ReaderTool
import com.studyink.sync.lan.PairingPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
