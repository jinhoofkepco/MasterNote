package com.studyink.reader

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.studyink.assistant.core.AssistantPageKey
import com.studyink.assistant.core.AssistantRepositoryProvider
import com.studyink.assistant.core.TeacherGptAnswerFormat
import com.studyink.assistant.core.TeacherGptAnswerMask
import com.studyink.assistant.webview.ChatGptCompletion
import com.studyink.assistant.webview.ChatGptManualFallback
import com.studyink.assistant.webview.ChatGptQuery
import com.studyink.assistant.webview.ChatGptResult
import com.studyink.assistant.webview.ChatGptResponseTimeoutException
import com.studyink.assistant.webview.ChatGptTextFormat
import com.studyink.assistant.webview.ChatGptWebViewController
import com.studyink.assistant.webview.ChatGptWebViewListener
import com.studyink.assistant.webview.ChatGptWebViewState
import com.studyink.assistant.webview.inferChatGptTextFormat
import com.studyink.core.model.PageBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Isolated ChatGPT workspace. Only an explicit save writes a page-sidecar resource. */
class GptAssistantActivity : FragmentActivity(), ChatGptWebViewListener {
    private lateinit var request: GptAssistantRequest
    private lateinit var controller: ChatGptWebViewController
    private lateinit var contentHost: FrameLayout
    private lateinit var statusBanner: TextView
    private lateinit var titleView: TextView
    private lateinit var modeButton: TextView
    private lateinit var overflowButton: TextView
    private lateinit var queryFooter: View
    private lateinit var readFooter: View
    private lateinit var editFooter: View
    private lateinit var sendButton: Button
    private lateinit var saveReadButton: Button
    private lateinit var saveEditButton: Button
    private lateinit var deleteSelectedButton: TextView
    private lateinit var undoDeleteButton: TextView

