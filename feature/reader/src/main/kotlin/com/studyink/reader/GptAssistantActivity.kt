package com.studyink.reader

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.studyink.assistant.core.AssistantPageKey
import com.studyink.assistant.core.AssistantRepositoryProvider
import com.studyink.assistant.webview.ChatGptManualFallback
import com.studyink.assistant.webview.ChatGptQuery
import com.studyink.assistant.webview.ChatGptResult
import com.studyink.assistant.webview.ChatGptResponseTimeoutException
import com.studyink.assistant.webview.ChatGptWebViewController
import com.studyink.assistant.webview.ChatGptWebViewListener
import com.studyink.assistant.webview.ChatGptWebViewState
import com.studyink.core.model.PageBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Isolated, disposable ChatGPT workspace. Only an explicit save writes a page-sidecar resource. */
class GptAssistantActivity : FragmentActivity(), ChatGptWebViewListener {
    private lateinit var controller: ChatGptWebViewController
    private lateinit var status: TextView
    private lateinit var answerEditor: EditText
    private lateinit var sendButton: Button
    private lateinit var saveButton: Button
    private lateinit var manualButton: Button
    private lateinit var copyButton: Button

    private lateinit var request: GptAssistantRequest
    private var queryRunning = false
    private var latestResult: ChatGptResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = GptAssistantRequest.fromIntent(intent) ?: run {
            finish()
            return
        }
        controller = ChatGptWebViewController(this, this)
        setContentView(buildContent())
        controller.open()
        restoreUiState(savedInstanceState)
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 23, 28))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(Color.rgb(246, 243, 234))
        }
        status = TextView(this).apply {
            text = "${request.pageNumber + 1}쪽 · ${request.promptTitle} · 처음이면 ChatGPT에 로그인하세요"
            setTextColor(Color.rgb(48, 48, 45))
            textSize = 13f
            maxLines = 2
        }
        header.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "닫기"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(
            controller.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        answerEditor = EditText(this).apply {
            hint = "받은 답변을 확인하거나 직접 붙여넣으세요"
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
            visibility = View.GONE
            setBackgroundColor(Color.rgb(255, 253, 247))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            filters = arrayOf(InputFilter.LengthFilter(MAX_ANSWER_CHARS))
        }
        root.addView(
            answerEditor,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(7))
            setBackgroundColor(Color.rgb(246, 243, 234))
        }
        sendButton = actionButton("질문 보내기") { beginQuery() }
        copyButton = actionButton("질문 복사") { copyPrompt() }
        manualButton = actionButton("답변 붙여넣기") { showManualAnswerDialog() }
        saveButton = actionButton("페이지에 저장") { saveAnswer() }.apply { isEnabled = false }
        listOf(sendButton, copyButton, manualButton, saveButton).forEach { button ->
            actions.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        setOnClickListener { action() }
    }

    private fun beginQuery() {
        if (queryRunning) return
        queryRunning = true
        sendButton.isEnabled = false
        status.text = "선택 영역과 질문을 보내는 중…"
        lifecycleScope.launch {
            try {
                val imageBytes = withContext(Dispatchers.IO) { request.readImage(cacheDir) }
                val result = controller.query(ChatGptQuery(request.promptBody, imageBytes))
                showAnswer(result)
            } catch (error: Throwable) {
                if (error is ChatGptResponseTimeoutException && error.partialResponse.isNotBlank()) {
                    showPartialAnswer(error.partialResponse)
                } else {
                    status.text = error.message ?: "자동 응답 확인에 실패했습니다. 수동 붙여넣기를 사용할 수 있어요."
                }
            } finally {
                queryRunning = false
                sendButton.isEnabled = true
            }
        }
    }

    private fun showAnswer(result: ChatGptResult) {
        latestResult = result
        answerEditor.visibility = View.VISIBLE
        answerEditor.setText(result.text)
        answerEditor.setSelection(answerEditor.text.length)
        saveButton.isEnabled = result.text.isNotBlank()
        status.text = "답변을 받았습니다. 확인 후 페이지에 저장하세요."
    }

    private fun showPartialAnswer(text: String) {
        answerEditor.visibility = View.VISIBLE
        answerEditor.setText(text.take(MAX_ANSWER_CHARS))
        answerEditor.setSelection(answerEditor.text.length)
        saveButton.isEnabled = answerEditor.text.isNotBlank()
        status.text = "응답 완료는 확인하지 못했습니다. 보이는 답변을 검토한 뒤 저장할 수 있어요."
    }

    private fun copyPrompt() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("MasterNote GPT 질문", request.promptBody))
        Toast.makeText(this, "질문을 복사했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun showManualAnswerDialog() {
        val editor = EditText(this).apply {
            hint = "ChatGPT 답변 붙여넣기"
            minLines = 6
            maxLines = 16
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(MAX_ANSWER_CHARS))
        }
        AlertDialog.Builder(this)
            .setTitle("답변 직접 붙여넣기")
            .setView(editor)
            .setNegativeButton("취소", null)
            .setPositiveButton("사용") { _, _ ->
                val text = editor.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@setPositiveButton
                if (!controller.provideManualResponse(text)) {
                    showAnswer(
                        ChatGptResult(
                            text = text,
                            assistantMessageCount = 0,
                            completion = com.studyink.assistant.webview.ChatGptCompletion.MANUAL,
                        ),
                    )
                }
            }
            .show()
    }

    private fun saveAnswer() {
        val text = answerEditor.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        saveButton.isEnabled = false
        status.text = "페이지에 저장 중…"
        lifecycleScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    AssistantRepositoryProvider.get(this@GptAssistantActivity).createTeacherResource(
                        page = AssistantPageKey(request.bookId, request.pageNumber),
                        title = request.promptTitle,
                        selectionBounds = request.selectionBounds,
                        promptSlotNumber = request.promptSlotNumber,
                        answerText = text,
                        // Text is authoritative. Keeping DOM HTML out makes later display/removal
                        // independent of WebView parsing and shrinks the optional sidecar.
                        answerHtml = null,
                        providerName = "ChatGPT WebView",
                        promptTitleSnapshot = request.promptTitle,
                        promptBodySnapshot = request.promptBody,
                    )
                }
            }
            saved.onSuccess { resource ->
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_SAVED_RESOURCE_ID, resource.resourceId)
                        .putExtra(EXTRA_SAVED_BOOK_ID, request.bookId)
                        .putExtra(EXTRA_SAVED_PAGE_NUMBER, request.pageNumber),
                )
                Toast.makeText(this@GptAssistantActivity, "이 페이지에 답변을 저장했습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure { error ->
                saveButton.isEnabled = true
                status.text = error.message ?: "답변을 저장하지 못했습니다."
            }
        }
    }

    override fun onStateChanged(state: ChatGptWebViewState) {
        if (queryRunning) return
        if (state == ChatGptWebViewState.READY) status.text = "ChatGPT 준비됨 · 질문 보내기를 누르세요."
    }

    override fun onManualFallback(fallback: ChatGptManualFallback) {
        status.text = when (fallback) {
            is ChatGptManualFallback.Send -> fallback.reason
            is ChatGptManualFallback.Response -> {
                if (fallback.partialText.isNotBlank()) showPartialAnswer(fallback.partialText)
                fallback.reason
            }
        }
    }

    override fun onNavigationBlocked(url: String) {
        status.text = "허용되지 않은 외부 페이지 이동을 막았습니다."
    }

    override fun onPageError(description: String) {
        status.text = description
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_STATUS_TEXT, status.text?.toString().orEmpty())
        outState.putBoolean(STATE_QUERY_WAS_RUNNING, queryRunning)
        if (answerEditor.visibility == View.VISIBLE) {
            outState.putString(STATE_ANSWER_TEXT, answerEditor.text?.toString().orEmpty())
        }
    }

    private fun restoreUiState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        val restoredAnswer = state.getString(STATE_ANSWER_TEXT)
        if (restoredAnswer != null) {
            answerEditor.visibility = View.VISIBLE
            answerEditor.setText(restoredAnswer.take(MAX_ANSWER_CHARS))
            answerEditor.setSelection(answerEditor.text.length)
            saveButton.isEnabled = answerEditor.text.isNotBlank()
        }
        state.getString(STATE_STATUS_TEXT)?.takeIf(String::isNotBlank)?.let { savedStatus ->
            status.text = if (state.getBoolean(STATE_QUERY_WAS_RUNNING, false)) {
                "화면이 다시 만들어졌습니다. 질문 보내기를 다시 눌러 주세요."
            } else {
                savedStatus
            }
        }
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.destroy()
        // A non-finishing recreation still needs the immutable capture carried by the Intent.
        // Normal close/save deletes it; process-death leftovers are age-pruned by ReaderActivity.
        if (isFinishing && ::request.isInitialized) request.deleteImage(cacheDir)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SAVED_RESOURCE_ID = "gptSavedResourceId"
        const val EXTRA_SAVED_BOOK_ID = "gptSavedBookId"
        const val EXTRA_SAVED_PAGE_NUMBER = "gptSavedPageNumber"
        // Bounded well below the repository's UTF-8 byte limit even for four-byte characters.
        private const val MAX_ANSWER_CHARS = 120_000
        private const val STATE_STATUS_TEXT = "gptStatusText"
        private const val STATE_ANSWER_TEXT = "gptAnswerText"
        private const val STATE_QUERY_WAS_RUNNING = "gptQueryWasRunning"

        fun intent(context: Context, request: GptAssistantRequest): Intent =
            Intent(context, GptAssistantActivity::class.java).apply { request.putInto(this) }
    }
}

