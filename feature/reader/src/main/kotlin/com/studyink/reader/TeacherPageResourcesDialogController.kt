package com.studyink.reader

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.studyink.assistant.core.AssistantPageKey
import com.studyink.assistant.core.AssistantPromptSlot
import com.studyink.assistant.core.StudentExplanationTarget
import com.studyink.assistant.core.TeacherGptResource
import com.studyink.assistant.core.TeacherGptResourceRevision
import com.studyink.core.model.PageBounds

/**
 * The teacher's GPT library is page-scoped even before a student attempt exists. Publishing a
 * card, however, is possible only when [studentAttemptNo] names the exact attempt on screen.
 */
data class TeacherPageAssistantTarget(
    val page: AssistantPageKey,
    val studentAttemptNo: Int?,
) {
    init {
        require(studentAttemptNo == null || studentAttemptNo > 0)
    }

    fun studentTargetOrNull(): StudentExplanationTarget? = studentAttemptNo?.let { attemptNo ->
        StudentExplanationTarget(page, attemptNo)
    }
}

/** Snapshot passed when the teacher selects one of the four fixed GPT prompt slots. */
data class TeacherPromptChoice(
    val target: TeacherPageAssistantTarget,
    val prompt: AssistantPromptSlot,
)

/**
 * Exact, explicit student-send request. The host turns this into a card and publishes it; this
 * UI-only controller never reads storage or invokes LAN/Telegram transport.
 */
data class TeacherExplanationSendDraft(
    val target: StudentExplanationTarget,
    val sourceResourceId: String,
    val sourceResourceRevisionId: String,
    val title: String,
    val text: String,
    val anchorBounds: PageBounds,
)

/**
 * Compact teacher UI for page-scoped GPT resources.
 *
 * Repository/WebView work must be completed off the main thread and supplied as immutable values
 * to [show] or [updateResources]. Selecting a prompt and sending an edited excerpt are explicit
 * callbacks, allowing the host to keep its existing lifecycle, attempt routing, and transport.
 */
