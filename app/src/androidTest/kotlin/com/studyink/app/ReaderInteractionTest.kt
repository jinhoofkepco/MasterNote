package com.studyink.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.pdf.view.PdfView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.studyink.core.model.StrokeTool
import com.studyink.document.pdf.ReaderPdfFragment
import com.studyink.reader.DryInkView
import com.studyink.reader.ReaderActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderInteractionTest {
    private lateinit var scenario: ActivityScenario<ReaderActivity>
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun launchReader() {
        scenario = ActivityScenario.launch(ReaderActivity::class.java)
        waitForDocument()
    }

    @After
    fun closeReader() {
        scenario.close()
    }

    @Test
    fun fingerDoesNotWriteButStylusDoes() {
        val before = revision()
        dispatchStroke(InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 320f, 760f, 430f, 790f)
        SystemClock.sleep(500)
        assertEquals("손가락 입력은 주석을 추가하면 안 됩니다", before, revision())

        dispatchStroke(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 360f, 820f, 520f, 870f)
        assertTrue("스타일러스 입력은 저장된 필기를 만들어야 합니다", waitForRevisionAfter(before))
    }

    @Test
    fun stylusSideButtonOpensIconMenuAtPointer() {
        val eventTime = SystemClock.uptimeMillis()
        val event = motionEvent(
            action = MotionEvent.ACTION_BUTTON_PRESS,
            source = InputDevice.SOURCE_STYLUS,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            x = 520f,
            y = 920f,
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
            eventTime = eventTime,
        )

        scenario.onActivity { activity ->
            assertTrue(activity.dispatchGenericMotionEvent(event))
        }
        event.recycle()

        assertTrue(device.wait(Until.hasObject(By.desc("펜")), 3_000))
        assertTrue(device.hasObject(By.desc("형광펜")))
        assertTrue(device.hasObject(By.desc("지우개")))
        assertTrue(device.hasObject(By.desc("다음 페이지")))
        assertTrue(device.hasObject(By.desc("되돌리기")))

        device.findObject(By.desc("다음 페이지")).click()
        repeat(30) {
            var page = 0
            scenario.onActivity { activity -> page = activity.findDryInkView().activePage }
            if (page == 1) {
                assertTrue("페이지 이동 후에도 메뉴가 유지되어야 합니다", device.hasObject(By.desc("펜")))
                dispatchStroke(InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 300f, 720f, 360f, 740f)
                assertTrue(
                    "메뉴 밖을 손가락으로 누르면 메뉴가 닫혀야 합니다",
                    device.wait(Until.gone(By.desc("펜")), 3_000),
                )
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("팝업의 다음 페이지 아이콘이 2페이지로 이동하지 못했습니다")
    }

    @Test
    fun highlighterSelectionSurvivesMenuDismissAndStoresTransparentStroke() {
        val eventTime = SystemClock.uptimeMillis()
        val event = motionEvent(
            action = MotionEvent.ACTION_BUTTON_PRESS,
            source = InputDevice.SOURCE_STYLUS,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            x = 520f,
            y = 920f,
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
            eventTime = eventTime,
        )
        scenario.onActivity { activity -> assertTrue(activity.dispatchGenericMotionEvent(event)) }
        event.recycle()

        assertTrue(device.wait(Until.hasObject(By.desc("형광펜")), 3_000))
        device.findObject(By.desc("형광펜")).click()
        val before = revision()
        dispatchStroke(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 360f, 820f, 520f, 850f)
        assertTrue("형광펜 획이 저장되어야 합니다", waitForRevisionAfter(before))

        scenario.onActivity { activity ->
            val stroke = activity.findDryInkView().snapshot.activeStrokes.last()
            assertEquals(StrokeTool.HIGHLIGHTER, stroke.tool)
            assertEquals(0x66FFE45C, stroke.colorArgb)
        }
    }

    @Test
    fun penSettingsOpenOnlyWhenTheSelectedPenIsTappedAgain() {
        openStylusMenu()
        device.findObject(By.desc("형광펜")).click()
        device.findObject(By.desc("펜")).click()
        assertTrue("첫 번째 펜 탭은 펜 선택만 해야 합니다", device.hasObject(By.desc("지우개")))
        assertTrue(!device.hasObject(By.descContains("선 굵기")))

        device.findObject(By.desc("펜")).click()
        assertTrue(device.wait(Until.hasObject(By.descContains("선 굵기")), 3_000))
        assertTrue(device.hasObject(By.descContains("펜 투명도")))
    }

    @Test
    fun openPdfButtonLaunchesTheSystemDocumentPicker() {
        device.findObject(By.text("PDF 열기")).click()
        assertTrue(
            "PDF 열기는 시스템 문서 선택기를 열어야 합니다",
            waitForSystemDocumentPicker(),
        )
        device.pressBack()
    }

    @Test
    fun annotationsAreIsolatedAndRestoredPerPdf() {
        lateinit var firstPdf: File
        lateinit var secondPdf: File
        var initialId = ""
        scenario.onActivity { activity ->
            initialId = activity.findDryInkView().snapshot.documentId
            firstPdf = createPdf(activity.filesDir, "isolation-a-${System.nanoTime()}.pdf", "Document A")
            secondPdf = createPdf(activity.filesDir, "isolation-b-${System.nanoTime()}.pdf", "Document B")
            openDocument(activity, Uri.fromFile(firstPdf))
        }
        val firstId = waitForStableDocumentId(excluding = initialId)
        val before = revision()
        dispatchStroke(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 340f, 780f, 520f, 820f)
        assertTrue(waitForRevisionAfter(before))

        scenario.onActivity { activity -> openDocument(activity, Uri.fromFile(secondPdf)) }
        val secondId = waitForStableDocumentId(excluding = firstId)
        assertTrue("PDF마다 서로 다른 필기 저장소를 사용해야 합니다", secondId != firstId)
        scenario.onActivity { activity ->
            assertTrue(activity.findDryInkView().snapshot.activeStrokes.isEmpty())
        }

        scenario.onActivity { activity -> openDocument(activity, Uri.fromFile(firstPdf)) }
        waitForStableDocumentId(expected = firstId)
        scenario.onActivity { activity ->
            assertTrue("첫 PDF를 다시 열면 그 PDF의 필기만 복원되어야 합니다", activity.findDryInkView().snapshot.activeStrokes.isNotEmpty())
        }
    }

    @Test
    fun zoomAndVerticalScrollStayOnTheSelectedPageWithoutThePdfEditButton() {
        var initialId = ""
        scenario.onActivity { activity ->
            initialId = activity.findDryInkView().snapshot.documentId
            val pdf = createColorPagesPdf(activity.filesDir, "page-lock-${System.nanoTime()}.pdf")
            openDocument(activity, Uri.fromFile(pdf))
        }
        waitForStableDocumentId(excluding = initialId)

        scenario.onActivity { activity ->
            ReaderActivity::class.java
                .getDeclaredMethod("showPage", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, 1)

            activity.readerPdfFragment().onRequestImmersiveMode(false)
            assertTrue(
                "AndroidX PDF 편집 버튼은 항상 숨겨져야 합니다",
                !activity.readerPdfFragment().isToolboxVisible,
            )
        }

        repeat(3) {
            scenario.onActivity { activity ->
                activity.findPdfView().apply {
                    zoom = maxOf(minZoom * 2.5f, 1.5f).coerceAtMost(maxZoom)
                }
            }
            SystemClock.sleep(400)
            repeat(2) { device.swipe(540, 1_700, 540, 450, 20) }
            assertOnlySelectedPageColorIsVisible()

            repeat(2) { device.swipe(540, 450, 540, 1_700, 20) }
            assertOnlySelectedPageColorIsVisible()

            scenario.onActivity { activity ->
                activity.findPdfView().apply { zoom = minZoom }
            }
            SystemClock.sleep(300)
        }

        scenario.onActivity { activity ->
            assertTrue(
                "확대·축소 후에도 PDF 편집 버튼이 나타나면 안 됩니다",
                !activity.readerPdfFragment().isToolboxVisible,
            )
        }
    }

    private fun openStylusMenu() {
        val eventTime = SystemClock.uptimeMillis()
        val event = motionEvent(
            action = MotionEvent.ACTION_BUTTON_PRESS,
            source = InputDevice.SOURCE_STYLUS,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            x = 520f,
            y = 920f,
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
            eventTime = eventTime,
        )
        scenario.onActivity { activity -> assertTrue(activity.dispatchGenericMotionEvent(event)) }
        event.recycle()
        assertTrue(device.wait(Until.hasObject(By.desc("펜")), 3_000))
    }

    private fun dispatchStroke(
        source: Int,
        toolType: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ) {
        val downTime = SystemClock.uptimeMillis()
        val events = listOf(
            motionEvent(MotionEvent.ACTION_DOWN, source, toolType, startX, startY, eventTime = downTime),
            motionEvent(MotionEvent.ACTION_MOVE, source, toolType, endX, endY, eventTime = downTime + 20),
            motionEvent(MotionEvent.ACTION_UP, source, toolType, endX, endY, eventTime = downTime + 40),
        )
        scenario.onActivity { activity -> events.forEach(activity::dispatchTouchEvent) }
        events.forEach(MotionEvent::recycle)
    }

    private fun motionEvent(
        action: Int,
        source: Int,
        toolType: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
        eventTime: Long = SystemClock.uptimeMillis(),
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.7f
            size = 0.05f
        })
        return MotionEvent.obtain(
            eventTime,
            eventTime,
            action,
            1,
            properties,
            coordinates,
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            source,
            0,
        )
    }

    private fun waitForDocument() {
        repeat(80) {
            var ready = false
            scenario.onActivity { activity ->
                val id = activity.findDryInkView().snapshot.documentId
                ready = id != "sample" && !id.startsWith("loading-")
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("PDF와 주석 문서가 준비되지 않았습니다")
    }

    private fun waitForSystemDocumentPicker(): Boolean {
        repeat(30) {
            if (
                device.hasObject(By.pkg("com.android.documentsui")) ||
                device.hasObject(By.pkg("com.google.android.documentsui"))
            ) {
                return true
            }
            SystemClock.sleep(100)
        }
        return false
    }

    private fun waitForStableDocumentId(excluding: String? = null, expected: String? = null): String {
        repeat(100) {
            var id = ""
            scenario.onActivity { activity -> id = activity.findDryInkView().snapshot.documentId }
            val stable = id.isNotBlank() && id != "sample" && !id.startsWith("loading-")
            if (stable && (excluding == null || id != excluding) && (expected == null || id == expected)) return id
            SystemClock.sleep(100)
        }
        throw AssertionError("PDF별 필기 문서가 준비되지 않았습니다")
    }

    private fun waitForRevisionAfter(revision: Long): Boolean {
        repeat(50) {
            if (revision() > revision) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun assertOnlySelectedPageColorIsVisible() {
        SystemClock.sleep(500)
        repeat(20) {
            val bitmap = device.takeScreenshot() ?: run {
                SystemClock.sleep(100)
                return@repeat
            }
            var contentTop = 0
            var contentBottom = bitmap.height
            scenario.onActivity { activity ->
                val dryInk = activity.findDryInkView()
                val location = IntArray(2)
                dryInk.getLocationOnScreen(location)
                contentTop = location[1].coerceIn(0, bitmap.height)
                contentBottom = (location[1] + dryInk.height).coerceIn(contentTop, bitmap.height)
            }

            var previousPagePixels = 0
            var selectedPagePixels = 0
            var nextPagePixels = 0
            for (y in contentTop until contentBottom step 8) {
                for (x in 0 until bitmap.width step 8) {
                    val pixel = bitmap.getPixel(x, y)
                    when {
                        pixel.isNear(PAGE_ONE_COLOR) -> previousPagePixels++
                        pixel.isNear(PAGE_TWO_COLOR) -> selectedPagePixels++
                        pixel.isNear(PAGE_THREE_COLOR) -> nextPagePixels++
                    }
                }
            }
            if (selectedPagePixels > 100) {
                assertEquals("이전 페이지가 화면에 나타나면 안 됩니다", 0, previousPagePixels)
                assertEquals("다음 페이지가 화면에 나타나면 안 됩니다", 0, nextPagePixels)
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("확대·이동 후 선택한 2페이지가 화면으로 복귀하지 않았습니다")
    }

    private fun revision(): Long {
        var revision = -1L
        scenario.onActivity { activity -> revision = activity.findDryInkView().snapshot.revision }
        return revision
    }

    private fun ReaderActivity.findDryInkView(): DryInkView {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val queue = ArrayDeque<android.view.View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            when (val view = queue.removeFirst()) {
                is DryInkView -> return view
                is android.view.ViewGroup -> (0 until view.childCount).forEach { queue.add(view.getChildAt(it)) }
            }
        }
        throw AssertionError("DryInkView를 찾을 수 없습니다")
    }

    private fun ReaderActivity.findPdfView(): PdfView {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val queue = ArrayDeque<android.view.View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            when (val view = queue.removeFirst()) {
                is PdfView -> return view
                is android.view.ViewGroup -> (0 until view.childCount).forEach { queue.add(view.getChildAt(it)) }
            }
        }
        throw AssertionError("PdfView를 찾을 수 없습니다")
    }

    private fun ReaderActivity.readerPdfFragment(): ReaderPdfFragment =
        supportFragmentManager.fragments.filterIsInstance<ReaderPdfFragment>().single()

    private fun createPdf(directory: File, name: String, text: String): File {
        val file = File(directory, name)
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(840, 1188, 1).create())
        page.canvas.drawText(text, 72f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f })
        document.finishPage(page)
        FileOutputStream(file).use(document::writeTo)
        document.close()
        return file
    }

    private fun createColorPagesPdf(directory: File, name: String): File {
        val file = File(directory, name)
        val document = PdfDocument()
        listOf(PAGE_ONE_COLOR, PAGE_TWO_COLOR, PAGE_THREE_COLOR).forEachIndexed { index, color ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(840, 1188, index + 1).create())
            page.canvas.drawColor(color)
            document.finishPage(page)
        }
        FileOutputStream(file).use(document::writeTo)
        document.close()
        return file
    }

    private fun Int.isNear(target: Int, tolerance: Int = 12): Boolean =
        kotlin.math.abs(Color.red(this) - Color.red(target)) <= tolerance &&
            kotlin.math.abs(Color.green(this) - Color.green(target)) <= tolerance &&
            kotlin.math.abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private fun openDocument(activity: ReaderActivity, uri: Uri) {
        ReaderActivity::class.java
            .getDeclaredMethod("showDocument", Uri::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(activity, uri, true)
    }

    private companion object {
        val PAGE_ONE_COLOR: Int = Color.rgb(198, 36, 72)
        val PAGE_TWO_COLOR: Int = Color.rgb(37, 168, 89)
        val PAGE_THREE_COLOR: Int = Color.rgb(45, 79, 198)
    }
}