    private var formattedAnswerView: FormattedAssistantAnswerView? = null
    private var mode = WorkspaceMode.GPT
    private var queryRunning = false
    private var saveInProgress = false
    private var hostResumed = false
    private var responseAvailable = false
    private var hasUnsavedAnswer = false
    private var answerFormat = TeacherGptAnswerFormat.PLAIN_TEXT
    private var originalAnswerText = ""
    private val hiddenBlockOrdinals = linkedSetOf<Int>()
    private var lastHiddenBlockBatch: Set<Int>? = null
    private var editorSelectionReadInProgress = false
    private val hideStatusRunnable = Runnable { statusBanner.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = GptAssistantRequest.fromIntent(intent) ?: run {
            finish()
            return
        }
        controller = ChatGptWebViewController(this, this)
        setContentView(buildContent())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestClose()
        })
        controller.open()
        restoreUiState(savedInstanceState)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(252, 251, 247))
        }
        root.addView(buildThinHeader(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        contentHost = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(255, 253, 248))
        }
        contentHost.addView(
            controller.view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        statusBanner = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedBackground(Color.argb(232, 50, 61, 57), 8)
            elevation = dp(5).toFloat()
            visibility = View.GONE
            maxLines = 2
        }
        contentHost.addView(
            statusBanner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(8)
            },
        )
        root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        queryFooter = buildSingleActionFooter().also { footer ->
            sendButton = primaryButton("질문 보내기") { beginQuery() }
            footer.addView(
                sendButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                },
            )
        }
        readFooter = buildSingleActionFooter().also { footer ->
            saveReadButton = primaryButton("페이지에 저장") { saveAnswer() }
            footer.addView(
                saveReadButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                },
            )
        }
        editFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = thinBarBackground(Color.rgb(247, 246, 240))
        }.also { footer ->
            deleteSelectedButton = footerAction("선택 삭제") { deleteSelectedBlocks() }
            undoDeleteButton = footerAction("되돌리기") {
                withNoStagedEditorSelection(::undoLastBlockDeletion)
            }.apply {
                visibility = View.GONE
            }
            val restoreAllButton = footerAction("전체 복원") {
                withNoStagedEditorSelection(::restoreAllBlocks)
            }
            saveEditButton = primaryButton("페이지에 저장") { saveAnswer() }
            footer.addView(deleteSelectedButton, LinearLayout.LayoutParams(dp(82), dp(48)))
            footer.addView(undoDeleteButton, LinearLayout.LayoutParams(dp(76), dp(48)))
            footer.addView(restoreAllButton, LinearLayout.LayoutParams(dp(76), dp(48)))
            footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            footer.addView(saveEditButton, LinearLayout.LayoutParams(dp(116), dp(48)))
        }
        listOf(queryFooter, readFooter, editFooter).forEach { footer ->
            root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }

        return FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(this)
        }.also { showMode(WorkspaceMode.GPT, renderAnswer = false) }
    }

    private fun buildThinHeader(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        addView(
            View(this@GptAssistantActivity).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(248, 247, 242))
                    setStroke(dp(1), Color.rgb(219, 217, 208))
                }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30), Gravity.CENTER),
        )
        val row = LinearLayout(this@GptAssistantActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(headerAction("×", "GPT 닫기") { requestClose() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleView = TextView(this@GptAssistantActivity).apply {
            text = "${superscript(request.promptSlotNumber)}${compactPromptLabel(request.promptSlotNumber, request.promptTitle)} · ${request.pageNumber + 1}쪽"
            textSize = 12f
            setTextColor(Color.rgb(47, 52, 49))
            typeface = Typeface.create(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
        }
        row.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        modeButton = headerAction("", "화면 전환") { togglePrimaryMode() }.apply { visibility = View.GONE }
        row.addView(modeButton, LinearLayout.LayoutParams(dp(52), dp(48)))
        overflowButton = headerAction("⋮", "더 보기") { anchor -> showOverflow(anchor) }
        row.addView(overflowButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildSingleActionFooter(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = thinBarBackground(Color.rgb(247, 246, 240))
    }

    private fun headerAction(text: String, description: String, action: (View) -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = if (text.length <= 1) 21f else 12f
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = description
            setTextColor(actionTextColors(Color.rgb(55, 63, 59)))
            background = thinActionBackground(
                normalColor = Color.TRANSPARENT,
                hoveredColor = Color.argb(26, 55, 91, 78),
                pressedColor = Color.argb(52, 55, 91, 78),
                disabledColor = Color.TRANSPARENT,
            )
            setOnClickListener(action)
        }

    private fun footerAction(text: String, action: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(actionTextColors(Color.rgb(62, 69, 65)))
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = thinActionBackground(
            normalColor = Color.TRANSPARENT,
            hoveredColor = Color.argb(26, 55, 91, 78),
            pressedColor = Color.argb(52, 55, 91, 78),
            disabledColor = Color.TRANSPARENT,
            selectedColor = Color.argb(38, 55, 91, 78),
        )
        setOnClickListener { action() }
    }

    private fun primaryButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(),
                ),
                intArrayOf(Color.argb(148, 255, 255, 255), Color.WHITE),
            ),
        )
        background = thinActionBackground(
            normalColor = Color.rgb(55, 91, 78),
            hoveredColor = Color.rgb(66, 108, 92),
            pressedColor = Color.rgb(42, 73, 62),
            disabledColor = Color.rgb(151, 158, 153),
        )
        backgroundTintList = null
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun beginQuery() {
        if (queryRunning) return
        queryRunning = true
        sendButton.isEnabled = false
        showStatus("선택 영역과 질문을 보내는 중…", persistent = true)
        lifecycleScope.launch {
            try {
                val imageBytes = withContext(Dispatchers.IO) { request.readImage(cacheDir) }
                showAnswer(controller.query(ChatGptQuery(request.promptBody, imageBytes)))
            } catch (error: Throwable) {
                if (error is ChatGptResponseTimeoutException && error.partialResponse.isNotBlank()) {
                    showPartialAnswer(error.partialResponse)
                } else {
                    showStatus(
                        error.message ?: "자동 응답 확인에 실패했습니다. 답변 붙여넣기를 사용할 수 있어요.",
                        persistent = true,
                    )
                }
            } finally {
                queryRunning = false
                sendButton.isEnabled = true
            }
        }
    }

    private fun showAnswer(result: ChatGptResult) {
        val truncated = applyAnswer(
            result.text,
            when (result.textFormat) {
                ChatGptTextFormat.PLAIN_TEXT -> TeacherGptAnswerFormat.PLAIN_TEXT
                ChatGptTextFormat.MARKDOWN_TEX -> TeacherGptAnswerFormat.MARKDOWN_TEX
            },
        )
        showStatus(
            if (truncated) "답변이 너무 길어 안전한 지점까지만 가져왔습니다."
            else "답변을 받았습니다. 줄 앞 칸을 누르거나 펜으로 훑어 삭제할 부분을 고르세요.",
            persistent = truncated,
        )
    }

    private fun showPartialAnswer(text: String) {
        applyAnswer(
            text,
            when (inferChatGptTextFormat(text)) {
                ChatGptTextFormat.PLAIN_TEXT -> TeacherGptAnswerFormat.PLAIN_TEXT
                ChatGptTextFormat.MARKDOWN_TEX -> TeacherGptAnswerFormat.MARKDOWN_TEX
            },
        )
        showStatus("완료 신호는 못 받았습니다. 보이는 답변을 검토한 뒤 저장할 수 있어요.", persistent = true)
    }

    private fun applyAnswer(text: String, format: TeacherGptAnswerFormat): Boolean {
        val bounded = boundedAssistantAnswer(text, MAX_ANSWER_CHARS)
        answerFormat = format
        originalAnswerText = bounded.text.trim()
        hiddenBlockOrdinals.clear()
        lastHiddenBlockBatch = null
        undoDeleteButton.visibility = View.GONE
        responseAvailable = originalAnswerText.isNotBlank()
        hasUnsavedAnswer = responseAvailable
        updateSaveButtons()
        showMode(WorkspaceMode.EDIT)
        return bounded.truncated
    }

    private fun togglePrimaryMode() {
        when (mode) {
            WorkspaceMode.GPT -> if (responseAvailable) showMode(WorkspaceMode.EDIT)
            WorkspaceMode.READ -> showMode(WorkspaceMode.EDIT)
            WorkspaceMode.EDIT -> withNoStagedEditorSelection { showMode(WorkspaceMode.READ) }
        }
    }

    private fun showMode(next: WorkspaceMode, renderAnswer: Boolean = true) {
        mode = next
        controller.view.visibility = View.GONE
        formattedAnswerView?.onHostPause()
        formattedAnswerView?.visibility = View.GONE
        queryFooter.visibility = View.GONE
        readFooter.visibility = View.GONE
        editFooter.visibility = View.GONE
        when (next) {
            WorkspaceMode.GPT -> {
                controller.open()
                queryFooter.visibility = if (responseAvailable) View.GONE else View.VISIBLE
                modeButton.text = "답변"
                modeButton.visibility = if (responseAvailable) View.VISIBLE else View.GONE
            }
            WorkspaceMode.READ -> {
                controller.hide()
                val renderer = ensureFormattedAnswerView()
                if (renderAnswer) {
                    renderer.render(visibleAnswerText(), answerFormat)
                }
                renderer.visibility = View.VISIBLE
                if (hostResumed) renderer.onHostResume() else renderer.onHostPause()
                readFooter.visibility = View.VISIBLE
                modeButton.text = "편집"
                modeButton.visibility = View.VISIBLE
            }
            WorkspaceMode.EDIT -> {
                controller.hide()
                val renderer = ensureFormattedAnswerView()
                if (renderAnswer) {
                    renderer.renderEditor(originalAnswerText, answerFormat, hiddenBlockOrdinals)
                }
                renderer.visibility = View.VISIBLE
                if (hostResumed) renderer.onHostResume() else renderer.onHostPause()
                editFooter.visibility = View.VISIBLE
                modeButton.text = "보기"
                modeButton.visibility = View.VISIBLE
            }
        }
        updateSaveButtons()
    }

    private fun ensureFormattedAnswerView(): FormattedAssistantAnswerView {
        formattedAnswerView?.let { return it }
        return FormattedAssistantAnswerView(this).also { renderer ->
            if (!hostResumed) renderer.onHostPause()
            formattedAnswerView = renderer
            contentHost.addView(
                renderer,
                0,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
    }

    private fun showOverflow(anchor: View) {
        if (saveInProgress) {
            showStatus("페이지에 저장을 마치는 중입니다.", persistent = true)
            return
        }
        PopupMenu(this, anchor).apply {
            var itemId = 0
            if (responseAvailable && mode != WorkspaceMode.GPT) {
                menu.add(0, MENU_GPT, itemId++, "GPT 원본")
            }
            menu.add(0, MENU_COPY, itemId++, "질문 복사")
            menu.add(0, MENU_PASTE, itemId, "답변 붙여넣기")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_GPT -> withNoStagedEditorSelection { showMode(WorkspaceMode.GPT) }
                    MENU_COPY -> copyPrompt()
                    MENU_PASTE -> showManualAnswerDialog()
                }
                true
            }
        }.show()
    }

    private fun deleteSelectedBlocks() {
        if (mode != WorkspaceMode.EDIT || saveInProgress || editorSelectionReadInProgress) return
        val renderer = formattedAnswerView ?: return
        editorSelectionReadInProgress = true
        renderer.selectedEditorBlockOrdinals { selected ->
            editorSelectionReadInProgress = false
            if (mode != WorkspaceMode.EDIT || saveInProgress) return@selectedEditorBlockOrdinals
            if (selected == null) {
                showStatus("편집 화면을 준비하는 중입니다. 잠시 후 다시 눌러주세요.")
                return@selectedEditorBlockOrdinals
            }
            val existing = AssistantAnswerBlocks.parse(originalAnswerText).mapTo(mutableSetOf()) { it.ordinal }
            val newlyHidden = selected.filterTo(linkedSetOf()) {
                it in existing && it !in hiddenBlockOrdinals
            }
            if (newlyHidden.isEmpty()) {
                showStatus("줄 앞 체크칸에서 삭제할 부분을 먼저 골라주세요.")
                return@selectedEditorBlockOrdinals
            }
            if (hiddenBlockOrdinals.size + newlyHidden.size > TeacherGptAnswerMask.MAX_HIDDEN_BLOCKS) {
                showStatus("한 답변에서 숨길 수 있는 줄 수를 넘었습니다.", persistent = true)
                return@selectedEditorBlockOrdinals
            }
            if (existing.size - hiddenBlockOrdinals.size - newlyHidden.size <= 0) {
                showStatus("답변은 한 줄 이상 남겨야 저장할 수 있습니다.", persistent = true)
                return@selectedEditorBlockOrdinals
            }
            hiddenBlockOrdinals += newlyHidden
            lastHiddenBlockBatch = newlyHidden
            undoDeleteButton.visibility = View.VISIBLE
            hasUnsavedAnswer = true
            showMode(WorkspaceMode.EDIT)
            showStatus("선택한 ${newlyHidden.size}개 줄을 숨겼습니다.")
        }
    }

    private fun undoLastBlockDeletion() {
        val restored = lastHiddenBlockBatch ?: return
        hiddenBlockOrdinals.removeAll(restored)
        lastHiddenBlockBatch = null
        undoDeleteButton.visibility = View.GONE
        hasUnsavedAnswer = true
        showMode(WorkspaceMode.EDIT)
        showStatus("마지막 삭제를 되돌렸습니다.")
    }

    private fun restoreAllBlocks() {
        if (hiddenBlockOrdinals.isEmpty()) {
            showStatus("숨긴 줄이 없습니다.")
            return
        }
        hiddenBlockOrdinals.clear()
        lastHiddenBlockBatch = null
        undoDeleteButton.visibility = View.GONE
        hasUnsavedAnswer = true
        showMode(WorkspaceMode.EDIT)
        showStatus("원문 전체를 복원했습니다.")
    }

    private fun visibleAnswerText(): String =
        AssistantAnswerBlocks.visibleSource(originalAnswerText, hiddenBlockOrdinals)

    private fun withNoStagedEditorSelection(action: () -> Unit) {
        if (mode != WorkspaceMode.EDIT) {
            action()
            return
        }
        if (editorSelectionReadInProgress) return
        val renderer = formattedAnswerView ?: return action()
        editorSelectionReadInProgress = true
        renderer.selectedEditorBlockOrdinals { selected ->
            editorSelectionReadInProgress = false
            if (mode != WorkspaceMode.EDIT) return@selectedEditorBlockOrdinals
            if (selected == null) {
                showStatus("편집 화면을 준비하는 중입니다. 잠시 후 다시 눌러주세요.")
                return@selectedEditorBlockOrdinals
            }
            if (selected.isNotEmpty()) {
                showStatus("체크한 줄은 ‘선택 삭제’를 먼저 눌러 확정해 주세요.", persistent = true)
            } else {
                action()
            }
        }
    }

    private fun copyPrompt() {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("MasterNote GPT 질문", request.promptBody))
        Toast.makeText(this, "질문을 복사했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun showManualAnswerDialog() {
        val editor = EditText(this).apply {
            hint = "ChatGPT 답변 붙여넣기"
            minLines = 6
            maxLines = 16
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(MAX_MANUAL_INPUT_CHARS))
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
                            html = null,
                            assistantMessageCount = 0,
                            completion = ChatGptCompletion.MANUAL,
                            textFormat = inferChatGptTextFormat(text),
                        )
                    )
                }
            }
            .show()
    }

    private fun saveAnswer() {
        if (saveInProgress || editorSelectionReadInProgress) return
        if (mode == WorkspaceMode.EDIT) {
            withNoStagedEditorSelection(::saveCommittedAnswer)
        } else {
            saveCommittedAnswer()
        }
    }

    private fun saveCommittedAnswer() {
        if (saveInProgress) return
        val original = originalAnswerText.trim()
        if (original.isEmpty() || visibleAnswerText().isBlank()) return
        saveInProgress = true
        setSaveEnabled(false)
        showStatus("페이지에 저장 중…", persistent = true)
        lifecycleScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    AssistantRepositoryProvider.get(this@GptAssistantActivity).createTeacherResource(
                        page = AssistantPageKey(request.bookId, request.pageNumber),
                        title = request.promptTitle,
                        selectionBounds = request.selectionBounds,
                        promptSlotNumber = request.promptSlotNumber,
                        answerText = original,
                        answerHtml = null,
                        providerName = "ChatGPT WebView",
                        promptTitleSnapshot = request.promptTitle,
                        promptBodySnapshot = request.promptBody,
                        answerFormat = answerFormat,
                        answerMask = hiddenBlockOrdinals.takeIf { it.isNotEmpty() }?.let { hidden ->
                            TeacherGptAnswerMask.forAnswer(original, hidden)
                        },
                    )
                }
            }
            saved.onSuccess { resource ->
                hasUnsavedAnswer = false
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
                saveInProgress = false
                updateSaveButtons()
                showStatus(error.message ?: "답변을 저장하지 못했습니다.", persistent = true)
            }
        }
    }

    private fun updateSaveButtons() {
        val enabled = !saveInProgress && visibleAnswerText().isNotBlank()
        if (::saveReadButton.isInitialized) saveReadButton.isEnabled = enabled
        if (::saveEditButton.isInitialized) saveEditButton.isEnabled = enabled
    }

    private fun setSaveEnabled(enabled: Boolean) {
        saveReadButton.isEnabled = enabled
        saveEditButton.isEnabled = enabled
    }

    private fun requestClose() {
        if (saveInProgress) {
            showStatus("페이지에 저장을 마치는 중입니다.", persistent = true)
            return
        }
        if (!hasUnsavedAnswer) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("저장하지 않고 닫을까요?")
            .setNegativeButton("계속 보기", null)
            .setPositiveButton("닫기") { _, _ ->
                hasUnsavedAnswer = false
                finish()
            }
            .show()
    }

    private fun showStatus(message: String, persistent: Boolean = false) {
        if (!::statusBanner.isInitialized) return
        statusBanner.removeCallbacks(hideStatusRunnable)
        statusBanner.text = message
        statusBanner.visibility = View.VISIBLE
        if (!persistent) statusBanner.postDelayed(hideStatusRunnable, STATUS_DURATION_MS)
    }

    private fun hideStatus() {
        if (!::statusBanner.isInitialized) return
        statusBanner.removeCallbacks(hideStatusRunnable)
        statusBanner.visibility = View.GONE
    }

    override fun onStateChanged(state: ChatGptWebViewState) {
        if (queryRunning || mode != WorkspaceMode.GPT) return
        if (state == ChatGptWebViewState.READY) showStatus("ChatGPT 준비됨")
    }

    override fun onManualFallback(fallback: ChatGptManualFallback) {
        when (fallback) {
            is ChatGptManualFallback.Send -> showStatus(fallback.reason, persistent = true)
            is ChatGptManualFallback.Response -> {
                if (fallback.partialText.isNotBlank()) showPartialAnswer(fallback.partialText)
                showStatus(fallback.reason, persistent = true)
            }
        }
    }

    override fun onNavigationBlocked(url: String) {
        showStatus("허용되지 않은 외부 페이지 이동을 막았습니다.")
    }

    override fun onPageError(description: String) {
        showStatus(description, persistent = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ANSWER_TEXT, originalAnswerText)
        outState.putIntArray(STATE_HIDDEN_BLOCKS, hiddenBlockOrdinals.sorted().toIntArray())
        outState.putIntArray(
            STATE_LAST_HIDDEN_BATCH,
            lastHiddenBlockBatch.orEmpty().sorted().toIntArray(),
        )
        outState.putString(STATE_MODE, mode.name)
        outState.putBoolean(STATE_RESPONSE_AVAILABLE, responseAvailable)
        outState.putBoolean(STATE_UNSAVED_ANSWER, hasUnsavedAnswer)
        outState.putString(STATE_ANSWER_FORMAT, answerFormat.name)
        outState.putBoolean(STATE_QUERY_WAS_RUNNING, queryRunning)
    }

    private fun restoreUiState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        val restoredAnswer = validUtf16Prefix(
            state.getString(STATE_ANSWER_TEXT).orEmpty(),
            MAX_ANSWER_CHARS,
        )
        answerFormat = runCatching {
            TeacherGptAnswerFormat.valueOf(state.getString(STATE_ANSWER_FORMAT).orEmpty())
        }.getOrDefault(TeacherGptAnswerFormat.PLAIN_TEXT)
        responseAvailable = state.getBoolean(STATE_RESPONSE_AVAILABLE, restoredAnswer.isNotBlank())
        val restoredUnsavedAnswer = state.getBoolean(STATE_UNSAVED_ANSWER, responseAvailable)
        if (responseAvailable) {
            originalAnswerText = restoredAnswer.trim()
            val knownOrdinals = AssistantAnswerBlocks.parse(originalAnswerText)
                .mapTo(mutableSetOf()) { it.ordinal }
            hiddenBlockOrdinals.clear()
            (state.getIntArray(STATE_HIDDEN_BLOCKS) ?: IntArray(0)).forEach { ordinal ->
                if (ordinal in knownOrdinals) hiddenBlockOrdinals += ordinal
            }
            lastHiddenBlockBatch = (state.getIntArray(STATE_LAST_HIDDEN_BATCH) ?: IntArray(0))
                .filter { it in hiddenBlockOrdinals }
                .toSet()
                .takeIf { it.isNotEmpty() }
            undoDeleteButton.visibility = if (lastHiddenBlockBatch == null) View.GONE else View.VISIBLE
            hasUnsavedAnswer = restoredUnsavedAnswer
            val restoredMode = runCatching {
                WorkspaceMode.valueOf(state.getString(STATE_MODE).orEmpty())
            }.getOrDefault(WorkspaceMode.READ)
            showMode(restoredMode.takeUnless { it == WorkspaceMode.GPT } ?: WorkspaceMode.READ)
        }
        if (state.getBoolean(STATE_QUERY_WAS_RUNNING, false)) {
            queryRunning = false
            sendButton.isEnabled = true
            showStatus("화면이 다시 만들어졌습니다. 질문 보내기를 다시 눌러 주세요.", persistent = true)
        }
    }

    override fun onResume() {
        super.onResume()
        hostResumed = true
        when (mode) {
            WorkspaceMode.GPT -> controller.open()
            WorkspaceMode.READ, WorkspaceMode.EDIT -> formattedAnswerView?.onHostResume()
        }
    }

    override fun onPause() {
        hostResumed = false
        if (mode == WorkspaceMode.GPT) controller.hide()
        formattedAnswerView?.onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::statusBanner.isInitialized) statusBanner.removeCallbacks(hideStatusRunnable)
        formattedAnswerView?.destroyRenderer()
        formattedAnswerView = null
        if (::controller.isInitialized) controller.destroy()
        if (isFinishing && ::request.isInitialized) request.deleteImage(cacheDir)
        super.onDestroy()
    }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun actionTextColors(normalColor: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        ),
        intArrayOf(
            Color.argb(92, Color.red(normalColor), Color.green(normalColor), Color.blue(normalColor)),
            Color.rgb(35, 68, 57),
            normalColor,
        ),
    )

    private fun thinBarBackground(color: Int): InsetDrawable =
        InsetDrawable(roundedBackground(color, 0), 0, dp(9), 0, dp(9))

    /** Keeps a 48dp input/hover lane while drawing only the centered 30dp control surface. */
    private fun thinActionBackground(
        normalColor: Int,
        hoveredColor: Int,
        pressedColor: Int,
        disabledColor: Int,
        selectedColor: Int? = null,
    ): StateListDrawable = StateListDrawable().apply {
        fun inset(color: Int) = InsetDrawable(roundedBackground(color, 8), 0, dp(9), 0, dp(9))
        addState(intArrayOf(-android.R.attr.state_enabled), inset(disabledColor))
        addState(intArrayOf(android.R.attr.state_pressed), inset(pressedColor))
        selectedColor?.let { addState(intArrayOf(android.R.attr.state_selected), inset(it)) }
        addState(intArrayOf(android.R.attr.state_hovered), inset(hoveredColor))
        addState(intArrayOf(android.R.attr.state_focused), inset(hoveredColor))
        addState(intArrayOf(), inset(normalColor))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private enum class WorkspaceMode { GPT, READ, EDIT }

    companion object {
        const val EXTRA_SAVED_RESOURCE_ID = "gptSavedResourceId"
        const val EXTRA_SAVED_BOOK_ID = "gptSavedBookId"
        const val EXTRA_SAVED_PAGE_NUMBER = "gptSavedPageNumber"
        private const val MAX_ANSWER_CHARS = 120_000
        private const val MAX_MANUAL_INPUT_CHARS = 240_000
        private const val STATUS_DURATION_MS = 3_500L
        private const val STATE_ANSWER_TEXT = "gptAnswerText"
        private const val STATE_HIDDEN_BLOCKS = "gptHiddenAnswerBlocks"
        private const val STATE_LAST_HIDDEN_BATCH = "gptLastHiddenAnswerBlockBatch"
        private const val STATE_MODE = "gptMode"
        private const val STATE_RESPONSE_AVAILABLE = "gptResponseAvailable"
        private const val STATE_UNSAVED_ANSWER = "gptUnsavedAnswer"
        private const val STATE_ANSWER_FORMAT = "gptAnswerFormat"
        private const val STATE_QUERY_WAS_RUNNING = "gptQueryWasRunning"
        private const val MENU_GPT = 1
        private const val MENU_COPY = 2
        private const val MENU_PASTE = 3

        fun intent(context: Context, request: GptAssistantRequest): Intent =
            Intent(context, GptAssistantActivity::class.java).apply { request.putInto(this) }
    }

}