class TeacherPageResourcesDialogController(
    private val context: Context,
    private val onPromptSelected: (TeacherPromptChoice) -> Unit,
    private val onSend: (TeacherExplanationSendDraft) -> Unit,
    private val onDismiss: () -> Unit = {},
) {
    private val density = context.resources.displayMetrics.density
    private val dialog = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(true)
        setOnDismissListener { onDismiss() }
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.rgb(255, 253, 247))
            setStroke(dp(1), Color.rgb(201, 195, 181))
        }
    }
    private val heading = label(context, 19f, bold = true)
    private val targetLabel = label(context, 12f, Color.rgb(104, 98, 87))
    private val promptButtons = List(EXACT_PROMPT_COUNT) { index ->
        Button(context).apply {
            isAllCaps = false
            minHeight = dp(44)
            setPadding(dp(7), dp(4), dp(7), dp(4))
            setOnClickListener { dispatchPrompt(index) }
        }
    }
    private val resourceSpinner = Spinner(context)
    private val revisionSpinner = Spinner(context)
    private val resourceDetail = label(context, 12f, Color.rgb(83, 78, 69)).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9).toFloat()
            setColor(Color.rgb(247, 244, 235))
        }
    }
    private val answerPreview = label(context, 13f, Color.rgb(63, 59, 52)).apply {
        maxLines = 6
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9).toFloat()
            setColor(Color.rgb(250, 249, 244))
            setStroke(dp(1), Color.rgb(222, 217, 205))
        }
    }
    private val fullAnswerButton = Button(context).apply {
        text = "전체 답변 보기"
        isAllCaps = false
        visibility = View.GONE
        setOnClickListener { showFullAnswer() }
    }
    private val titleEditor = EditText(context).apply {
        hint = "학생에게 보일 제목"
        isSingleLine = true
        filters = arrayOf(InputFilter.LengthFilter(MAX_TITLE_CHARS))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    private val excerptEditor = EditText(context).apply {
        hint = "학생에게 보낼 설명을 골라 다듬으세요"
        gravity = Gravity.TOP or Gravity.START
        minLines = 5
        maxLines = 12
        filters = arrayOf(InputFilter.LengthFilter(MAX_EDITABLE_EXCERPT_CHARS))
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isVerticalScrollBarEnabled = true
    }
    private val truncationNote = label(context, 11f, Color.rgb(139, 91, 42)).apply {
        visibility = View.GONE
        text = "긴 답변은 앞 ${MAX_EDITABLE_EXCERPT_CHARS}자만 편집기에 불러왔습니다."
    }
    private val sendButton = Button(context).apply {
        text = "학생에게 보내기"
        isAllCaps = false
        isEnabled = false
        setOnClickListener { dispatchSend() }
    }
    private val closeButton = Button(context).apply {
        text = "닫기"
        isAllCaps = false
        setOnClickListener { dialog.dismiss() }
    }

    private var target: TeacherPageAssistantTarget? = null
    private var promptSlots: List<AssistantPromptSlot> = emptyList()
    private var resources: List<TeacherGptResource> = emptyList()
    private var displayedResources: List<TeacherGptResource> = emptyList()
    private var displayedRevisions: List<TeacherGptResourceRevision> = emptyList()
    private var selectedRevision: TeacherGptResourceRevision? = null
    private var bindingSpinners = false

    init {
        buildContent(context)
        dialog.setContentView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
        resourceSpinner.onItemSelectedListener = SimpleItemSelectedListener { bindSelectedResource() }
        revisionSpinner.onItemSelectedListener = SimpleItemSelectedListener { bindSelectedRevision() }
        val editorWatcher = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                updateSendEnabled()
            }
            override fun afterTextChanged(editable: Editable?) = Unit
        }
        titleEditor.addTextChangedListener(editorWatcher)
        excerptEditor.addTextChangedListener(editorWatcher)
    }

    /** Exactly four fixed-position prompt slots are required; resource rows from other pages drop. */
    fun show(
        target: TeacherPageAssistantTarget,
        promptSlots: List<AssistantPromptSlot>,
        resources: List<TeacherGptResource>,
    ) {
        this.target = target
        this.promptSlots = canonicalPrompts(promptSlots)
        this.resources = resources.filter { it.page == target.page }.toList()
        bindAll()
        dialog.show()
        val availableWidth = context.resources.displayMetrics.widthPixels - dp(32)
        dialog.window?.setLayout(minOf(availableWidth, dp(MAX_DIALOG_WIDTH_DP)), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Rejects a late background result after the reader has moved to another page or attempt. */
    fun updateResources(
        expectedTarget: TeacherPageAssistantTarget,
        resources: List<TeacherGptResource>,
    ): Boolean {
        if (expectedTarget != target) return false
        val selectedResourceId = selectedResource()?.resourceId
        val selectedRevisionId = selectedRevision?.revisionId
        this.resources = resources.filter { it.page == expectedTarget.page }.toList()
        bindResourceSpinner(selectedResourceId, selectedRevisionId)
        return true
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing

    private fun buildContent(context: Context) {
        heading.text = "페이지 설명 자료"
        root.addView(heading, matchWrap(bottom = 2))
        root.addView(targetLabel, matchWrap(bottom = 12))
        root.addView(sectionLabel(context, "GPT 질문"), matchWrap(bottom = 6))
        repeat(2) { rowIndex ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(2) { columnIndex ->
                val button = promptButtons[rowIndex * 2 + columnIndex]
                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (columnIndex > 0) marginStart = dp(6)
                    },
                )
            }
            root.addView(row, matchWrap(bottom = if (rowIndex == 0) 6 else 14))
        }
        root.addView(sectionLabel(context, "저장된 답변"), matchWrap(bottom = 4))
        root.addView(resourceSpinner, matchWrap(bottom = 6))
        root.addView(revisionSpinner, matchWrap(bottom = 8))
        root.addView(resourceDetail, matchWrap(bottom = 6))
        root.addView(answerPreview, matchWrap(bottom = 12))
        root.addView(fullAnswerButton, matchWrap(bottom = 12))
        root.addView(sectionLabel(context, "학생 카드 제목"), matchWrap(bottom = 2))
        root.addView(titleEditor, matchWrap(bottom = 8))
        root.addView(sectionLabel(context, "보낼 발췌문"), matchWrap(bottom = 2))
        root.addView(excerptEditor, matchWrap(bottom = 2))
        root.addView(truncationNote, matchWrap(bottom = 10))
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(
            closeButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.38f),
        )
        actions.addView(
            sendButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.62f).apply {
                marginStart = dp(8)
            },
        )
        root.addView(actions, matchWrap())
    }

    private fun bindAll() {
        val currentTarget = checkNotNull(target)
        heading.text = "${currentTarget.page.pageNumber + 1}쪽 설명 자료"
        targetLabel.text = currentTarget.studentAttemptNo?.let { attemptNo ->
            "회차 $attemptNo · 이 대상에만 전송"
        } ?: "페이지 전용 저장 · 학생 회차를 선택하면 전송 가능"
        promptSlots.forEachIndexed { index, slot ->
            promptButtons[index].text = "${slot.slotNumber}. ${slot.title.ifBlank { "질문" }}"
            promptButtons[index].contentDescription = "GPT 질문 ${slot.slotNumber}: ${slot.title}"
        }
        bindResourceSpinner(preferredResourceId = null, preferredRevisionId = null)
    }

    private fun bindResourceSpinner(
        preferredResourceId: String?,
        preferredRevisionId: String?,
    ) {
        bindingSpinners = true
        displayedResources = resources.sortedWith(
            compareByDescending<TeacherGptResource> { resource ->
                resource.revisions.maxOfOrNull { it.createdAtEpochMillis } ?: resource.createdAtEpochMillis
            }.thenBy { it.resourceId },
        )
        val labels = if (displayedResources.isEmpty()) {
            listOf("저장된 답변 없음")
        } else {
            displayedResources.map { resource ->
                "${resource.title.ifBlank { "제목 없음" }} · ${resource.revisions.size}개 버전"
            }
        }
        resourceSpinner.adapter = arrayAdapter(labels)
        resourceSpinner.isEnabled = displayedResources.isNotEmpty()
        val resourceIndex = displayedResources.indexOfFirst { it.resourceId == preferredResourceId }
            .takeIf { it >= 0 } ?: 0
        if (displayedResources.isNotEmpty()) resourceSpinner.setSelection(resourceIndex, false)
        bindingSpinners = false
        bindSelectedResource(preferredRevisionId)
    }

    private fun bindSelectedResource(preferredRevisionId: String? = null) {
        if (bindingSpinners) return
        val resource = selectedResource()
        bindingSpinners = true
        displayedRevisions = resource?.revisions
            ?.sortedWith(compareByDescending<TeacherGptResourceRevision> { it.revisionNumber }
                .thenByDescending { it.createdAtEpochMillis })
            .orEmpty()
        val labels = if (displayedRevisions.isEmpty()) {
            listOf("버전 없음")
        } else {
            displayedRevisions.map { revision ->
                "버전 ${revision.revisionNumber} · ${revision.promptTitle.ifBlank { "질문 ${revision.promptSlotNumber}" }}"
            }
        }
        revisionSpinner.adapter = arrayAdapter(labels)
        revisionSpinner.isEnabled = displayedRevisions.isNotEmpty()
        val desiredRevisionId = preferredRevisionId ?: resource?.currentRevisionId
        val revisionIndex = displayedRevisions.indexOfFirst { it.revisionId == desiredRevisionId }
            .takeIf { it >= 0 } ?: 0
        if (displayedRevisions.isNotEmpty()) revisionSpinner.setSelection(revisionIndex, false)
        bindingSpinners = false
        bindSelectedRevision()
    }

    private fun bindSelectedRevision() {
        if (bindingSpinners) return
        val resource = selectedResource()
        val revision = displayedRevisions.getOrNull(revisionSpinner.selectedItemPosition)
        selectedRevision = revision
        if (resource == null || revision == null) {
            resourceDetail.text = "이 페이지에 저장된 GPT 답변이 없습니다. 위 질문으로 새 답변을 만드세요."
            answerPreview.text = ""
            answerPreview.visibility = View.GONE
            fullAnswerButton.visibility = View.GONE
            titleEditor.setText("")
            excerptEditor.setText("")
            titleEditor.isEnabled = false
            excerptEditor.isEnabled = false
            truncationNote.visibility = View.GONE
            updateSendEnabled()
            return
        }
        titleEditor.isEnabled = true
        excerptEditor.isEnabled = true
        answerPreview.visibility = View.VISIBLE
        fullAnswerButton.visibility = View.VISIBLE
        resourceDetail.text = buildString {
            append("질문 ${revision.promptSlotNumber}: ${revision.promptTitle.ifBlank { "제목 없음" }}")
            revision.providerName?.takeIf { it.isNotBlank() }?.let { append("\n제공: $it") }
            append("\n응답 ${revision.answerText.length}자")
        }
        answerPreview.text = revision.answerText.take(MAX_DETAIL_PREVIEW_CHARS)
        titleEditor.setText(resource.title.take(MAX_TITLE_CHARS))
        val excerpt = revision.answerText.take(MAX_EDITABLE_EXCERPT_CHARS)
        excerptEditor.setText(excerpt)
        excerptEditor.setSelection(excerpt.length)
        truncationNote.visibility = if (revision.answerText.length > excerpt.length) View.VISIBLE else View.GONE
        updateSendEnabled()
    }

    private fun showFullAnswer() {
        val revision = selectedRevision ?: return
        val answer = TextView(context).apply {
            text = revision.answerText
            textSize = 15f
            setTextColor(Color.rgb(45, 42, 37))
            setTextIsSelectable(true)
            setPadding(dp(18), dp(12), dp(18), dp(20))
        }
        AlertDialog.Builder(context)
            .setTitle(revision.promptTitle.ifBlank { "저장된 GPT 답변" })
            .setView(ScrollView(context).apply {
                isFillViewport = true
                addView(
                    answer,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            })
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun dispatchPrompt(index: Int) {
        val currentTarget = target ?: return
        val prompt = promptSlots.getOrNull(index) ?: return
        onPromptSelected(TeacherPromptChoice(currentTarget, prompt))
    }

    private fun dispatchSend() {
        val currentTarget = target ?: return
        val studentTarget = currentTarget.studentTargetOrNull() ?: return
        val resource = selectedResource() ?: return
        val revision = selectedRevision ?: return
        val title = titleEditor.text?.toString()?.trim().orEmpty()
        val text = excerptEditor.text?.toString()?.trim().orEmpty()
        if (title.isEmpty() || text.isEmpty()) return
        onSend(
            TeacherExplanationSendDraft(
                target = studentTarget,
                sourceResourceId = resource.resourceId,
                sourceResourceRevisionId = revision.revisionId,
                title = title,
                text = text,
                anchorBounds = revision.selectionBounds,
            ),
        )
    }

    private fun updateSendEnabled() {
        sendButton.isEnabled = target?.studentAttemptNo != null && selectedRevision != null &&
            titleEditor.text?.isNotBlank() == true && excerptEditor.text?.isNotBlank() == true
    }

    private fun selectedResource(): TeacherGptResource? =
        displayedResources.getOrNull(resourceSpinner.selectedItemPosition)

    private fun canonicalPrompts(slots: List<AssistantPromptSlot>): List<AssistantPromptSlot> {
        val sorted = slots.sortedBy { it.slotNumber }
        require(sorted.size == EXACT_PROMPT_COUNT) { "Exactly four assistant prompt slots are required" }
        require(sorted.map { it.slotNumber } == (1..EXACT_PROMPT_COUNT).toList()) {
            "Assistant prompt slots must be numbered 1 through 4"
        }
        return sorted.toList()
    }

    private fun arrayAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(context, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun label(
        context: Context,
        textSizeSp: Float,
        colour: Int = Color.rgb(45, 42, 37),
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        textSize = textSizeSp
        setTextColor(colour)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sectionLabel(context: Context, value: String): TextView =
        label(context, 13f, bold = true).apply { text = value }

    private fun matchWrap(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int = (value * density).toInt()

    private class SimpleItemSelectedListener(
        private val onSelected: () -> Unit,
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelected()
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
            onSelected()
        }
    }

    private companion object {
        const val EXACT_PROMPT_COUNT = 4
        const val MAX_DIALOG_WIDTH_DP = 560
        const val MAX_TITLE_CHARS = 200
        const val MAX_DETAIL_PREVIEW_CHARS = 4_000
        const val MAX_EDITABLE_EXCERPT_CHARS = 16_000
    }
}
