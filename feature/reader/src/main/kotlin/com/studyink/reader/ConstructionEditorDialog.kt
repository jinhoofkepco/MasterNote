package com.studyink.reader

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintSolver
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.DragTarget
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryLineStyle
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.MeasurementType
import com.studyink.construction.core.SceneValidator
import com.studyink.construction.core.SolveResult
import com.studyink.construction.storage.ConstructionSceneSnapshot
import com.studyink.construction.storage.ConstructionSceneStore
import com.studyink.construction.storage.ConstructionSceneAccess
import com.studyink.construction.storage.ConstructionConflictChoice
import com.studyink.construction.storage.ConstructionReplicaChangeBus
import com.studyink.construction.storage.ConstructionReplicaChangeKind
import com.studyink.construction.storage.ConstructionReplicaRole
import com.studyink.construction.storage.ConstructionUiBridge
import com.studyink.construction.storage.ConstructionTarget
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Local mathematical memo editor. All hard solving and durable writes run on one worker.
 * A complete drag is one persisted/undoable command; stale preview results cannot cross targets.
 */
internal class ConstructionEditorView(
    context: Context,
    private val target: ConstructionTarget,
    private val titleText: String,
    private val embedded: Boolean = false,
    private val store: ConstructionSceneAccess = ConstructionSceneStore(File(context.applicationContext.filesDir, "masternote")),
    private val replicaRole: ConstructionReplicaRole? = null,
    private val syncBridge: ConstructionUiBridge? = null,
) : FrameLayout(context) {
    var onRequestClose: () -> Unit = {}
    var onDurableChanged: () -> Unit = {}
    var onLoaded: (ConstructionSceneSnapshot) -> Unit = {}
    var onUndoStateChanged: () -> Unit = {}
    var onEditingRequested: () -> Unit = {}
    private var sharedMemoHost: SharedMemoCanvasHost? = null
    private var memoGeometryActive = true
    val hasPendingWork: Boolean get() = (busy && !loadFailed) || dragSolving || dragBase != null || measurementBase != null
    val canUndo: Boolean get() = !hasPendingWork && !closed && undo.isNotEmpty()
    val canRedo: Boolean get() = !hasPendingWork && !closed && redo.isNotEmpty()
    private val solver = ConstraintSolver()
    // Storage/replica results belong to the controller, even while its canvas is being reparented.
    private val uiHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "construction-editor").apply { isDaemon = true } }
    private val density = context.resources.displayMetrics.density
    private val canvas = ConstructionCanvasView(context)
    private val status = TextView(context)
    private val selectionInfo = TextView(context)
    private val viewport = FrameLayout(context)
    private val panel = LinearLayout(context)
    private val panelBody = LinearLayout(context)
    private val panelScroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = minOf(dp(260), (viewport.height * .44f).toInt()).coerceAtLeast(dp(70))
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
        }
    }
    private val panelTitle = TextView(context)
    private val toolButtons = mutableMapOf<ConstructionTool, Button>()
    private val panelButtons = mutableMapOf<PanelKind, Button>()
    private val colorButtons = mutableMapOf<Int, Button>()
    private val lineStyleButtons = mutableMapOf<GeometryLineStyle, Button>()
    private var panelKind: PanelKind? = null
    private var detailSelection: Set<String> = emptySet()
    private var toolHint = "선택 · 대상을 눌러 주세요"
    private var newColor = Color.rgb(44, 59, 72)
    private var newLineStyle = GeometryLineStyle.SOLID
    private var selectedCondition: String? = null
    private var panelAnchorConstraint: String? = null
    private var panelRevision = 0L
    private val conditionSteps = mutableMapOf<String, Double>()
    private var measurementBase: ConstructionScene? = null
    private enum class PanelKind { RELATIONS, MEASURE, CONDITIONS, MORE, DETAIL }
    private val actionButtons = mutableListOf<Button>()
    private var closeButton: Button? = null
    private var snapshot: ConstructionSceneSnapshot? = null
    private var scene = ConstructionScene()
    private val undo = ArrayDeque<ConstructionScene>()
    private val redo = ArrayDeque<ConstructionScene>()
    private var restoreListener: AutoCloseable? = null
    private var replicaListener: AutoCloseable? = null
    private var syncListener: AutoCloseable? = null
    private var publishButton: Button? = null
    private val syncStatus = TextView(context)
    private var busy = true
    private var loadFailed = false
    private var generation = 0L
    private var closed = false
    private var dragBase: ConstructionScene? = null
    private var pendingDrag: Pair<DragTarget, Boolean>? = null
    private var dragSolving = false
    private var dragRequest = 0L
    private var fitAfterCommit = false
    private val childDialogs = mutableSetOf<AlertDialog>()

    private fun isCurrent(token: Long) = token == generation && !closed && !busy && dragBase == null && measurementBase == null
    private fun showChild(dialog: AlertDialog): AlertDialog {
        childDialogs += dialog
        dialog.setOnDismissListener { childDialogs -= dialog }
        dialog.show()
        return dialog
    }
    private fun AlertDialog.Builder.showChild() = showChild(create())
    private fun dismissChildren() { childDialogs.toList().forEach { it.dismiss() }; childDialogs.clear(); closePanel() }

    init {
        clipChildren = true
        clipToPadding = true
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = !embedded
            setBackgroundColor(Color.rgb(255, 254, 249))
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(4), 0) }
        header.addView(TextView(context).apply {
            text = if (embedded) "도형 작도판" else "작도 메모 · $titleText"
            textSize = 13f; setTextColor(Color.rgb(33, 47, 66)); setSingleLine(); ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        header.addView(button("되돌리기", icon = ConstructionIcon.UNDO, iconOnly = true) { history(true) })
        header.addView(button("다시", icon = ConstructionIcon.REDO, iconOnly = true) { history(false) })
        if (embedded && replicaRole == ConstructionReplicaRole.TEACHER) {
            publishButton = button("발행", register = false) { requestPublication() }
            header.addView(publishButton)
        }
        closeButton = button("닫기", register = false, icon = ConstructionIcon.CLOSE, iconOnly = true) { requestClose() }
        if (embedded) closeButton?.visibility = View.GONE
        header.addView(closeButton)
        if (!embedded) root.addView(header)
        root.addView(toolbar {
            listOf(Triple(ConstructionTool.SELECT,"선택",ConstructionIcon.SELECT), Triple(ConstructionTool.POINT,"점",ConstructionIcon.POINT),
                Triple(ConstructionTool.SEGMENT,"선분",ConstructionIcon.SEGMENT), Triple(ConstructionTool.CIRCLE,"원",ConstructionIcon.CIRCLE)).forEach { (tool, label, icon) ->
                val b = button(label, icon = icon, iconOnly = true) { chooseTool(tool) }
                toolButtons[tool] = b; addView(b)
            }
            addView(TextView(context).apply { text = "│"; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(15), dp(36)))
            for ((color, label) in palette()) {
                val b = button(label, register = false) { applyColor(color) }.apply {
                    text = "●"; textSize = 18f; setTextColor(color); contentDescription = "도형 색 $label"
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                }
                colorButtons[color] = b; addView(b)
            }
            GeometryLineStyle.entries.forEach { style ->
                val b = button(style.koreanName(), icon = style.icon(), iconOnly = true) { applyLineStyle(style) }
                lineStyleButtons[style] = b; addView(b)
            }
            addView(button("자동 연결", icon = ConstructionIcon.MAGNET, iconOnly = true) {
                canvas.snapEnabled = !canvas.snapEnabled; updateToolbar()
                notice(if (canvas.snapEnabled) "끝점·직선 위·교점 자동 연결 켜짐" else "자동 연결 꺼짐 · 자유롭게 점을 만듭니다")
            }.apply { tag = "snap-toggle" })
        })
        root.addView(toolbar {
            fun action(kind: PanelKind, label: String, icon: ConstructionIcon) {
                val b = button(label, icon = icon) { togglePanel(kind) }; panelButtons[kind] = b; addView(b)
            }
            action(PanelKind.RELATIONS, "조건 추가", ConstructionIcon.CONSTRAINT)
            action(PanelKind.MEASURE, "측정", ConstructionIcon.MEASURE)
            action(PanelKind.CONDITIONS, "조건 목록", ConstructionIcon.LIST)
            addView(button("맞춤", icon = ConstructionIcon.FIT, iconOnly = true) { canvas.fitScene() })
            addView(button("더보기", icon = ConstructionIcon.MORE, iconOnly = true) { togglePanel(PanelKind.MORE) })
            if (embedded) {
                addView(button("되돌리기", icon = ConstructionIcon.UNDO, iconOnly = true) { onEditingRequested(); history(true) })
                addView(button("다시", icon = ConstructionIcon.REDO, iconOnly = true) { onEditingRequested(); history(false) })
                publishButton?.let { header.removeView(it); addView(it) }
            }
        })
        viewport.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        selectionInfo.apply { textSize = 11f; setTextColor(Color.rgb(40, 88, 82)); setPadding(dp(8), dp(3), dp(8), dp(3)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; background = surface(0xf0fffef9.toInt()); elevation = dp(2).toFloat(); contentDescription = "현재 작도 동작" }
        viewport.addView(selectionInfo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(8); rightMargin = dp(8); topMargin = dp(6) })
        status.apply { textSize = 10f; setTextColor(Color.rgb(64, 77, 70)); setPadding(dp(7), dp(3), dp(7), dp(3)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; background = surface(0xeafffef9.toInt()); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        viewport.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(5) })
        if (embedded) {
            syncStatus.apply {
                textSize = 10f; setTextColor(Color.rgb(66, 85, 106)); maxLines = 2
                setPadding(dp(7), dp(3), dp(7), dp(3)); background = surface(0xeafffef9.toInt())
                contentDescription = "도형 동기화 상태"
                setOnClickListener { showConflictChoices() }
            }
            viewport.addView(syncStatus, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(42); rightMargin = dp(8); leftMargin = dp(8)
            })
        }
        configurePanel()
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(root, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        canvas.onSelectionChanged = { updateSelection() }
        canvas.onToolHintChanged = { toolHint = it; updateHint() }
        canvas.onPoint = { p -> runCatching { ConstructionEdits.addPoint(scene, p, newColor) }.onSuccess { edit(it) }.onFailure { notice(it.message.orEmpty()) } }
        canvas.onSegment = { a, b -> runCatching { ConstructionEdits.addSegment(scene, a, b, newColor, newLineStyle) }.onSuccess { edit(it) }.onFailure { notice(it.message.orEmpty()) } }
        canvas.onCircle = { center, radius -> runCatching { ConstructionEdits.addCircle(scene, center, radius, newColor, newLineStyle) }.onSuccess { edit(it) }.onFailure { notice(it.message.orEmpty()) } }
        canvas.onDragPoint =(::onDrag)
        canvas.onMeasurementSelected = { id -> canvas.selectedMeasurementId = id; showMeasurementDetails(id) }
        canvas.onMeasurementDrag = ::onMeasurementDrag
        canvas.onConstraintSelected = { id -> panelAnchorConstraint = id; showConditionDetails(id) }
        canvas.onAnnotationLayoutChanged = { positionConditionPanel() }
        restoreListener = store.addRestoreListener {
            uiHandler.post {
                if (!closed) {
                    generation++; dragBase = null; measurementBase = null; pendingDrag = null; dragSolving = false
                    undo.clear(); redo.clear(); canvas.cancelDrag(); load()
                }
            }
        }
        if (embedded) {
            replicaListener = ConstructionReplicaChangeBus.addListener { change ->
                if (change.target == target && change.role == replicaRole &&
                    change.kind in setOf(ConstructionReplicaChangeKind.REMOTE_STUDENT,
                        ConstructionReplicaChangeKind.REMOTE_PUBLISH, ConstructionReplicaChangeKind.ADOPTED_STUDENT,
                        ConstructionReplicaChangeKind.PUBLISH_RESULT,
                        ConstructionReplicaChangeKind.DELETED)) uiHandler.post {
                    // Shadow/ACK metadata can advance while the teacher is drawing a new draft.
                    // The access adapter safely rebases identical scene bytes without discarding
                    // that in-progress gesture or its undo history.
                    if (!closed && (change.snapshot.deleted || change.snapshot.scene != snapshot?.scene)) reloadAfterRemoteChange()
                }
            }
            syncListener = syncBridge?.addListener(target) { uiHandler.post { if (!closed) refreshSyncState() } }
        }
        updateToolbar(); updateHint()
        refreshSyncState()
        onUndoStateChanged()
        load()
    }

    private fun dp(value: Int) = (value * density).toInt()
    private fun button(label: String, register: Boolean = true, icon: ConstructionIcon? = null, iconOnly: Boolean = false, action: () -> Unit): Button =
        constructionButton(context, label, icon, iconOnly) { if (!busy && dragBase == null && measurementBase == null && !closed) action() else notice("계산 또는 저장을 마친 뒤 다시 눌러 주세요.") }
            .also { if (register) actionButtons += it }
    private fun toolbar(build: LinearLayout.() -> Unit) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        setPadding(dp(5), 0, dp(5), dp(2))
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; build() })
    }
    private fun surface(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(9).toFloat()
        setStroke(dp(1).coerceAtLeast(1), Color.argb(28, 65, 88, 73))
    }
    private fun palette() = listOf(Color.rgb(44,59,72) to "먹색", Color.rgb(53,113,176) to "파랑", Color.rgb(194,91,64) to "주황")

    private fun applyColor(color: Int) {
        onEditingRequested()
        newColor = color
        presentationEdit(ConstructionEdits.setColor(scene, canvas.selectedIds, color))
        updateToolbar()
    }

    private fun applyLineStyle(style: GeometryLineStyle) {
        onEditingRequested()
        newLineStyle = style
        presentationEdit(ConstructionEdits.setLineStyle(scene, canvas.selectedIds, style))
        updateToolbar()
    }

    /** This panel is a sibling of the full-size canvas, never a row above it. */
    private fun configurePanel() {
        panel.orientation = LinearLayout.VERTICAL; panel.visibility = View.GONE
        panel.tag = "construction-overlay"; panel.elevation = dp(7).toFloat()
        panel.background = surface(0xfafffef9.toInt()); panel.setPadding(dp(5), dp(2), dp(5), dp(5))
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        panelTitle.apply { textSize = 12f; setTextColor(Color.rgb(51,69,59)); setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(dp(5), 0, 0, 0); contentDescription = "작은 메뉴 제목 · 끌어서 이동" }
        heading.addView(panelTitle, LinearLayout.LayoutParams(0, dp(32), 1f))
        heading.addView(constructionButton(context, "메뉴 닫기", ConstructionIcon.CLOSE, true) { closePanel() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        })
        panel.addView(heading)
        panelBody.orientation = LinearLayout.VERTICAL
        panelScroll.isFillViewport = false; panelScroll.addView(panelBody)
        panel.addView(panelScroll, LinearLayout.LayoutParams(-1, -2))
        viewport.addView(panel, FrameLayout.LayoutParams(dp(310), -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(8); topMargin = dp(36) })
        var startX = 0f; var startY = 0f; var initialX = 0f; var initialY = 0f
        panelTitle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { panelAnchorConstraint = null; startX = event.rawX; startY = event.rawY; initialX = panel.translationX; initialY = panel.translationY; true }
                MotionEvent.ACTION_MOVE -> { panel.translationX = initialX + event.rawX - startX; panel.translationY = initialY + event.rawY - startY; clampPanel(); true }
                MotionEvent.ACTION_UP -> { panelTitle.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        viewport.addOnLayoutChangeListener { _, l,t,r,b, oldL,oldT,oldR,oldB ->
            if (r-l != oldR-oldL || b-t != oldB-oldT) {
                val width = minOf(dp(if (panelAnchorConstraint != null) 256 else 310), (r-l-dp(16)).coerceAtLeast(dp(120)))
                panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
                panelScroll.requestLayout(); panel.post { clampPanel() }
            }
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (panelAnchorConstraint != null) positionConditionPanel() else clampPanel()
        }
    }
    /** Place the existing overlay beside the dimension. Neither canvas layer is ever resized. */
    private fun positionConditionPanel() {
        if (panelKind != PanelKind.DETAIL || panel.visibility != View.VISIBLE || panel.width == 0) return
        val id = panelAnchorConstraint ?: return
        val bounds = canvas.constraintScreenBounds(id) ?: return
        val gap = dp(8).toFloat()
        val margin = dp(4).toFloat()
        val right = bounds.right + gap
        val left = bounds.left - gap - panel.width
        val x: Float
        val y: Float
        when {
            right + panel.width <= viewport.width - margin -> { x = right; y = bounds.centerY() - panel.height / 2f }
            left >= margin -> { x = left; y = bounds.centerY() - panel.height / 2f }
            bounds.bottom + gap + panel.height <= viewport.height - margin -> { x = bounds.centerX() - panel.width / 2f; y = bounds.bottom + gap }
            else -> { x = bounds.centerX() - panel.width / 2f; y = bounds.top - gap - panel.height }
        }
        panel.translationX = x - panel.left
        panel.translationY = y - panel.top
        clampPanel()
    }
    private fun clampPanel() {
        panel.translationX = panel.translationX.coerceIn(-panel.left.toFloat() + dp(4), maxOf(-panel.left.toFloat() + dp(4), (viewport.width-panel.right-dp(4)).toFloat()))
        panel.translationY = panel.translationY.coerceIn(-panel.top.toFloat() + dp(4), maxOf(-panel.top.toFloat() + dp(4), (viewport.height-panel.bottom-dp(4)).toFloat()))
    }
    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(panel.windowToken, 0)
        panel.findFocus()?.clearFocus()
    }
    private fun closePanel() {
        hideKeyboard(); panelKind = null; panel.visibility = View.GONE
        panelRevision++
        panelAnchorConstraint = null
        panelBody.removeAllViews(); selectedCondition = null; canvas.selectedConstraintId = null
        canvas.selectedMeasurementId = null; updateToolbar()
    }
    private fun showPanel(title: String, kind: PanelKind, build: LinearLayout.() -> Unit) {
        val retainedScroll = if (kind == PanelKind.CONDITIONS && panelKind == kind) panelScroll.scrollY else 0
        val revision = ++panelRevision
        if (kind != PanelKind.DETAIL) panelAnchorConstraint = null
        hideKeyboard(); panelKind = kind; panelTitle.text = title
        val desiredWidth = dp(if (panelAnchorConstraint != null) 256 else 310)
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = minOf(desiredWidth, (viewport.width - dp(16)).coerceAtLeast(dp(120)))
        }
        detailSelection = canvas.selectedIds.toSet(); panelBody.removeAllViews(); panelBody.build()
        panelScroll.scrollTo(0, retainedScroll); panel.visibility = View.VISIBLE; panel.bringToFront(); updateToolbar()
        if (retainedScroll > 0) panelBody.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                v.removeOnLayoutChangeListener(this)
                if (panelRevision == revision && panelKind == kind) panelScroll.scrollTo(0, retainedScroll)
            }
        })
    }
    private fun LinearLayout.info(text: String) {
        addView(TextView(context).apply { this.text = text; textSize = 11f; setTextColor(Color.rgb(80,91,83)); setPadding(dp(7), dp(4), dp(7), dp(5)) }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun LinearLayout.action(label: String, icon: ConstructionIcon? = null, apply: () -> Unit) {
        val token = generation
        addView(button(label, register = false, icon = icon) { if (isCurrent(token)) apply() }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START; layoutParams = LinearLayout.LayoutParams(-1, dp(36))
        })
    }
    private fun togglePanel(kind: PanelKind) {
        onEditingRequested()
        if (panelKind == kind) return closePanel()
        if (canvas.tool != ConstructionTool.SELECT) canvas.tool = ConstructionTool.SELECT
        selectedCondition = null; canvas.selectedConstraintId = null; canvas.selectedMeasurementId = null
        panelKind = kind; refreshPanel(); updateHint(); updateToolbar()
    }
    private fun refreshPanel() {
        when (panelKind) {
            PanelKind.RELATIONS -> showRelationMenu()
            PanelKind.MEASURE -> showMeasurement()
            PanelKind.CONDITIONS -> showConditions()
            PanelKind.MORE -> showMore()
            PanelKind.DETAIL -> if (selectedCondition != null) showConditionDetails(selectedCondition!!) else canvas.selectedMeasurementId?.let(::showMeasurementDetails)
            null -> Unit
        }
    }
    private fun updateToolbar() {
        toolButtons.forEach { (tool, b) -> b.isSelected = memoGeometryActive && canvas.tool == tool }
        panelButtons.forEach { (kind,b) -> b.isSelected = panelKind == kind }
        val colors = canvas.selectedIds.mapNotNull { id ->
            scene.point(id)?.let { it.colorArgb ?: Color.rgb(44,59,72) }
                ?: scene.segment(id)?.let { it.colorArgb ?: Color.rgb(44,59,72) }
                ?: scene.circle(id)?.let { it.colorArgb ?: Color.rgb(57,123,112) }
        }.distinct()
        val shownColor = if (canvas.selectedIds.isEmpty()) newColor else colors.singleOrNull()
        colorButtons.forEach { (color,b) -> b.isSelected = color == shownColor; b.setTextColor(color); b.background = if (color == shownColor) surface(Color.rgb(230,235,229)) else surface(Color.TRANSPARENT); b.isEnabled = !busy && dragBase == null && measurementBase == null }
        val styles = (selectedSegments().map { it.lineStyle } + selectedCircles().map { it.lineStyle }).distinct()
        val shownStyle = if (styles.isEmpty()) newLineStyle else styles.singleOrNull()
        lineStyleButtons.forEach { (style,b) -> b.isSelected = style == shownStyle }
        actionButtons.firstOrNull { it.tag == "snap-toggle" }?.isSelected = canvas.snapEnabled
        actionButtons.firstOrNull { it.tag == "더보기" }?.isSelected = panelKind == PanelKind.MORE
    }
    private fun updateHint() {
        selectionInfo.text = if (canvas.tool == ConstructionTool.SELECT) {
            if (canvas.selectedIds.isEmpty()) "선택 · 대상을 눌러 주세요" else "선택 · ${canvas.selectedIds.joinToString { name(it) }}"
        } else toolHint
    }
    private fun showMore() = showPanel("더보기", PanelKind.MORE) {
        if (canvas.selectedIds.isNotEmpty()) {
            info("선택한 도형 색 바꾸기")
            addView(LinearLayout(context).apply {
                palette().forEach { (color,label) -> addView(button(label, register = false) { presentationEdit(ConstructionEdits.setColor(scene, canvas.selectedIds, color)) }.apply { text = "● $label"; setTextColor(color) }) }
            })
        }
        action("이름") { renamePoint() }
        action("삭제", ConstructionIcon.DELETE) { deleteSelection() }
        addView(LinearLayout(context).apply {
            addView(button("확대 +", register = false) { canvas.zoom(1.25f) })
            addView(button("축소 −", register = false) { canvas.zoom(.8f) })
        })
        action("예제") { showExamples() }
        action("도움") { showHelp() }
    }
    private fun notice(message: String) { status.text = message; Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    private fun setBusy(value: Boolean) {
        busy = value
        actionButtons.forEach { it.isEnabled = !value && dragBase == null && measurementBase == null }
        closeButton?.isEnabled = !value
        canvas.editable = !value
        fun enableControls(view: View) {
            if (view is Button || view is EditText) view.isEnabled = !value && dragBase == null && measurementBase == null
            if (view is ViewGroup) for (i in 0 until view.childCount) enableControls(view.getChildAt(i))
        }
        enableControls(panelBody)
        updateToolbar()
        refreshSyncState()
        onUndoStateChanged()
    }
    private fun chooseTool(tool: ConstructionTool) {
        onEditingRequested()
        canvas.tool = tool; canvas.clearSelection()
        closePanel(); updateToolbar(); updateHint()
    }
    private fun load(fit: Boolean = true) {
        dismissChildren()
        loadFailed = false
        canvas.tool = canvas.tool // Discard an unfinished two-tap construction from the old scene.
        val token = ++generation
        setBusy(true); status.text = "작도 메모 불러오는 중…"
        worker.execute {
            val result = runCatching { store.load(target) }
            uiHandler.post {
                if (closed || generation != token) return@post
                result.onSuccess { loaded ->
                    snapshot = loaded; scene = loaded.scene; canvas.scene = scene
                    canvas.clearSelection(); if (fit && sharedMemoHost == null) canvas.fitScene(); setBusy(false)
                    onLoaded(loaded)
                    status.text = "선분·원을 그린 뒤 대상을 선택해 조건을 추가하세요. 예제로 시작할 수도 있습니다."
                }.onFailure {
                    loadFailed = true
                    status.text = "저장된 작도를 읽지 못했습니다. 기존 파일은 보존됩니다: ${it.message}"
                    closeButton?.isEnabled = true
                    closeButton?.setOnClickListener { onRequestClose() }
                }
            }
        }
    }

    private fun edit(candidate: ConstructionScene) {
        if (busy || dragBase != null || measurementBase != null || closed || candidate == scene) return
        val token = ++generation; setBusy(true); status.text = "조건 확인 중…"
        worker.execute {
            val result = runCatching { solver.solve(candidate) }
            uiHandler.post {
                if (closed || generation != token) return@post
                result.onSuccess { solved ->
                    if (solved.success) persist(solved.scene, solved = solved)
                    else { fitAfterCommit = false; setBusy(false); refreshPanel(); showSolveFailure(solved) }
                }.onFailure { fitAfterCommit = false; setBusy(false); refreshPanel(); notice("계산하지 못했습니다: ${it.message}") }
            }
        }
    }
    private fun presentationEdit(candidate: ConstructionScene) {
        if (busy || dragBase != null || measurementBase != null || closed || candidate == scene) return
        generation++; persist(candidate)
    }

    private fun onMeasurementDrag(id: String, offsetX: Double, offsetY: Double, phase: ConstructionDragPhase) {
        if (closed || busy || dragBase != null) return
        when (phase) {
            ConstructionDragPhase.START -> {
                if (measurementBase != null || scene.measurements.none { it.id == id }) return
                if (panelKind == PanelKind.DETAIL) closePanel()
                measurementBase = scene
                actionButtons.forEach { it.isEnabled = false }; updateToolbar()
            }
            ConstructionDragPhase.MOVE, ConstructionDragPhase.END -> {
                val before = measurementBase ?: return
                if (offsetX.isFinite() && offsetY.isFinite()) {
                    scene = before.copy(measurements = before.measurements.map {
                        if (it.id == id) it.copy(offsetX = offsetX.coerceIn(-1e6, 1e6), offsetY = offsetY.coerceIn(-1e6, 1e6)) else it
                    })
                    canvas.scene = scene
                }
                if (phase == ConstructionDragPhase.END) {
                    measurementBase = null
                    if (scene != before) { generation++; persist(scene) } else setBusy(false)
                }
            }
            ConstructionDragPhase.CANCEL -> {
                scene = measurementBase ?: return; measurementBase = null; canvas.scene = scene; setBusy(false)
            }
        }
    }

    private fun persist(next: ConstructionScene, historyDirection: Int = 0, solved: SolveResult? = null) {
        val expected = snapshot ?: return
        val before = expected.scene
        val token = generation
        setBusy(true); status.text = "저장 중…"
        worker.execute {
            val result = runCatching { store.save(expected, next) }
            uiHandler.post {
                if (closed || generation != token) return@post
                result.onSuccess { committed ->
                    when (historyDirection) {
                        -1 -> { undo.removeLastOrNull(); redo.addLast(before) }
                        1 -> { redo.removeLastOrNull(); undo.addLast(before) }
                        else -> if (before != next) { undo.addLast(before); redo.clear() }
                    }
                    while (undo.size > 80) undo.removeFirst()
                    snapshot = committed; scene = committed.scene; canvas.scene = scene
                    onDurableChanged()
                    if (fitAfterCommit) { fitAfterCommit = false; if (sharedMemoHost == null) canvas.fitScene() }
                    canvas.selectedIds = canvas.selectedIds.intersect(allIds())
                    setBusy(false); updateSelection()
                    status.text = if (solved != null) {
                        "저장됨 · ${if (solved.degreesOfFreedom == 0) "현재 조건에서 모양이 정해졌습니다. 치수를 바꾸어 탐구하세요." else "움직일 여지 ${solved.degreesOfFreedom}개"}"
                    } else "저장됨 · 조건 ${scene.constraints.count { it.enabled }}개"
                }.onFailure {
                    fitAfterCommit = false
                    if (it is java.util.ConcurrentModificationException) {
                        undo.clear(); redo.clear(); canvas.clearSelection()
                        notice("다른 화면이나 복원에서 작도가 바뀌어 최신 저장본을 다시 불러옵니다.")
                        load()
                    } else {
                        scene = expected.scene; canvas.scene = scene; setBusy(false); updateSelection()
                        notice("저장하지 못해 이전 상태를 유지했습니다: ${it.message}")
                    }
                }
            }
        }
    }
    private fun allIds() = scene.points.map { it.id }.toSet() + scene.segments.map { it.id } + scene.circles.map { it.id }
    private fun showSolveFailure(result: SolveResult) {
        val labels = scene.constraints.filter { it.id in result.conflictingConstraintIds }.joinToString { it.type.koreanName() }
        notice("${result.message}${if (labels.isNotEmpty()) " · $labels" else ""} (이전 도형 유지)")
    }

    private fun onDrag(id: String, x: Double, y: Double, phase: ConstructionDragPhase) {
        if (closed || busy) return
        when (phase) {
            ConstructionDragPhase.START -> {
                if (dragBase != null) return
                if (panelKind == PanelKind.DETAIL) closePanel()
                dragBase = scene; pendingDrag = null; dragRequest++
                actionButtons.forEach { it.isEnabled = false }
            }
            ConstructionDragPhase.MOVE, ConstructionDragPhase.END -> {
                if (dragBase == null) return
                if (phase == ConstructionDragPhase.END) canvas.editable = false
                pendingDrag = DragTarget(id, x, y) to (phase == ConstructionDragPhase.END)
                runPendingDrag()
            }
            ConstructionDragPhase.CANCEL -> {
                val before = dragBase ?: return
                dragRequest++; pendingDrag = null; dragBase = null; dragSolving = false
                scene = before; canvas.scene = scene; setBusy(false); status.text = "이동 취소"
            }
        }
    }

    private fun runPendingDrag() {
        if (dragSolving || closed || dragBase == null) return
        val request = pendingDrag ?: return
        pendingDrag = null; dragSolving = true
        val gesture = dragRequest; val token = generation; val base = scene
        worker.execute {
            val result = runCatching { solver.solve(base, request.first) }
            uiHandler.post {
                if (closed || generation != token || gesture != dragRequest || dragBase == null) return@post
                dragSolving = false
                result.onSuccess { solved ->
                    if (solved.success) { scene = solved.scene; canvas.scene = scene; updateSelection() }
                    status.text = when {
                        !solved.success -> "조건을 유지할 수 있는 위치까지 이동합니다. ${solved.message}"
                        solved.dragLimited -> "조건이 허용하는 위치까지 이동했습니다."
                        else -> "조건을 유지하며 이동 중 · 손을 떼면 저장"
                    }
                }.onFailure { status.text = "계산하지 못해 마지막 정상 위치를 유지합니다." }
                if (request.second) {
                    val before = dragBase!!; dragBase = null; pendingDrag = null
                    if (scene != before) { generation++; persist(scene, solved = result.getOrNull()) } else { setBusy(false); refreshPanel(); status.text = "조건 때문에 더 이동할 수 없습니다. 조건 목록에서 값을 바꿀 수 있습니다." }
                } else runPendingDrag()
            }
        }
    }

    private fun history(backward: Boolean) {
        val next = if (backward) undo.lastOrNull() else redo.lastOrNull()
        if (next == null) return notice(if (backward) "되돌릴 작업이 없습니다." else "다시 실행할 작업이 없습니다.")
        generation++; dismissChildren()
        persist(next, historyDirection = if (backward) -1 else 1)
    }
    private fun selectedPoints() = canvas.selectedIds.mapNotNull { id -> scene.points.firstOrNull { it.id == id } }
    private fun selectedSegments() = canvas.selectedIds.mapNotNull { id -> scene.segments.firstOrNull { it.id == id } }
    private fun selectedCircles() = canvas.selectedIds.mapNotNull { id -> scene.circles.firstOrNull { it.id == id } }
    private fun name(id: String): String = scene.points.firstOrNull { it.id == id }?.label?.ifBlank { "점" }
        ?: scene.segments.firstOrNull { it.id == id }?.let { "${name(it.startPointId)}${name(it.endPointId)}" }
        ?: scene.circles.firstOrNull { it.id == id }?.let { "원(${name(it.centerPointId)})" } ?: "대상"
    private fun updateSelection() {
        updateHint(); updateToolbar()
        if (panelKind == PanelKind.DETAIL && detailSelection != canvas.selectedIds) closePanel()
        if (dragBase == null && measurementBase == null) refreshPanel()
    }

    private data class RelationAction(val label: String, val run: () -> Unit)
    private fun showRelationMenu() {
        val token = generation
        val points = selectedPoints(); val lines = selectedSegments(); val circles = selectedCircles()
        val count = canvas.selectedIds.size
        val actions = mutableListOf<RelationAction>()
        fun relation(label: String, type: ConstraintType, ids: List<String>, numeric: Double? = null) {
            actions += RelationAction(label) {
                if (numeric != null) numberInput(label, numeric, angle = type == ConstraintType.ANGLE, allowZero = type == ConstraintType.DISTANCE_POINT_LINE) { value ->
                    edit(ConstructionEdits.addConstraint(scene, GeometryConstraint(ConstructionEdits.id(), type, ids, value = value)))
                } else edit(ConstructionEdits.addConstraint(scene, GeometryConstraint(ConstructionEdits.id(), type, ids)))
            }
        }
        if (count == 1 && points.size == 1) {
            val p = points.single()
            actions += RelationAction("점 위치 고정") { edit(ConstructionEdits.addConstraint(scene,
                GeometryConstraint(ConstructionEdits.id(), ConstraintType.FIXED_POINT, listOf(p.id), targetX = p.x, targetY = p.y))) }
            actions += RelationAction("고정 풀기") { edit(scene.copy(constraints = scene.constraints.map { if (it.type == ConstraintType.FIXED_POINT && p.id in it.entityIds) it.copy(enabled = false) else it })) }
        }
        if (count == 2 && points.size == 2) relation("두 점 일치", ConstraintType.COINCIDENT, points.map { it.id })
        if (count == 2 && points.size == 1 && lines.size == 1) {
            relation("점이 직선 위에 있음 (연장선 포함)", ConstraintType.POINT_ON_LINE, listOf(points[0].id, lines[0].id))
            relation("점에서 직선까지 수선 거리", ConstraintType.DISTANCE_POINT_LINE, listOf(points[0].id, lines[0].id), distanceToLine(points[0], lines[0]))
        }
        if (count == 2 && points.size == 1 && circles.size == 1) relation("점이 원 위에 있음", ConstraintType.POINT_ON_CIRCLE, listOf(points[0].id, circles[0].id))
        if (count == 1 && lines.size == 1) {
            relation("선분 길이 (cm)", ConstraintType.LENGTH, listOf(lines[0].id), length(lines[0]))
            relation("수평으로 유지", ConstraintType.HORIZONTAL, listOf(lines[0].id))
            relation("수직 방향으로 유지", ConstraintType.VERTICAL, listOf(lines[0].id))
        }
        if (count == 1 && circles.size == 1) relation("반지름 (cm)", ConstraintType.RADIUS, listOf(circles[0].id), circles[0].radius)
        if (count == 2 && lines.size == 2) {
            relation("두 선 평행", ConstraintType.PARALLEL, lines.map { it.id })
            relation("두 선 수직 (90°)", ConstraintType.PERPENDICULAR, lines.map { it.id })
            relation("두 선분 같은 길이 유지", ConstraintType.EQUAL_LENGTH, lines.map { it.id })
            relation("두 선의 각도 (시작→끝 방향 기준)", ConstraintType.ANGLE, lines.map { it.id }, angle(lines[0], lines[1]))
            actions += RelationAction("평행하게 만들고 높이 지정 (cm)") {
                val p = scene.points.first { it.id == lines[1].startPointId }
                numberInput("두 평행선 사이 높이", distanceToLine(p, lines[0]).coerceAtLeast(1.0)) { value ->
                    val parallel = ConstructionEdits.addConstraint(scene, GeometryConstraint(ConstructionEdits.id(), ConstraintType.PARALLEL, lines.map { it.id }))
                    edit(ConstructionEdits.addConstraint(parallel, GeometryConstraint(ConstructionEdits.id(), ConstraintType.DISTANCE_POINT_LINE, listOf(p.id, lines[0].id), value = value)))
                }
            }
            actions += RelationAction("두 직선의 교점 만들기") { createIntersection(lines[0], lines[1]) }
        }
        if (count == 2 && points.size == 1 && lines.size == 1) actions += RelationAction("수선과 수선의 발 만들기") { createFoot(points[0], lines[0]) }
        showPanel("조건 추가", PanelKind.RELATIONS) {
            info(if (count == 0) "그림에서 대상을 선택하세요.\n점 2개 → 일치 · 선분 1개 → 길이" else canvas.selectedIds.joinToString { name(it) })
            if (actions.isEmpty() && count > 0) info("이 조합에 추가할 조건이 없습니다. 점·선·원을 1~2개 선택하세요.")
            actions.forEach { option -> action(option.label) { if (isCurrent(token)) option.run() } }
        }
    }

    private fun numberInput(title: String, initial: Double, angle: Boolean = false, allowZero: Boolean = false, apply: (Double) -> Unit) {
        val token = generation
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatGeometry(initial)); selectAll(); setSingleLine(); contentDescription = "작도 치수 값"
        }
        panelAnchorConstraint = null; panel.translationX = 0f; panel.translationY = 0f
        selectedCondition = null; canvas.selectedMeasurementId = null
        showPanel(title, PanelKind.DETAIL) {
            info(if (angle) "시작→끝 방향 사이 각도 · 0~180°" else "조건 값 · cm (화면 확대와 무관)")
            addView(input, LinearLayout.LayoutParams(-1, dp(42)))
            addView(LinearLayout(context).apply {
                for (step in listOf(-1.0, -.1, .1, 1.0)) addView(button(if (step > 0) "+${formatGeometry(step)}" else formatGeometry(step), register = false) {
                    input.setText(formatGeometry(((input.text.toString().toDoubleOrNull() ?: initial) + step).coerceIn(if (angle || allowZero) 0.0 else .01, if (angle) 180.0 else 10000.0)))
                }, LinearLayout.LayoutParams(0, dp(36), 1f))
            })
            action("적용") {
                val value = input.text.toString().toDoubleOrNull()
                if (value == null || !value.isFinite() || value > 10000 || value < 0 || (!angle && !allowZero && value == 0.0) || (angle && value > 180)) {
                    input.error = if (angle) "0~180 사이 각도를 입력하세요." else "유효한 길이를 입력하세요."
                } else if (isCurrent(token)) { closePanel(); apply(value) }
            }
        }
    }

    private fun conditionLabel(c: GeometryConstraint): String = "${c.type.koreanName()} · ${c.entityIds.joinToString { name(it) }}${c.value?.let { " = ${formatGeometry(it)}${if (c.type == ConstraintType.ANGLE) "°" else " cm"}" } ?: ""}"
    private fun showConditions() {
        if (scene.constraints.none { it.id == selectedCondition }) selectedCondition = null
        showPanel("조건 목록 · ${scene.constraints.size}개", PanelKind.CONDITIONS) {
            if (scene.constraints.isEmpty()) info("조건이 없습니다. 대상을 선택한 뒤 조건 추가를 누르세요.")
            val selected = canvas.selectedIds
            scene.constraints.sortedByDescending { c -> c.entityIds.any { it in selected } }.forEach { c ->
                val expanded = c.id == selectedCondition
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; tag = "condition-row-${c.id}"
                    background = if (expanded) surface(0xEEF0F5FA.toInt()) else null
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(conditionCheckBox(c), LinearLayout.LayoutParams(dp(36), dp(40)))
                        addView(button("${if (expanded) "▾" else "▸"} ${conditionLabel(c)}", register = false) {
                            selectedCondition = if (expanded) null else c.id
                            canvas.selectedConstraintId = selectedCondition; canvas.selectedMeasurementId = null
                            showConditions()
                        }.apply {
                            tag = "condition-expand-${c.id}"; maxLines = 2; gravity = Gravity.CENTER_VERTICAL or Gravity.START
                            contentDescription = "조건 세부 메뉴 ${conditionLabel(c)}"
                        }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    })
                    if (expanded) addConditionControls(c)
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun conditionCheckBox(c: GeometryConstraint): CheckBox {
        val token = generation
        return CheckBox(context).apply {
            tag = "condition-enabled-${c.id}"; isChecked = c.enabled
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(79,117,158))
            setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0
            contentDescription = "${conditionLabel(c)} 조건 ${if (c.enabled) "켜짐" else "꺼짐"} · 눌러 전환"
            setOnClickListener {
                if (isCurrent(token)) {
                    edit(scene.copy(constraints = scene.constraints.map { if (it.id == c.id) it.copy(enabled = isChecked) else it }))
                } else isChecked = scene.constraints.firstOrNull { it.id == c.id }?.enabled ?: false
            }
        }
    }

    /** The same controls are embedded in a list row or in the overlay next to a dimension. */
    private fun LinearLayout.addConditionControls(c: GeometryConstraint) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; tag = "condition-controls-${c.id}"
            setPadding(dp(5), 0, dp(5), dp(3))
            val numericValue = c.value
            if (numericValue != null) {
                val token = generation
                val angle = c.type == ConstraintType.ANGLE
                val unit = if (angle) "°" else "cm"
                val step = conditionSteps[c.id] ?: if (angle) 1.0 else .1
                val input = EditText(context).apply {
                    tag = "condition-value-${c.id}"; contentDescription = "조건 값 ${c.type.koreanName()} $unit"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    textSize = 15f; gravity = Gravity.CENTER; setSingleLine(); setSelectAllOnFocus(true)
                    setPadding(dp(2), 0, dp(2), 0); setText(formatGeometry(numericValue))
                }
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    fun adjust(label: String, delta: Double, tagName: String) = button(label, register = false) {
                        if (isCurrent(token)) {
                            val current = scene.constraints.firstOrNull { it.id == c.id }?.value ?: return@button
                            val minimum = if (angle || c.type == ConstraintType.DISTANCE_POINT_LINE) 0.0 else SceneValidator.MIN_LENGTH
                            val maximum = if (angle) 180.0 else SceneValidator.MAX_MAGNITUDE
                            val proposed = kotlin.math.round((current + delta) * 1e9) / 1e9
                            if (minimum > 0 && proposed < minimum) return@button notice("길이는 0보다 커야 합니다. 더 작은 값은 직접 입력하세요.")
                            val value = proposed.coerceIn(minimum, maximum)
                            changeConditionValue(c.id, value)
                        }
                    }.apply { tag = tagName; contentDescription = "${c.type.koreanName()} $label $step $unit"; textSize = 20f }
                    addView(adjust("−", -step, "condition-minus-${c.id}"), LinearLayout.LayoutParams(dp(40), dp(38)))
                    addView(input, LinearLayout.LayoutParams(0, dp(38), 1f))
                    addView(TextView(context).apply { text = unit; textSize = 11f; setPadding(dp(2), 0, dp(4), 0) })
                    addView(adjust("+", step, "condition-plus-${c.id}"), LinearLayout.LayoutParams(dp(40), dp(38)))
                })
                addView(LinearLayout(context).apply {
                    for (choice in if (angle) listOf(1.0, 5.0) else listOf(.1, 1.0)) {
                        addView(button("${formatGeometry(choice)}$unit", register = false) {
                            conditionSteps[c.id] = choice; refreshPanel()
                        }.apply {
                            isSelected = step == choice; tag = "condition-step-${c.id}-$choice"
                            contentDescription = "조절 간격 ${formatGeometry(choice)} $unit"
                        }, LinearLayout.LayoutParams(0, dp(32), 1f))
                    }
                    addView(button("입력 적용", register = false) {
                        if (isCurrent(token)) {
                            val value = input.text.toString().toDoubleOrNull()
                            val minimum = if (angle || c.type == ConstraintType.DISTANCE_POINT_LINE) 0.0 else SceneValidator.MIN_LENGTH
                            val maximum = if (angle) 180.0 else SceneValidator.MAX_MAGNITUDE
                            if (value == null || !value.isFinite() || value !in minimum..maximum) {
                                input.error = if (angle) "0~180°를 입력하세요" else "유효한 길이를 입력하세요"
                            } else { hideKeyboard(); changeConditionValue(c.id, value) }
                        }
                    }.apply { tag = "condition-apply-${c.id}" }, LinearLayout.LayoutParams(0, dp(32), 1.3f))
                })
                if (c.type == ConstraintType.RADIUS) info("반지름 기준 · 둘레 ${formatGeometry(2 * Math.PI * numericValue)} cm")
                if (angle) info("시작→끝 방향 사이 각도 · 0~180°")
                if (!c.enabled) info("잠시 꺼짐 · 설정 값만 변경됩니다. 체크하면 다시 적용합니다.")
            } else info(if (c.enabled) "체크를 해제하면 관계를 잠시 풉니다." else "잠시 꺼짐 · 체크하면 다시 적용합니다.")
            action("조건 삭제", ConstructionIcon.DELETE) {
                selectedCondition = null; canvas.selectedConstraintId = null
                if (panelKind == PanelKind.DETAIL) closePanel()
                edit(scene.copy(constraints = scene.constraints.filterNot { it.id == c.id }))
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun changeConditionValue(id: String, value: Double) {
        // A disabled condition stays disabled; the checkbox alone controls activation.
        edit(scene.copy(constraints = scene.constraints.map { if (it.id == id) it.copy(value = value) else it }))
    }

    private fun showConditionDetails(id: String) {
        val c = scene.constraints.firstOrNull { it.id == id } ?: return closePanel()
        selectedCondition = id; canvas.selectedConstraintId = id; canvas.selectedMeasurementId = null
        showPanel("조건 · ${c.type.koreanName()}", PanelKind.DETAIL) {
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(conditionCheckBox(c), LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(TextView(context).apply {
                    text = conditionLabel(c); textSize = 11f; setTextColor(Color.rgb(80,91,83))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addConditionControls(c)
            action("조건 목록으로") { showConditions() }
        }
        positionConditionPanel()
    }

    private fun length(line: GeometrySegment): Double {
        val a = scene.points.first { it.id == line.startPointId }; val b = scene.points.first { it.id == line.endPointId }
        return hypot(a.x - b.x, a.y - b.y)
    }
    private fun angle(first: GeometrySegment, second: GeometrySegment): Double {
        val a = scene.points.first { it.id == first.startPointId }; val b = scene.points.first { it.id == first.endPointId }
        val c = scene.points.first { it.id == second.startPointId }; val d = scene.points.first { it.id == second.endPointId }
        val dot = ((b.x - a.x) * (d.x - c.x) + (b.y - a.y) * (d.y - c.y)) / (length(first) * length(second))
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }
    private fun distanceToLine(p: GeometryPoint, line: GeometrySegment): Double {
        val a = scene.points.first { it.id == line.startPointId }; val b = scene.points.first { it.id == line.endPointId }
        return kotlin.math.abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / length(line)
    }
    private fun showMeasurement() {
        val p = selectedPoints(); val s = selectedSegments(); val c = selectedCircles()
        val candidates = buildList {
            s.forEach { add(GeometryMeasurement(ConstructionEdits.id(), MeasurementType.DISTANCE, listOf(it.startPointId, it.endPointId))) }
            c.forEach { add(GeometryMeasurement(ConstructionEdits.id(), MeasurementType.RADIUS, listOf(it.id))) }
            if (p.size == 2) add(GeometryMeasurement(ConstructionEdits.id(), MeasurementType.DISTANCE, p.map { it.id }))
            if (p.size == 3) {
                add(GeometryMeasurement(ConstructionEdits.id(), MeasurementType.ANGLE, p.map { it.id }))
                add(GeometryMeasurement(ConstructionEdits.id(), MeasurementType.AREA, p.map { it.id }))
            }
            if (s.size == 2) {
                val first = listOf(s[0].startPointId, s[0].endPointId); val second = listOf(s[1].startPointId, s[1].endPointId)
                first.intersect(second.toSet()).singleOrNull()?.let { vertex ->
                    val ids = listOf(first.first { it != vertex }, vertex, second.first { it != vertex })
                    if (ids.distinct().size == 3) add(GeometryMeasurement(ConstructionEdits.id(), MeasurementType.ANGLE, ids))
                }
            }
        }
        showPanel("측정 · 모양은 고정하지 않음", PanelKind.MEASURE) {
            if (candidates.isEmpty()) info("선분·원 또는 점 2~3개를 선택하세요.\n각도는 세 점 A → 꼭짓점 B → C 순서입니다.")
            candidates.forEach { measurement ->
                val label = measurementLabel(measurement)
                val existing = ConstructionEdits.matchingMeasurement(scene, measurement)
                if (existing != null) action("표시 편집 · $label") { showMeasurementDetails(existing.id) }
                else if (ConstructionMeasurementGeometry.layout(scene, measurement) != null) action("그림에 표시 · $label") {
                    runCatching { ConstructionEdits.upsertMeasurement(scene, measurement) }.onSuccess { next ->
                        if (next == scene) notice("이미 그림에 표시되어 있습니다. 글자를 끌어 위치를 바꿀 수 있습니다.") else presentationEdit(next)
                    }.onFailure { notice(it.message.orEmpty()) }
                }
            }
            c.forEach { info("${name(it.id)} 넓이 ${formatGeometry(Math.PI * it.radius * it.radius)} cm²") }
            if (s.size == 2 && candidates.none { it.type == MeasurementType.ANGLE }) info("시작→끝 방향 사이 ${formatGeometry(angle(s[0], s[1]))}°\n각을 그림에 표시하려면 꼭짓점을 포함한 세 점을 선택하세요.")
            if (p.size == 1 && s.size == 1) info("점~직선 수선 거리 ${formatGeometry(distanceToLine(p[0], s[0]))} cm")
            if (scene.measurements.isNotEmpty()) {
                val others = scene.measurements.filter { m -> candidates.none { ConstructionEdits.matchingMeasurement(scene, it)?.id == m.id } }
                if (others.isNotEmpty()) info("그림에 표시된 다른 측정")
                others.forEach { m -> action(measurementLabel(m)) { showMeasurementDetails(m.id) } }
                info("측정 글자를 끌어 표시 위치를 바꿀 수 있습니다.")
            }
        }
    }
    private fun measurementLabel(m: GeometryMeasurement): String {
        val value = ConstructionMeasurementGeometry.layout(scene, m)?.value?.takeIf { it.isFinite() }
        val target = when (m.type) {
            MeasurementType.DISTANCE -> m.entityIds.joinToString("~") { name(it) }
            MeasurementType.ANGLE -> "∠" + m.entityIds.joinToString("") { name(it) }
            MeasurementType.RADIUS -> "${name(m.entityIds.first())} 반지름"
            MeasurementType.AREA -> "△${m.entityIds.joinToString("") { name(it) }} 넓이"
        }
        val unit = when (m.type) { MeasurementType.ANGLE -> "°"; MeasurementType.AREA -> " cm²"; else -> " cm" }
        return "$target = ${value?.let(::formatGeometry) ?: "정의되지 않음"}${if (value != null) unit else ""}"
    }
    private fun showMeasurementDetails(id: String) {
        val m = scene.measurements.firstOrNull { it.id == id } ?: return closePanel()
        panelAnchorConstraint = null
        selectedCondition = null; canvas.selectedConstraintId = null; canvas.selectedMeasurementId = id
        showPanel("측정 표시", PanelKind.DETAIL) {
            info(measurementLabel(m)); info("글자를 끌어 위치를 바꾸세요. 측정은 조건이 아니므로 도형을 고정하지 않습니다.")
            action("표시 위치 초기화") { presentationEdit(scene.copy(measurements = scene.measurements.map { if (it.id == id) it.copy(offsetX = 0.0, offsetY = 0.0) else it })) }
            action("표시 지우기", ConstructionIcon.DELETE) { closePanel(); presentationEdit(scene.copy(measurements = scene.measurements.filterNot { it.id == id })) }
            action("측정으로") { showMeasurement() }
        }
    }
    private fun renamePoint() {
        val token = generation
        val p = selectedPoints().singleOrNull()?.takeIf { canvas.selectedIds.size == 1 } ?: return notice("이름을 바꿀 점 하나를 선택하세요.")
        val input = EditText(context).apply { setText(p.label); setSingleLine(); selectAll(); filters = arrayOf(android.text.InputFilter.LengthFilter(12)) }
        panelAnchorConstraint = null; panel.translationX = 0f; panel.translationY = 0f
        selectedCondition = null; canvas.selectedMeasurementId = null
        showPanel("점 이름", PanelKind.DETAIL) {
            addView(input, LinearLayout.LayoutParams(-1, dp(42)))
            action("적용") { if (isCurrent(token)) { closePanel(); presentationEdit(scene.copy(points = scene.points.map { if (it.id == p.id) it.copy(label = input.text.toString().trim()) else it })) } }
        }
    }
    private fun deleteSelection() {
        val token = generation
        val selected = canvas.selectedIds.toSet()
        if (selected.isEmpty()) return notice("삭제할 점·선·원을 선택하세요.")
        val next = ConstructionEdits.remove(scene, selected)
        val count = scene.points.size + scene.segments.size + scene.circles.size - next.points.size - next.segments.size - next.circles.size
        val constraints = scene.constraints.size - next.constraints.size
        AlertDialog.Builder(context).setTitle("선택한 도형 삭제")
            .setMessage("연결된 도형을 포함해 ${count}개와 관련 조건 ${constraints}개를 삭제합니다. 되돌리기로 복구할 수 있습니다.")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> if (isCurrent(token)) edit(next) }.showChild()
    }

    private fun createIntersection(first: GeometrySegment, second: GeometrySegment) {
        val a = scene.points.first { it.id == first.startPointId }; val b = scene.points.first { it.id == first.endPointId }
        val c = scene.points.first { it.id == second.startPointId }; val d = scene.points.first { it.id == second.endPointId }
        val determinant = (b.x - a.x) * (d.y - c.y) - (b.y - a.y) * (d.x - c.x)
        if (kotlin.math.abs(determinant) < 1e-10) return notice("평행하거나 겹친 직선에는 하나의 교점이 정해지지 않습니다.")
        val t = ((c.x - a.x) * (d.y - c.y) - (c.y - a.y) * (d.x - c.x)) / determinant
        val p = GeometryPoint(ConstructionEdits.id(), a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), ConstructionEdits.nextPointLabel(scene), newColor)
        edit(scene.copy(points = scene.points + p, constraints = scene.constraints + listOf(
            GeometryConstraint(ConstructionEdits.id(), ConstraintType.POINT_ON_LINE, listOf(p.id, first.id)),
            GeometryConstraint(ConstructionEdits.id(), ConstraintType.POINT_ON_LINE, listOf(p.id, second.id)),
        )))
    }
    private fun createFoot(p: GeometryPoint, line: GeometrySegment) {
        val a = scene.points.first { it.id == line.startPointId }; val b = scene.points.first { it.id == line.endPointId }
        val dx = b.x - a.x; val dy = b.y - a.y
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
        if (distanceToLine(p, line) < .0001) return notice("점이 이미 직선 위에 있습니다. 길이 0인 수선은 만들지 않습니다.")
        val e = GeometryPoint(ConstructionEdits.id(), a.x + t * dx, a.y + t * dy, ConstructionEdits.nextPointLabel(scene), newColor)
        val perpendicular = GeometrySegment(ConstructionEdits.id(), p.id, e.id, colorArgb = newColor, lineStyle = newLineStyle)
        edit(scene.copy(points = scene.points + e, segments = scene.segments + perpendicular, constraints = scene.constraints + listOf(
            GeometryConstraint(ConstructionEdits.id(), ConstraintType.POINT_ON_LINE, listOf(e.id, line.id)),
            GeometryConstraint(ConstructionEdits.id(), ConstraintType.PERPENDICULAR, listOf(perpendicular.id, line.id)),
        )))
    }
    private fun showExamples() {
        val token = generation
        AlertDialog.Builder(context).setTitle("예제 열기")
            .setItems(arrayOf("10cm + 6cm 연결 막대", "사다리꼴 · 수선 길이 3.8cm")) { _, which ->
                if (!isCurrent(token)) return@setItems
                fun applyExample() {
                    if (!isCurrent(token)) return
                    canvas.clearSelection(); canvas.tool = ConstructionTool.SELECT
                    fitAfterCommit = true
                    edit(if (which == 0) ConstructionEdits.linkedBars() else ConstructionEdits.trapezoid())
                }
                if (scene.points.isEmpty()) applyExample() else AlertDialog.Builder(context).setTitle("현재 작도를 예제로 바꿀까요?")
                    .setMessage("기존 필기는 그대로 두며, 현재 작도는 되돌리기로 복구할 수 있습니다.")
                    .setNegativeButton("취소", null).setPositiveButton("예제 열기") { _, _ -> applyExample() }.showChild()
            }.setNegativeButton("취소", null).showChild()
    }
    private fun showHelp() {
        AlertDialog.Builder(context).setTitle("함께 작도하기")
            .setMessage("1. 눌린 도구의 배경색과 왼쪽 위 안내가 다음 동작을 알려줍니다. 선분·원은 두 번 눌러 만듭니다.\n2. 도형을 선택하고 색·실선·점선·점점선 아이콘을 누르면 바로 바뀝니다. 선택이 없으면 다음 도형에 적용됩니다.\n3. 자석이 켜져 있으면 기존 끝점·선 위·두 선의 교점에 연결됩니다. 원 둘레를 맞추는 두 번째 탭은 반지름 위치만 맞추며 연결 조건을 만들지는 않습니다.\n4. 선택 → 조건 추가로 모양을 유지할 관계를 지정합니다. 길이·반지름·방향각은 그림에 표시됩니다. 표시를 누르면 옆 메뉴에서 ±로 바로 조절합니다. 조건 목록의 항목은 아래로 펼쳐지며 체크로 잠시 끄고 켭니다.\n5. 선택 → 측정 → 그림에 표시. 측정은 모양을 고정하지 않습니다. 글자를 끌면 치수 표시만 이동합니다. 각도는 A → 꼭짓점 B → C 순서로 세 점을 선택합니다.\n6. 작은 메뉴의 제목을 끌면 옮길 수 있습니다. 열고 닫아도 도형은 움직이지 않습니다. 두 손가락으로 도형을 확대·이동합니다.\n\n빈 곳을 누르면 선택 해제. 겹친 점은 반복해서 누르면 따로 선택됩니다. 두 선의 각도 조건은 시작→끝 방향 기준입니다. 직선 위 조건·수선·교점은 연장선을 포함합니다. 원과 선·두 원의 교점 자동 연결은 아직 지원하지 않습니다.\n\n${if (embedded) "도형과 손필기는 같은 평면에서 함께 확대·이동하지만 지우개·되돌리기는 서로 영향을 주지 않습니다. 필기/도형 모드를 선택해 조작하세요. 학생 도형은 손을 놓아 저장한 뒤 자동 전송됩니다. 선생 도형은 초안으로 저장되며 발행을 눌러야 학생에게 전송됩니다. 끊겨도 로컬 작업은 유지됩니다." else "이 전체화면 작도는 현재 기기에 자동 저장되고 앱 백업에 포함됩니다. 메모 안에 넣은 도형판만 원격 동기화 대상입니다."}")
            .setPositiveButton("확인", null).showChild()
    }
    private fun requestClose() {
        if (busy || dragSolving) return notice("저장을 마친 뒤 닫을 수 있습니다.")
        canvas.cancelDrag(); onRequestClose()
    }
    fun handleBack() { if (panelKind != null) closePanel() else requestClose() }
    fun undoEdit(): Boolean = if (canUndo) { history(true); true } else false
    fun redoEdit(): Boolean = if (canRedo) { history(false); true } else false
    fun cancelInteraction() { canvas.cancelDrag(); canvas.tool = canvas.tool; dismissChildren() }

    /** Toolbars remain outside the shared content rectangle; both editable layers fill it exactly. */
    fun attachSharedCanvas(host: SharedMemoCanvasHost) {
        check(embedded && sharedMemoHost == null)
        sharedMemoHost = host
        (host.parent as? ViewGroup)?.removeView(host)
        viewport.removeView(canvas)
        canvas.sharedViewport = host.viewport
        canvas.setBackgroundColor(Color.TRANSPARENT)
        canvas.onSharedZoom = { host.zoomBy(it) }
        canvas.onSharedFit = { host.fitContent() }
        host.addView(canvas, 0, FrameLayout.LayoutParams(-1, -1))
        host.geometryLayer = canvas
        viewport.addView(host, 0, FrameLayout.LayoutParams(-1, -1))
    }

    fun detachSharedCanvas() {
        val host = sharedMemoHost ?: return
        host.cancelOwnedGesture()
        host.geometryLayer = null
        host.removeView(canvas)
        viewport.removeView(host)
        canvas.sharedViewport = null
        canvas.onSharedZoom = {}; canvas.onSharedFit = {}
        sharedMemoHost = null
    }

    fun setMemoGeometryMode(enabled: Boolean) {
        if (memoGeometryActive != enabled) cancelInteraction()
        memoGeometryActive = enabled
        selectionInfo.visibility = if (enabled) View.VISIBLE else View.GONE
        status.visibility = if (enabled) View.VISIBLE else View.GONE
        updateToolbar()
    }

    fun notifySharedViewportChanged() { canvas.notifyViewportChanged() }
    private fun reloadAfterRemoteChange() {
        generation++; dragRequest++; dragBase = null; measurementBase = null
        pendingDrag = null; dragSolving = false; undo.clear(); redo.clear(); canvas.cancelDrag()
        load(fit = false)
    }
    private fun refreshSyncState() {
        if (!embedded || closed) return
        val state = runCatching { syncBridge?.state(target) }.getOrNull()
        syncStatus.text = state?.message?.takeIf(String::isNotBlank)
            ?: if (replicaRole == ConstructionReplicaRole.TEACHER) "선생 도형 초안 · 발행해야 학생에게 전송됩니다"
            else "도형 자동 저장 · 연결 시 자동 전송"
        syncStatus.isClickable = state?.conflictToken != null
        publishButton?.isEnabled = !hasPendingWork && state?.canPublish == true && !state.busy
    }
    private fun requestPublication() {
        if (hasPendingWork || closed) return
        val state = syncBridge?.state(target) ?: return notice("원격 연결 상태를 확인해 주세요.")
        if (state.conflictToken != null) { showConflictChoices(); return }
        if (!state.canPublish || state.busy) return notice(state.message)
        syncBridge.requestPublish(target)
        refreshSyncState()
    }
    private fun showConflictChoices() {
        if (hasPendingWork || closed) return
        val bridge = syncBridge ?: return
        val expectedToken = bridge.state(target).conflictToken ?: return
        val token = generation
        val labels = arrayOf("선생 도형으로 학생 맞추기", "학생 도형으로 선생 맞추기")
        AlertDialog.Builder(context).setTitle("서로 다른 도형이 있습니다")
            .setMessage("두 기기의 도형이 달라졌습니다. 어느 도형을 사용할지 선택하세요. 손필기는 변경되지 않습니다.")
            .setPositiveButton(labels[0]) { _, _ -> confirmConflict(bridge, expectedToken, token, ConstructionConflictChoice.USE_TEACHER, labels[0]) }
            .setNeutralButton(labels[1]) { _, _ -> confirmConflict(bridge, expectedToken, token, ConstructionConflictChoice.USE_STUDENT, labels[1]) }
            .setNegativeButton("취소", null).showChild()
    }
    private fun confirmConflict(bridge: ConstructionUiBridge, expectedToken: String, token: Long, choice: ConstructionConflictChoice, label: String) {
        if (!isCurrent(token) || bridge.state(target).conflictToken != expectedToken) {
            notice("비교하는 동안 도형이 바뀌었습니다. 최신 도형을 다시 확인해 주세요."); return
        }
        AlertDialog.Builder(context).setTitle(label).setMessage("선택한 도형으로 맞출까요? 상대 도형이 다시 바뀌면 재확인합니다.")
            .setPositiveButton("확인") { _, _ ->
                if (isCurrent(token) && bridge.state(target).conflictToken == expectedToken) {
                    bridge.resolveConflict(target, choice, expectedToken); refreshSyncState()
                } else notice("비교하는 동안 도형이 바뀌었습니다. 다시 선택해 주세요.")
            }.setNegativeButton("취소", null).showChild()
    }
    fun closeEditor() {
        if (!closed) {
            canvas.cancelDrag()
            closed = true; generation++; dragRequest++
            dismissChildren()
            restoreListener?.close(); restoreListener = null; worker.shutdown()
            replicaListener?.close(); replicaListener = null
            syncListener?.close(); syncListener = null
        }
    }
}

/** The existing full-screen entry point shares exactly the same controller as the memo pane. */
internal class ConstructionEditorDialog(
    context: Context,
    private val target: ConstructionTarget,
    private val titleText: String,
) : Dialog(context, android.R.style.Theme_Material_Light_NoActionBar) {
    private var editor: ConstructionEditorView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCanceledOnTouchOutside(false)
        editor = ConstructionEditorView(context, target, titleText).also {
            it.onRequestClose = { dismiss() }
            setContentView(it)
        }
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    @Deprecated("Android dialog back navigation")
    override fun onBackPressed() { editor?.handleBack() ?: super.onBackPressed() }

    override fun dismiss() {
        editor?.closeEditor()
        editor = null
        super.dismiss()
    }
}