data class GptAssistantRequest(
    val bookId: String,
    val pageNumber: Int,
    val promptSlotNumber: Int,
    val promptTitle: String,
    val promptBody: String,
    val selectionBounds: PageBounds,
    val imagePath: String,
) {
    fun putInto(intent: Intent) {
        intent.putExtra(EXTRA_BOOK_ID, bookId)
            .putExtra(EXTRA_PAGE_NUMBER, pageNumber)
            .putExtra(EXTRA_PROMPT_SLOT, promptSlotNumber)
            .putExtra(EXTRA_PROMPT_TITLE, promptTitle)
            .putExtra(EXTRA_PROMPT_BODY, promptBody)
            .putExtra(EXTRA_LEFT, selectionBounds.left)
            .putExtra(EXTRA_TOP, selectionBounds.top)
            .putExtra(EXTRA_RIGHT, selectionBounds.right)
            .putExtra(EXTRA_BOTTOM, selectionBounds.bottom)
            .putExtra(EXTRA_IMAGE_PATH, imagePath)
    }

    fun readImage(cacheRoot: File): ByteArray {
        val file = checkedImageFile(cacheRoot)
        require(file.length() in 1..MAX_IMAGE_BYTES.toLong()) { "선택 영역 이미지가 너무 큽니다." }
        return file.readBytes().also { require(it.size <= MAX_IMAGE_BYTES) }
    }

    fun deleteImage(cacheRoot: File) {
        runCatching { checkedImageFile(cacheRoot).delete() }
    }

    private fun checkedImageFile(cacheRoot: File): File {
        val root = cacheRoot.canonicalFile
        val file = File(imagePath).canonicalFile
        require(file.path.startsWith(root.path + File.separator) && file.isFile) {
            "선택 영역 이미지가 없습니다."
        }
        return file
    }

    companion object {
        private const val EXTRA_BOOK_ID = "gptBookId"
        private const val EXTRA_PAGE_NUMBER = "gptPageNumber"
        private const val EXTRA_PROMPT_SLOT = "gptPromptSlot"
        private const val EXTRA_PROMPT_TITLE = "gptPromptTitle"
        private const val EXTRA_PROMPT_BODY = "gptPromptBody"
        private const val EXTRA_LEFT = "gptSelectionLeft"
        private const val EXTRA_TOP = "gptSelectionTop"
        private const val EXTRA_RIGHT = "gptSelectionRight"
        private const val EXTRA_BOTTOM = "gptSelectionBottom"
        private const val EXTRA_IMAGE_PATH = "gptImagePath"
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        fun fromIntent(intent: Intent): GptAssistantRequest? = runCatching {
            val bookId = requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)).also {
                require(it.isNotBlank())
            }
            val pageNumber = intent.getIntExtra(EXTRA_PAGE_NUMBER, -1).also { require(it >= 0) }
            val slot = intent.getIntExtra(EXTRA_PROMPT_SLOT, -1).also { require(it in 1..4) }
            val title = requireNotNull(intent.getStringExtra(EXTRA_PROMPT_TITLE)).also {
                require(it.isNotBlank())
            }
            val body = requireNotNull(intent.getStringExtra(EXTRA_PROMPT_BODY)).also {
                require(it.isNotBlank())
            }
            val bounds = PageBounds(
                intent.getFloatExtra(EXTRA_LEFT, Float.NaN),
                intent.getFloatExtra(EXTRA_TOP, Float.NaN),
                intent.getFloatExtra(EXTRA_RIGHT, Float.NaN),
                intent.getFloatExtra(EXTRA_BOTTOM, Float.NaN),
            ).also {
                require(it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite())
                require(it.left < it.right && it.top < it.bottom)
            }
            val path = requireNotNull(intent.getStringExtra(EXTRA_IMAGE_PATH))
            GptAssistantRequest(bookId, pageNumber, slot, title, body, bounds, path)
        }.getOrNull()
    }
}
