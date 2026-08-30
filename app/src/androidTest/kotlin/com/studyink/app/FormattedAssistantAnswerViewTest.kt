package com.studyink.app

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studyink.assistant.core.TeacherGptAnswerFormat
import com.studyink.reader.FormattedAssistantAnswerView
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FormattedAssistantAnswerViewTest {
    @Test
    fun androidRegexConvertsFracWithoutPatternSyntaxCrash() {
        val container = Class.forName(
            "com.studyink.reader.TeacherPageResourcesDialogControllerKt",
        )
        val method = container.getDeclaredMethod(
            "assistantStudentText",
            String::class.java,
            TeacherGptAnswerFormat::class.java,
        ).apply { isAccessible = true }

        val text = method.invoke(
            null,
            "분수는 ${'$'}\\frac{1}{2}${'$'}입니다.",
            TeacherGptAnswerFormat.MARKDOWN_TEX,
        ) as String

        assertEquals("분수는 (1)/(2)입니다.", text)
    }

    @Test
    fun localDocumentRendersMarkdownAndMathWithoutAWebErrorPage() {
        ActivityScenario.launch(RemoteReviewActivity::class.java).use { scenario ->
            lateinit var answerView: FormattedAssistantAnswerView
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                answerView = FormattedAssistantAnswerView(activity)
                activity.setContentView(answerView)
                answerView.render(
                    "# 수식 확인\n\n피타고라스 정리는 ${'$'}a^2+b^2=c^2${'$'} 입니다.",
                    TeacherGptAnswerFormat.MARKDOWN_TEX,
                )
                webView = checkNotNull(answerView.findWebView())
            }

            val deadline = SystemClock.uptimeMillis() + RENDER_TIMEOUT_MS
            var renderedText = ""
            while (SystemClock.uptimeMillis() < deadline) {
                renderedText = evaluateString(
                    webView,
                    "(document.body ? document.body.innerText : '') + '\\nKATEX=' + " +
                        "document.querySelectorAll('.katex').length",
                )
                if (renderedText.contains("수식 확인") && renderedText.contains("KATEX=1")) break
                SystemClock.sleep(100)
            }

            assertTrue("local answer document did not render: $renderedText", renderedText.contains("수식 확인"))
            assertTrue("KaTeX did not render: $renderedText", renderedText.contains("KATEX=1"))
            scenario.onActivity { answerView.destroyRenderer() }
        }
    }

    @Test
    fun localEditorLoadsCheckboxBlocksAndReportsPointerSelection() {
        ActivityScenario.launch(RemoteReviewActivity::class.java).use { scenario ->
            lateinit var answerView: FormattedAssistantAnswerView
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                answerView = FormattedAssistantAnswerView(activity)
                activity.setContentView(answerView)
                answerView.renderEditor("첫 줄\n\n둘째 줄\n\n셋째 줄")
                webView = checkNotNull(answerView.findWebView())
            }

            val deadline = SystemClock.uptimeMillis() + RENDER_TIMEOUT_MS
            var state = ""
            while (SystemClock.uptimeMillis() < deadline) {
                state = evaluateString(
                    webView,
                    "(function () {" +
                        "var blocks=document.querySelectorAll('.edit-block');" +
                        "if(blocks.length!==3||!window.MasterNoteAnswerEditor)return 'waiting';" +
                        "var button=blocks[0].querySelector('.block-check');" +
                        "var box=button.getBoundingClientRect();" +
                        "var last=blocks[2].querySelector('.block-check').getBoundingClientRect();" +
                        "button.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:5," +
                        "pointerType:'touch',clientX:box.left+4,clientY:box.top+4}));" +
                        "document.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,pointerId:5," +
                        "pointerType:'touch',clientX:last.left+4,clientY:last.top+4}));" +
                        "document.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:5," +
                        "pointerType:'touch',clientX:last.left+4,clientY:last.top+4}));" +
                        "if(window.MasterNoteAnswerEditor.selectedOrdinals().length)return 'touch-swept';" +
                        "button.click();" +
                        "if(JSON.stringify(window.MasterNoteAnswerEditor.selectedOrdinals())!=='[0]')return 'click-failed';" +
                        "button.click();" +
                        "button.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:7," +
                        "pointerType:'pen',clientX:box.left+4,clientY:box.top+4}));" +
                        "document.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,pointerId:7," +
                        "pointerType:'pen',clientX:last.left+4,clientY:last.top+4}));" +
                        "document.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:7," +
                        "pointerType:'pen',clientX:last.left+4,clientY:last.top+4}));" +
                        "return JSON.stringify(window.MasterNoteAnswerEditor.selectedOrdinals());" +
                        "})()",
                )
                if (state == "[0,1,2]") break
                SystemClock.sleep(100)
            }

            assertEquals("[0,1,2]", state)
            scenario.onActivity { answerView.destroyRenderer() }
        }
    }

    private fun evaluateString(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var encodedResult = "null"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) { result ->
                encodedResult = result
                latch.countDown()
            }
        }
        check(latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "WebView JavaScript timed out" }
        return JSONTokener(encodedResult).nextValue() as? String ?: ""
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index -> getChildAt(index).findWebView()?.let { return it } }
        return null
    }

    private companion object {
        const val RENDER_TIMEOUT_MS = 10_000L
        const val JS_TIMEOUT_SECONDS = 3L
    }
}