internal data class BoundedAssistantAnswer(
    val text: String,
    val truncated: Boolean,
)

/** Keeps the saved answer within its UTF-8-safe character budget and never splits an emoji. */
internal fun boundedAssistantAnswer(source: String, maxChars: Int): BoundedAssistantAnswer {
    require(maxChars > ANSWER_TRUNCATION_NOTICE.length + 16)
    val valid = validUtf16Prefix(source, source.length)
    if (valid.length <= maxChars) return BoundedAssistantAnswer(valid, truncated = false)
    val prefixBudget = maxChars - ANSWER_TRUNCATION_NOTICE.length
    val prefix = validUtf16Prefix(valid, prefixBudget)
    val preferredFloor = prefixBudget * 4 / 5
    val paragraphBoundary = prefix.lastIndexOf("\n\n").takeIf { it >= preferredFloor }
    val lineBoundary = prefix.lastIndexOf('\n').takeIf { it >= preferredFloor }
    val spaceBoundary = prefix.lastIndexOf(' ').takeIf { it >= preferredFloor }
    val end = paragraphBoundary ?: lineBoundary ?: spaceBoundary ?: prefix.length
    val bounded = prefix.substring(0, end).trimEnd() + ANSWER_TRUNCATION_NOTICE
    return BoundedAssistantAnswer(validUtf16Prefix(bounded, maxChars), truncated = true)
}

private const val ANSWER_TRUNCATION_NOTICE = "\n\n[답변이 너무 길어 이후 내용은 생략했습니다.]"

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
            val bookId = requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)).also { require(it.isNotBlank()) }
            val pageNumber = intent.getIntExtra(EXTRA_PAGE_NUMBER, -1).also { require(it >= 0) }
            val slot = intent.getIntExtra(EXTRA_PROMPT_SLOT, -1).also { require(it in 1..4) }
            val title = requireNotNull(intent.getStringExtra(EXTRA_PROMPT_TITLE)).also { require(it.isNotBlank()) }
            val body = requireNotNull(intent.getStringExtra(EXTRA_PROMPT_BODY)).also { require(it.isNotBlank()) }
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
