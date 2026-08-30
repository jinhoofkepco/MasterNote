package com.studyink.reader

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.studyink.assistant.core.AssistantPageKey
import com.studyink.assistant.core.AssistantPromptSlot
import com.studyink.assistant.core.StudentExplanationTarget
import com.studyink.assistant.core.TeacherGptAnswerFormat
import com.studyink.assistant.core.TeacherGptResource
import com.studyink.assistant.core.TeacherGptResourceRevision
import com.studyink.core.model.PageBounds
import kotlin.math.roundToInt

/** A GPT resource belongs to the page; a student publication also requires an exact attempt. */
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

data class TeacherPromptChoice(
    val target: TeacherPageAssistantTarget,
    val prompt: AssistantPromptSlot,
)

/** Immutable, exact-target request. This UI never calls LAN or Telegram directly. */
data class TeacherExplanationSendDraft(
    val target: StudentExplanationTarget,
    val sourceResourceId: String,
    val sourceResourceRevisionId: String,
    val title: String,
    val text: String,
    val anchorBounds: PageBounds,
)

/**
 * One job per state: compact prompt strip -> saved-answer list -> answer -> student excerpt.
 * The compact strip has a 30dp visible surface inside a 48dp input window.
 */
class TeacherPageResourcesDialogController(
    private val context: Context,
    private val onPromptSelected: (TeacherPromptChoice) -> Unit,
    private val onSend: (TeacherExplanationSendDraft, (Result<Unit>) -> Unit) -> Unit,
    private val onDismiss: () -> Unit = {},
) {
    private val density = context.resources.displayMetrics.density
    private val dialog = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) {
                false
            } else {
                handleBack()
                true
            }
        }
        setOnDismissListener {
            sendGeneration += 1L
            sendInProgress = false
            releaseFormattedAnswer()
            mode = Mode.COMPACT
            onDismiss()
        }
    }

    private var target: TeacherPageAssistantTarget? = null
    private var promptSlots: List<AssistantPromptSlot> = emptyList()
    private var resources: List<TeacherGptResource> = emptyList()
    private var selectedResourceId: String? = null
    private var selectedRevisionId: String? = null
    private var formattedAnswerView: FormattedAssistantAnswerView? = null
    private var mode = Mode.COMPACT
    private var sendInProgress = false
    private var sendGeneration = 0L

    fun show(
        target: TeacherPageAssistantTarget,
        promptSlots: List<AssistantPromptSlot>,
        resources: List<TeacherGptResource>,
        openLibrary: Boolean = false,
    ) {
        val targetChanged = this.target != target
        this.target = target
        this.promptSlots = canonicalPrompts(promptSlots)
        this.resources = resources.filter { it.page == target.page }.sortedWith(resourceOrder)
        if (targetChanged || selectedResource() == null) {
            selectedResourceId = this.resources.firstOrNull()?.resourceId
            selectedRevisionId = this.resources.firstOrNull()?.currentRevisionId
        }
        if (!dialog.isShowing) dialog.show()
        if (openLibrary) showLibrary() else showCompact()
    }

    /** Rejects a late repository result after the reader has moved to a different exact target. */
    fun updateResources(
        expectedTarget: TeacherPageAssistantTarget,
        resources: List<TeacherGptResource>,
    ): Boolean {
        if (expectedTarget != target) return false
        this.resources = resources.filter { it.page == expectedTarget.page }.sortedWith(resourceOrder)
        if (selectedResource() == null) {
            selectedResourceId = this.resources.firstOrNull()?.resourceId
            selectedRevisionId = this.resources.firstOrNull()?.currentRevisionId
        }
        when (mode) {
            Mode.COMPACT -> showCompact()
            Mode.LIBRARY -> showLibrary()
            Mode.DETAIL -> selectedRevision()?.let { showDetail() } ?: showLibrary()
            Mode.SEND -> selectedRevision()?.let { showSendEditor() } ?: showLibrary()
        }
        return true
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing

    fun onHostConfigurationChanged() {
        if (!dialog.isShowing) return
        dialog.window?.decorView?.post {
            if (dialog.isShowing) configureWindow(mode)
        }
    }

    private fun showCompact() {
        mode = Mode.COMPACT
        releaseFormattedAnswer()
        dialog.setCanceledOnTouchOutside(true)
        val host = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                View(context).apply { background = compactBarBackground() },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(COMPACT_VISIBLE_HEIGHT_DP),
                    Gravity.CENTER,
                ),
            )
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(compactCell("×", "GPT 메뉴 닫기") { dialog.dismiss() }, fixedWidth(dp(44)))
        row.addView(label(context, 11f, bold = true).apply {
            text = "GPT"
            gravity = Gravity.CENTER
        }, fixedWidth(dp(38)))
        promptSlots.forEach { prompt ->
            row.addView(
                compactCell(
                    "${superscript(prompt.slotNumber)}${compactPromptLabel(prompt)}",
                    "GPT 질문 ${prompt.slotNumber}: ${prompt.title}",
                ) { dispatchPrompt(prompt) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
        }
        val resourceLabel = buildString {
            append("자료")
            if (resources.isNotEmpty()) append(superscriptCount(resources.size))
        }
        row.addView(
            compactCell(resourceLabel, "저장된 GPT 답변 ${resources.size}개") { showLibrary() },
            fixedWidth(dp(54)),
        )
        host.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setDialogContent(host)
        configureWindow(Mode.COMPACT)
    }

    private fun showLibrary() {
        mode = Mode.LIBRARY
        releaseFormattedAnswer()
        dialog.setCanceledOnTouchOutside(true)
        val currentTarget = checkNotNull(target)
        val root = panelRoot()
        root.addView(
            panelHeader(
                title = "${currentTarget.page.pageNumber + 1}쪽 GPT 자료",
                leading = "×" to { dialog.dismiss() },
                trailing = "+ 질문" to { showCompact() },
            ),
            matchWrap(),
        )
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(16))
        }
        if (resources.isEmpty()) {
            body.addView(label(context, 15f, Color.rgb(91, 89, 82)).apply {
                text = "저장된 답변이 없습니다.\n위의 ‘+ 질문’에서 질문 방식을 고르세요."
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(28))
            }, matchWrap())
        } else {
            resources.forEach { resource -> body.addView(resourceRow(resource), matchWrap(bottom = 2)) }
        }
        root.addView(
            ScrollView(context).apply {
                isFillViewport = false
                addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setDialogContent(root)
        configureWindow(Mode.LIBRARY)
    }

    private fun resourceRow(resource: TeacherGptResource): View {
        val revision = resource.currentRevision
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(10), dp(7), dp(6), dp(7))
            background = rowBackground()
            isClickable = true
            isFocusable = true
            contentDescription = "${resource.title}, ${resource.revisions.size}개 버전"
            setOnClickListener {
                selectedResourceId = resource.resourceId
                selectedRevisionId = resource.currentRevisionId
                showDetail()
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(context, 15f, bold = true).apply {
                    text = resource.title.ifBlank { revision.promptTitle.ifBlank { "저장된 답변" } }
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, matchWrap(bottom = 3))
                addView(label(context, 12f, Color.rgb(104, 101, 93)).apply {
                    text = buildString {
                        append("${superscript(revision.promptSlotNumber)}${compactPromptLabel(revision.promptSlotNumber, revision.promptTitle)}")
                        append(" · 버전 ${revision.revisionNumber}")
                        val preview = assistantPreviewText(
                            revision.answerText,
                            RESOURCE_PREVIEW_CHARS,
                            revision.answerFormat,
                        )
                        if (preview.isNotBlank()) append("\n$preview")
                    }
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, matchWrap())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(context, 22f, Color.rgb(75, 91, 84)).apply {
                text = "›"
                gravity = Gravity.CENTER
            }, fixedWidth(dp(38)))
        }
    }

    private fun showDetail() {
        val resource = selectedResource() ?: return showLibrary()
        val revision = selectedRevision() ?: resource.currentRevision.also {
            selectedRevisionId = it.revisionId
        }
        mode = Mode.DETAIL
        releaseFormattedAnswer()
        dialog.setCanceledOnTouchOutside(true)
        val root = panelRoot()
        val versionAction = if (resource.revisions.size > 1) {
            "v${revision.revisionNumber}⌄" to { anchor: View -> showVersionMenu(anchor, resource) }
        } else {
            "v${revision.revisionNumber}" to { _: View -> Unit }
        }
        root.addView(
            detailHeader(
                title = resource.title.ifBlank { revision.promptTitle.ifBlank { "저장된 답변" } },
                versionAction = versionAction,
            ),
            matchWrap(),
        )
        val rendered = FormattedAssistantAnswerView(context).also { view ->
            formattedAnswerView = view
            view.render(revision.answerText, revision.answerFormat)
        }
        root.addView(rendered, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        target?.studentTargetOrNull()?.let { studentTarget ->
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(8))
                background = panelFooterBackground()
                addView(label(context, 12f, Color.rgb(95, 93, 86)).apply {
                    text = "${studentTarget.page.pageNumber + 1}쪽 · ${studentTarget.attemptNo}회차"
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(primaryButton("학생에게") { showSendEditor() }, fixedWidth(dp(112)))
            }, matchWrap())
        }
        setDialogContent(root)
        configureWindow(Mode.DETAIL)
    }

    private fun showVersionMenu(anchor: View, resource: TeacherGptResource) {
        val revisions = resource.revisions.sortedByDescending { it.revisionNumber }
        PopupMenu(context, anchor).apply {
            revisions.forEachIndexed { index, revision ->
                menu.add(0, index, index, "버전 ${revision.revisionNumber} · ${revision.promptTitle}")
            }
            setOnMenuItemClickListener { item ->
                selectedRevisionId = revisions.getOrNull(item.itemId)?.revisionId
                showDetail()
                true
            }
        }.show()
    }

    private fun showSendEditor() {
        if (sendInProgress) return
        val currentTarget = target?.studentTargetOrNull() ?: return
        val resource = selectedResource() ?: return
        val revision = selectedRevision() ?: return
        mode = Mode.SEND
        sendInProgress = false
        releaseFormattedAnswer()
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        val root = panelRoot()
        root.addView(
            panelHeader(
                title = "${currentTarget.page.pageNumber + 1}쪽 · ${currentTarget.attemptNo}회차에 보내기",
                leading = "‹" to { showDetail() },
                trailing = "×" to { dialog.dismiss() },
            ),
            matchWrap(),
        )
        val titleEditor = EditText(context).apply {
            hint = "학생에게 보일 제목"
            isSingleLine = true
            textSize = 16f
            filters = arrayOf(InputFilter.LengthFilter(MAX_TITLE_CHARS))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(resource.title.take(MAX_TITLE_CHARS))
            setSelection(text.length)
        }
        val excerpt = assistantStudentText(revision.answerText, revision.answerFormat)
            .let { validUtf16Prefix(it, MAX_EDITABLE_EXCERPT_CHARS) }
        val excerptEditor = EditText(context).apply {
            hint = "학생에게 보낼 설명을 골라 다듬으세요"
            gravity = Gravity.TOP or Gravity.START
            textSize = 16f
            setLineSpacing(dp(4).toFloat(), 1.12f)
            filters = arrayOf(InputFilter.LengthFilter(MAX_EDITABLE_EXCERPT_CHARS))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isVerticalScrollBarEnabled = true
            setPadding(dp(14), dp(12), dp(14), dp(14))
            setText(excerpt)
            setSelection(text.length)
        }
        val editors = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
            addView(titleEditor, matchWrap(bottom = 8))
            addView(excerptEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(editors, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val sendStatus = label(context, 12f, Color.rgb(151, 55, 47), bold = true).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(3), dp(12), dp(3))
        }
        root.addView(sendStatus, matchWrap())
        val sendButtonText =
            "${currentTarget.page.pageNumber + 1}쪽 · ${currentTarget.attemptNo}회차에 보내기"
        lateinit var sendButton: Button
        sendButton = primaryButton(
            sendButtonText,
        ) {
            if (sendInProgress) return@primaryButton
            val title = titleEditor.text?.toString()?.trim().orEmpty()
            val text = excerptEditor.text?.toString()?.trim().orEmpty()
            if (title.isBlank() || text.isBlank()) return@primaryButton
            val draft = TeacherExplanationSendDraft(
                target = currentTarget,
                sourceResourceId = resource.resourceId,
                sourceResourceRevisionId = revision.revisionId,
                title = title,
                text = text,
                anchorBounds = revision.selectionBounds,
            )
            val generation = ++sendGeneration
            sendInProgress = true
            dialog.setCancelable(false)
            sendStatus.apply {
                this.text = "저장 중…"
                setTextColor(Color.rgb(88, 91, 84))
                visibility = View.VISIBLE
            }
            sendButton.text = "저장 중…"
            setEnabledRecursively(root, false)
            val finishSend: (Result<Unit>) -> Unit = { result ->
                root.post {
                    val stillSameRequest = sendInProgress && generation == sendGeneration &&
                        dialog.isShowing && mode == Mode.SEND &&
                        target?.studentTargetOrNull() == draft.target &&
                        selectedResourceId == draft.sourceResourceId &&
                        selectedRevision()?.revisionId == draft.sourceResourceRevisionId
                    if (!stillSameRequest) return@post
                    sendInProgress = false
                    result.onSuccess {
                        dialog.dismiss()
                    }.onFailure {
                        dialog.setCancelable(true)
                        setEnabledRecursively(root, true)
                        sendButton.text = sendButtonText
                        sendButton.isEnabled =
                            titleEditor.text.isNotBlank() && excerptEditor.text.isNotBlank()
                        sendStatus.apply {
                            this.text = "저장하지 못했습니다. 내용은 그대로입니다. 다시 눌러주세요."
                            setTextColor(Color.rgb(151, 55, 47))
                            visibility = View.VISIBLE
                        }
                    }
                }
            }
            runCatching { onSend(draft, finishSend) }
                .onFailure { error -> finishSend(Result.failure(error)) }
        }.apply { isEnabled = titleEditor.text.isNotBlank() && excerptEditor.text.isNotBlank() }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sendButton.isEnabled = titleEditor.text.isNotBlank() && excerptEditor.text.isNotBlank()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        titleEditor.addTextChangedListener(watcher)
        excerptEditor.addTextChangedListener(watcher)
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
            background = panelFooterBackground()
            addView(secondaryButton("취소") { showDetail() }, LinearLayout.LayoutParams(0, dp(48), 0.32f))
            addView(sendButton, LinearLayout.LayoutParams(0, dp(48), 0.68f).apply {
                marginStart = dp(8)
            })
        }, matchWrap())
        setDialogContent(root)
        configureWindow(Mode.SEND)
    }

    private fun detailHeader(
        title: String,
        versionAction: Pair<String, (View) -> Unit>,
    ): View = thinHeaderHost(
        leading = headerButton("‹", "답변 목록") { showLibrary() },
        title = "${target?.page?.pageNumber?.plus(1)}쪽 · $title",
        trailing = listOf(
            headerButton(versionAction.first, "답변 버전 선택") { view ->
                versionAction.second(view)
            } to dp(58),
            headerButton("×", "닫기") { dialog.dismiss() } to dp(48),
        ),
    )

    private fun panelHeader(
        title: String,
        leading: Pair<String, () -> Unit>,
        trailing: Pair<String, () -> Unit>,
    ): View = thinHeaderHost(
        leading = headerButton(leading.first, leading.first) { leading.second() },
        title = title,
        trailing = listOf(headerButton(trailing.first, trailing.first) { trailing.second() } to dp(70)),
    )

    /** Thirty visible dp, with a 48dp input lane so finger and S Pen targeting stay reliable. */
    private fun thinHeaderHost(
        leading: View,
        title: String,
        trailing: List<Pair<View, Int>>,
    ): View = FrameLayout(context).apply {
        addView(
            View(context).apply { background = panelHeaderBackground() },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(COMPACT_VISIBLE_HEIGHT_DP),
                Gravity.CENTER,
            ),
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(leading, fixedWidth(dp(48)))
            addView(label(context, 13f, bold = true).apply {
                text = title
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            trailing.forEach { (view, width) -> addView(view, fixedWidth(width)) }
        }
        addView(
            row,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MINIMUM_TOUCH_DP)),
        )
    }

    private fun compactCell(text: String, description: String, action: () -> Unit): View =
        FrameLayout(context).apply {
            minimumWidth = dp(MINIMUM_TOUCH_DP)
            minimumHeight = dp(MINIMUM_TOUCH_DP)
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { action() }
            addView(label(context, 11f, Color.rgb(48, 55, 52), bold = true).apply {
                this.text = text
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(2), 0, dp(2), 0)
                isDuplicateParentStateEnabled = true
                setTextColor(thinActionTextColors())
                background = thinActionBackground()
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(COMPACT_VISIBLE_HEIGHT_DP),
                Gravity.CENTER,
            ))
        }

    private fun headerButton(
        text: String,
        description: String,
        action: (View) -> Unit,
    ): View = FrameLayout(context).apply {
        minimumWidth = dp(MINIMUM_TOUCH_DP)
        minimumHeight = dp(MINIMUM_TOUCH_DP)
        isClickable = true
        isFocusable = true
        contentDescription = description
        setOnClickListener(action)
        addView(TextView(context).apply {
            this.text = text
            textSize = if (text.length <= 1) 22f else 12f
            gravity = Gravity.CENTER
            maxLines = 1
            isDuplicateParentStateEnabled = true
            setTextColor(thinActionTextColors())
            background = thinActionBackground()
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(COMPACT_VISIBLE_HEIGHT_DP),
            Gravity.CENTER,
        ))
    }

    private fun primaryButton(text: String, action: () -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        minHeight = dp(MINIMUM_TOUCH_DP)
        setTextColor(actionButtonTextColors(Color.WHITE, Color.rgb(171, 178, 174)))
        background = actionButtonBackground(
            normal = Color.rgb(57, 91, 79),
            pressed = Color.rgb(38, 69, 59),
            hovered = Color.rgb(72, 108, 95),
            disabled = Color.rgb(218, 220, 215),
        )
        setOnClickListener { action() }
    }

    private fun secondaryButton(text: String, action: () -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        minHeight = dp(MINIMUM_TOUCH_DP)
        setTextColor(actionButtonTextColors(Color.rgb(62, 64, 59), Color.rgb(157, 157, 151)))
        background = actionButtonBackground(
            normal = Color.rgb(239, 238, 232),
            pressed = Color.rgb(216, 218, 210),
            hovered = Color.rgb(231, 232, 224),
            disabled = Color.rgb(246, 245, 241),
        )
        setOnClickListener { action() }
    }

    private fun thinActionBackground(): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(-android.R.attr.state_enabled),
            roundedBackground(Color.argb(34, 90, 92, 87), 4),
        )
        addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedBackground(Color.argb(58, 52, 88, 75), 4),
        )
        addState(
            intArrayOf(android.R.attr.state_hovered),
            roundedBackground(Color.argb(42, 52, 88, 75), 4),
        )
        addState(
            intArrayOf(android.R.attr.state_focused),
            roundedBackground(Color.argb(42, 52, 88, 75), 4),
        )
        addState(intArrayOf(), roundedBackground(Color.TRANSPARENT, 4))
    }

    private fun thinActionTextColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_hovered),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(),
        ),
        intArrayOf(
            Color.rgb(154, 155, 150),
            Color.rgb(29, 68, 55),
            Color.rgb(38, 78, 64),
            Color.rgb(38, 78, 64),
            Color.rgb(48, 55, 52),
        ),
    )

    private fun actionButtonBackground(
        normal: Int,
        pressed: Int,
        hovered: Int,
        disabled: Int,
    ): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), roundedBackground(disabled, 11))
        addState(intArrayOf(android.R.attr.state_pressed), roundedBackground(pressed, 11))
        addState(intArrayOf(android.R.attr.state_hovered), roundedBackground(hovered, 11))
        addState(intArrayOf(android.R.attr.state_focused), roundedBackground(hovered, 11))
        addState(intArrayOf(), roundedBackground(normal, 11))
    }

    private fun actionButtonTextColors(normal: Int, disabled: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, normal),
    )

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setEnabledRecursively(view.getChildAt(index), enabled)
            }
        }
    }

    private fun setDialogContent(content: View) {
        dialog.setContentView(content)
        if (!dialog.isShowing) dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun configureWindow(targetMode: Mode) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val metrics = context.resources.displayMetrics
        val wide = context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_DP ||
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val attributes = window.attributes
        when (targetMode) {
            Mode.COMPACT -> {
                attributes.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                window.attributes = attributes
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(COMPACT_TOUCH_HEIGHT_DP))
            }
            Mode.LIBRARY -> if (wide) {
                attributes.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                window.attributes = attributes
                window.setLayout(
                    minOf(dp(TABLET_PANEL_WIDTH_DP), (metrics.widthPixels * 0.42f).roundToInt()),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            } else {
                attributes.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                window.attributes = attributes
                window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    minOf(dp(PHONE_LIBRARY_MAX_HEIGHT_DP), (metrics.heightPixels * 0.44f).roundToInt()),
                )
            }
            Mode.DETAIL, Mode.SEND -> if (wide) {
                attributes.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                window.attributes = attributes
                window.setLayout(
                    minOf(dp(TABLET_DETAIL_WIDTH_DP), (metrics.widthPixels * 0.46f).roundToInt()),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            } else {
                attributes.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                window.attributes = attributes
                window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (metrics.heightPixels * 0.90f).roundToInt(),
                )
            }
        }
    }

    private fun dispatchPrompt(prompt: AssistantPromptSlot) {
        val currentTarget = target ?: return
        onPromptSelected(TeacherPromptChoice(currentTarget, prompt))
    }

    private fun handleBack() {
        when (mode) {
            Mode.COMPACT -> dialog.dismiss()
            Mode.LIBRARY -> showCompact()
            Mode.DETAIL -> showLibrary()
            Mode.SEND -> if (!sendInProgress) showDetail()
        }
    }

    private fun selectedResource(): TeacherGptResource? =
        resources.firstOrNull { it.resourceId == selectedResourceId }

    private fun selectedRevision(): TeacherGptResourceRevision? {
        val resource = selectedResource() ?: return null
        return resource.revisions.firstOrNull { it.revisionId == selectedRevisionId }
            ?: resource.currentRevision
    }

    private fun releaseFormattedAnswer() {
        formattedAnswerView?.destroyRenderer()
        formattedAnswerView = null
    }

    private fun canonicalPrompts(slots: List<AssistantPromptSlot>): List<AssistantPromptSlot> {
        val sorted = slots.sortedBy { it.slotNumber }
        require(sorted.size == EXACT_PROMPT_COUNT) { "Exactly four assistant prompt slots are required" }
        require(sorted.map { it.slotNumber } == (1..EXACT_PROMPT_COUNT).toList()) {
            "Assistant prompt slots must be numbered 1 through 4"
        }
        return sorted.toList()
    }

    private fun compactPromptLabel(prompt: AssistantPromptSlot): String =
        compactPromptLabel(prompt.slotNumber, prompt.title)

    private fun panelRoot(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
    }

    private fun label(
        context: Context,
        textSizeSp: Float,
        colour: Int = Color.rgb(45, 44, 40),
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        textSize = textSizeSp
        setTextColor(colour)
        if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
    }

    private fun compactBarBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(3).toFloat()
        setColor(Color.argb(242, 249, 248, 243))
        setStroke(dp(1), Color.rgb(216, 214, 205))
    }

    private fun panelBackground(): GradientDrawable = roundedBackground(Color.rgb(253, 252, 247), 18)
    private fun panelHeaderBackground(): GradientDrawable = roundedBackground(Color.rgb(248, 247, 241), 18)
    private fun panelFooterBackground(): GradientDrawable = roundedBackground(Color.rgb(248, 247, 241), 12)
    private fun rowBackground(): GradientDrawable = roundedBackground(Color.rgb(248, 247, 241), 10)

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun fixedWidth(width: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun matchWrap(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private enum class Mode { COMPACT, LIBRARY, DETAIL, SEND }

    private companion object {
        const val EXACT_PROMPT_COUNT = 4
        const val MINIMUM_TOUCH_DP = 48
        const val COMPACT_TOUCH_HEIGHT_DP = 48
        const val COMPACT_VISIBLE_HEIGHT_DP = 30
        const val TABLET_MIN_DP = 600
        const val TABLET_PANEL_WIDTH_DP = 420
        const val TABLET_DETAIL_WIDTH_DP = 500
        const val PHONE_LIBRARY_MAX_HEIGHT_DP = 420
        const val MAX_TITLE_CHARS = 200
        const val MAX_EDITABLE_EXCERPT_CHARS = 16_000
        const val RESOURCE_PREVIEW_CHARS = 120

        val resourceOrder = compareByDescending<TeacherGptResource> { resource ->
            resource.revisions.maxOfOrNull { it.createdAtEpochMillis } ?: resource.createdAtEpochMillis
        }.thenBy { it.resourceId }
    }
}

internal fun compactPromptLabel(slotNumber: Int, title: String): String {
    val normalized = title.trim()
    return when {
        normalized.contains("깨달음") -> "깨달음"
        normalized.contains("오개념") || normalized.contains("개념") -> "개념"
        normalized.contains("직접 풀이") || slotNumber == 3 -> "풀이"
        normalized.contains("전략") || normalized.contains("확장") -> "확장"
        else -> normalized.split(Regex("\\s+")).firstOrNull().orEmpty().take(4).ifBlank {
            when (slotNumber) {
                1 -> "질문"
                2 -> "개념"
                3 -> "풀이"
                else -> "확장"
            }
        }
    }
}

internal fun superscript(value: Int): String = value.toString().map { character ->
    when (character) {
        '0' -> '⁰'
        '1' -> '¹'
        '2' -> '²'
        '3' -> '³'
        '4' -> '⁴'
        '5' -> '⁵'
        '6' -> '⁶'
        '7' -> '⁷'
        '8' -> '⁸'
        '9' -> '⁹'
        else -> character
    }
}.joinToString("")

private fun superscriptCount(count: Int): String = if (count > 99) "⁹⁹⁺" else superscript(count)

internal fun assistantPreviewText(
    text: String,
    maxChars: Int,
    format: TeacherGptAnswerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
): String = assistantStudentText(text, format).replace(Regex("\\s+"), " ").trim()
    .let { validUtf16Prefix(it, maxChars) }

/** Plain student cards intentionally stay protocol-compatible while teacher answers use rich view. */
internal fun assistantStudentText(
    markdown: String,
    format: TeacherGptAnswerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
): String {
    if (format == TeacherGptAnswerFormat.PLAIN_TEXT) {
        return FormattedAssistantAnswerDocument.normalizeForDisplay(markdown).trim()
    }
    var value = markdown.replace("\r\n", "\n").replace('\r', '\n')
    val escapedPunctuation = listOf(
        '\\', '`', '*', '_', '[', ']', '~', '$', '#', '>', '-', '+', '.', ')',
    )
    val protectedEscapes = escapedPunctuation.mapIndexed { index, character ->
        character to (0xE000 + index).toChar().toString()
    }
    protectedEscapes.forEach { (character, placeholder) ->
        value = value.replace("\\$character", placeholder)
    }
    value = value.replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
    value = value.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
    value = value.replace(Regex("(?m)^\\s*```[^\\n]*\\n?"), "")
    value = value.replace("```", "")
    value = value.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
    value = value.replace(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)"), "$1")
    value = value.replace("**", "").replace("__", "").replace("`", "")
    value = value.replace("\\[", "").replace("\\]", "")
        .replace("\\(", "").replace("\\)", "")
        .replace("$$", "").replace("$", "")
    repeat(3) {
        value = value.replace(Regex("\\\\frac\\{([^{}]+)}\\{([^{}]+)}"), "($1)/($2)")
    }
    val replacements = mapOf(
        "\\times" to "×",
        "\\cdot" to "·",
        "\\leq" to "≤",
        "\\le" to "≤",
        "\\geq" to "≥",
        "\\ge" to "≥",
        "\\neq" to "≠",
        "\\pm" to "±",
        "\\sqrt" to "√",
    )
    replacements.forEach { (from, to) -> value = value.replace(from, to) }
    value = value.replace(Regex("\\\\[A-Za-z]+"), "")
        .replace("{", "")
        .replace("}", "")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
    protectedEscapes.forEach { (character, placeholder) ->
        value = value.replace(placeholder, character.toString())
    }
    return value.trim()
}
