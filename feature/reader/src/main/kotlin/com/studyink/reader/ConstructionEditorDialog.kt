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
    private val panelScroll = ScrollView(context)
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
    private var pendingEqualAngle: List<String>? = null
    private var pendingEqualMeasurement: String? = null
    private var panelRevision = 0L
    private var renderedDetailKey: String? = null
    private val conditionSteps = mutableMapOf<String, Double>()
    private var measurementBase: ConstructionScene? = null
    private enum class PanelKind { RELATIONS, MEASURE, CONDITIONS, MORE, DETAIL }
    private val actionButtons = mutableListOf<Button>()
    private var closeButton: Button? = null
    private var deleteButton: Button? = null
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
                if (tool == ConstructionTool.SELECT) {
                    deleteButton = button("선택 도형 삭제", icon = ConstructionIcon.DELETE, iconOnly = true) {
                        onEditingRequested(); deleteSelection()
                    }.apply { tag = "construction-delete-selected" }
                    addView(deleteButton)
                }
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
            addView(button("기본 도형", icon = ConstructionIcon.SQUARE, iconOnly = true) { onEditingRequested(); showPresets() })
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
        canvas.onMeasurementSelected = { id ->
            canvas.selectedMeasurementId = id; showMeasurementDetails(id); updateHint()
        }
        canvas.onMeasurementDrag = ::onMeasurementDrag
        canvas.onConstraintSelected = { id ->
            // A driving dimension takes over its saved measurement's hit target. While choosing
            // a second measurement, that visible label must still participate in the same flow.
            val reference = scene.measurements.firstOrNull { it.id == pendingEqualMeasurement }
            val constraint = scene.constraints.firstOrNull { it.id == id }
            val measurement = if (reference == null || constraint == null) null else scene.measurements.firstOrNull {
                it.type == reference.type && ConstructionMeasurementGeometry.matchesConstraint(scene, it, constraint)
            }
            if (measurement != null) {
                showMeasurementDetails(measurement.id)
                canvas.selectedConstraintId = id
            } else showConditionDetails(id)
            updateHint()
        }
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
        panelTitle.apply { textSize = 12f; setTextColor(Color.rgb(51,69,59)); setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(dp(5), 0, 0, 0); contentDescription = "고정 조절 메뉴 제목" }
        heading.addView(panelTitle, LinearLayout.LayoutParams(0, dp(32), 1f))
        heading.addView(constructionButton(context, "메뉴 닫기", ConstructionIcon.CLOSE, true) { closePanel() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        })
        panel.addView(heading)
        panelBody.orientation = LinearLayout.VERTICAL
        panelScroll.isFillViewport = false; panelScroll.addView(panelBody)
        panel.addView(panelScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        viewport.addView(panel, FrameLayout.LayoutParams(dp(272), dp(300), Gravity.TOP or Gravity.END).apply { rightMargin = dp(8); topMargin = dp(8) })
        viewport.addOnLayoutChangeListener { _, l,t,r,b, oldL,oldT,oldR,oldB ->
            if (r-l != oldR-oldL || b-t != oldB-oldT) updatePanelFrame()
        }
    }
    /** One predictable inspector rectangle for every menu. Only the window, never its contents
     * or a moving dimension, may resize it; overflow belongs to the inner scroll view. */
    private fun updatePanelFrame() {
        if (viewport.width <= 0 || viewport.height <= 0) return
        val w = minOf(dp(272), (viewport.width - dp(16)).coerceAtLeast(1))
        val h = minOf(dp(300), (viewport.height - dp(16)).coerceAtLeast(1))
        val params = panel.layoutParams as FrameLayout.LayoutParams
        if (params.width != w || params.height != h) {
            panel.layoutParams = params.apply { width = w; height = h }
        }
    }
    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(panel.windowToken, 0)
        panel.findFocus()?.clearFocus()
    }
    private fun closePanel() {
        hideKeyboard(); panelKind = null; panel.visibility = View.GONE
        panelRevision++
        renderedDetailKey = null
        panelBody.removeAllViews(); selectedCondition = null; canvas.selectedConstraintId = null
        canvas.selectedMeasurementId = null; updateToolbar()
    }
    private fun showPanel(title: String, kind: PanelKind, build: LinearLayout.() -> Unit) {
        val detailKey = selectedCondition?.let { "condition:$it" } ?: canvas.selectedMeasurementId?.let { "measurement:$it" }
        val retainedScroll = if (panelKind == kind && (kind == PanelKind.CONDITIONS || kind == PanelKind.DETAIL && detailKey != null && detailKey == renderedDetailKey)) panelScroll.scrollY else 0
        val revision = ++panelRevision
        renderedDetailKey = if (kind == PanelKind.DETAIL) detailKey else null
        hideKeyboard(); panelKind = kind; panelTitle.text = title
        updatePanelFrame()
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
    private fun LinearLayout.action(label: String, icon: ConstructionIcon? = null, actionTag: String? = null, apply: () -> Unit) {
        val token = generation
        addView(button(label, register = false, icon = icon) { if (isCurrent(token)) apply() }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START; layoutParams = LinearLayout.LayoutParams(-1, dp(36))
            if (actionTag != null) tag = actionTag
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
        deleteButton?.isEnabled = !busy && !closed && dragBase == null && measurementBase == null &&
            canvas.selectedIds.any { it in allIds() }
    }
    private fun updateHint() {
        val reference = scene.measurements.firstOrNull { it.id == pendingEqualMeasurement }
        selectionInfo.text = if (reference != null) {
            "${if (reference.type == MeasurementType.ANGLE) "같은 각" else "같은 길이"} · 기준 ${reference.entityIds.joinToString("") { name(it) }} → 다른 측정 표시를 누르세요"
        } else if (pendingEqualAngle != null) {
            "∠${pendingEqualAngle!!.joinToString("") { name(it) }}와 같게 · 다른 각의 세 점(끝→꼭짓점→끝) 선택 후 조건 추가"
        } else if (canvas.tool == ConstructionTool.SELECT) {
            when {
                canvas.selectedIds.isNotEmpty() -> "선택 ${canvas.selectedIds.size}개 · ${canvas.selectedIds.joinToString { name(it) }} · 휴지통으로 삭제"
                canvas.selectedConstraintId != null || canvas.selectedMeasurementId != null -> "표시 확인 중 · 도형을 지우려면 ‘대상 도형 선택’ 후 휴지통"
                else -> "선택 · 점·선·원을 눌러 선택한 뒤 휴지통으로 삭제"
            }
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
        clearMeasurementReference()
        pendingEqualAngle = null
        canvas.tool = tool; canvas.clearSelection()
        closePanel(); updateToolbar(); updateHint()
    }
    private fun load(fit: Boolean = true) {
        clearMeasurementReference()
        pendingEqualAngle = null
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
        pendingEqualAngle = null; clearMeasurementReference()
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
        if (pendingEqualMeasurement != null && scene.measurements.none { it.id == pendingEqualMeasurement }) clearMeasurementReference()
        updateHint(); updateToolbar()
        // Transient input menus capture a generation. A completed unrelated edit must dismiss
        // them rather than leave visible buttons that silently reject every subsequent click.
        if (panelKind == PanelKind.DETAIL && (detailSelection != canvas.selectedIds ||
                selectedCondition == null && canvas.selectedMeasurementId == null)) closePanel()
        if (dragBase == null && measurementBase == null) refreshPanel()
    }

    private data class RelationAction(val label: String, val icon: ConstructionIcon = ConstructionIcon.CONSTRAINT, val run: () -> Unit)
    private fun relationIcon(type: ConstraintType): ConstructionIcon = when (type) {
        ConstraintType.LENGTH, ConstraintType.DISTANCE_POINTS, ConstraintType.POINT_DISTANCE,
        ConstraintType.DISTANCE_POINT_LINE -> ConstructionIcon.MEASURE
        ConstraintType.ANGLE, ConstraintType.INTERIOR_ANGLE, ConstraintType.EQUAL_ANGLE -> ConstructionIcon.ANGLE
        ConstraintType.EQUAL_LENGTH, ConstraintType.EQUAL_DISTANCE_POINTS, ConstraintType.LENGTH_RATIO -> ConstructionIcon.EQUAL
        ConstraintType.POINT_FRACTION -> ConstructionIcon.DIVIDE
        ConstraintType.RADIUS, ConstraintType.POINT_ON_CIRCLE -> ConstructionIcon.CIRCLE
        ConstraintType.COINCIDENT, ConstraintType.FIXED_POINT, ConstraintType.POINT_ON_LINE,
        ConstraintType.POINT_ON_SEGMENT -> ConstructionIcon.POINT
        else -> ConstructionIcon.CONSTRAINT
    }
    private fun showRelationMenu() {
        val token = generation
        val points = selectedPoints(); val lines = selectedSegments(); val circles = selectedCircles()
        val count = canvas.selectedIds.size
        val actions = mutableListOf<RelationAction>()
        fun relation(label: String, type: ConstraintType, ids: List<String>, numeric: Double? = null) {
            actions += RelationAction(label, relationIcon(type)) {
                if (numeric != null) numberInput(label, numeric, angle = type in setOf(ConstraintType.ANGLE, ConstraintType.INTERIOR_ANGLE), allowZero = type == ConstraintType.DISTANCE_POINT_LINE,
                    description = if (type == ConstraintType.LENGTH_RATIO) "${name(ids[0])} 길이 = 입력 배수 × ${name(ids[1])} 길이" else null) { value ->
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
            actions += RelationAction("선분 위 위치 · 등분 / 끝점부터 cm") { showPointLocation(points[0].id, lines[0].id) }
            relation("점이 직선 위에 있음 (연장선 포함)", ConstraintType.POINT_ON_LINE, listOf(points[0].id, lines[0].id))
            relation("점에서 직선까지 수선 거리", ConstraintType.DISTANCE_POINT_LINE, listOf(points[0].id, lines[0].id), distanceToLine(points[0], lines[0]))
        }
        if (count == 2 && points.size == 1 && circles.size == 1) relation("점이 원 위에 있음", ConstraintType.POINT_ON_CIRCLE, listOf(points[0].id, circles[0].id))
        if (count == 1 && lines.size == 1) {
            relation("선분 길이 (cm)", ConstraintType.LENGTH, listOf(lines[0].id), length(lines[0]))
            relation("수평으로 유지", ConstraintType.HORIZONTAL, listOf(lines[0].id))
            relation("수직 방향으로 유지", ConstraintType.VERTICAL, listOf(lines[0].id))
            actions += RelationAction("등분점 만들기 · 원래 선분 유지") { showDivideSegment(lines[0].id) }
        }
        if (count == 1 && circles.size == 1) relation("반지름 (cm)", ConstraintType.RADIUS, listOf(circles[0].id), circles[0].radius)
        if (count == 2 && lines.size == 2) {
            relation("두 선 평행", ConstraintType.PARALLEL, lines.map { it.id })
            relation("두 선 수직 (90°)", ConstraintType.PERPENDICULAR, lines.map { it.id })
            relation("두 선분 같은 길이 유지", ConstraintType.EQUAL_LENGTH, lines.map { it.id })
            relation("두 선분 길이 비율", ConstraintType.LENGTH_RATIO, lines.map { it.id }, length(lines[0]) / length(lines[1]))
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
        if (count >= 3 && lines.size == count) actions += RelationAction("선분 ${count}개 모두 같은 길이") {
            runCatching { ConstructionEdits.multiEqualLength(scene, lines.map { it.id }) }
                .onSuccess { edit(it) }.onFailure { notice(it.message.orEmpty()) }
        }
        val angleIds = selectedAngleIds()
        if (angleIds != null) {
            val value = ConstructionMeasurementGeometry.layout(scene, GeometryMeasurement("preview", MeasurementType.ANGLE, angleIds))?.value
            if (value != null) relation("∠${angleIds.joinToString("") { name(it) }} 크기 지정", ConstraintType.INTERIOR_ANGLE, angleIds, value)
            actions += RelationAction(if (pendingEqualAngle == null) "이 각과 다른 각을 같게…" else "기준 각과 이 각을 같게") { chooseEqualAngle(angleIds) }
        }
        if (count == 2 && points.size == 1 && lines.size == 1) actions += RelationAction("수선과 수선의 발 만들기") { createFoot(points[0], lines[0]) }
        showPanel("조건 추가", PanelKind.RELATIONS) {
            info(if (count == 0) "그림에서 대상을 선택하세요.\n점 2개 → 일치 · 선분 1개 → 길이" else canvas.selectedIds.joinToString { name(it) })
            if (pendingEqualAngle != null) action("같은 각 선택 취소") { pendingEqualAngle = null; updateHint(); showRelationMenu() }
            if (actions.isEmpty() && count > 0) info("점·선·원을 선택하세요. 각도는 세 점(끝→꼭짓점→끝), 같은 길이는 여러 선분입니다.")
            actions.forEach { option -> action(option.label, option.icon) { if (isCurrent(token)) option.run() } }
        }
    }

    private fun selectedAngleIds(): List<String>? {
        val points = selectedPoints()
        if (points.size == 3 && canvas.selectedIds.size == 3) return points.map { it.id }
        val lines = selectedSegments()
        if (lines.size != 2 || canvas.selectedIds.size != 2) return null
        val a = listOf(lines[0].startPointId, lines[0].endPointId)
        val b = listOf(lines[1].startPointId, lines[1].endPointId)
        val vertex = a.intersect(b.toSet()).singleOrNull() ?: return null
        return listOf(a.first { it != vertex }, vertex, b.first { it != vertex }).takeIf { it.distinct().size == 3 }
    }

    /** Select each angle separately so a shared vertex can participate in both triples. */
    private fun chooseEqualAngle(ids: List<String>) {
        clearMeasurementReference()
        val first = pendingEqualAngle
        if (first == null) {
            pendingEqualAngle = ids.toList()
            closePanel(); canvas.tool = ConstructionTool.SELECT; canvas.clearSelection(); updateHint()
        } else {
            val condition = GeometryConstraint(ConstructionEdits.id(), ConstraintType.EQUAL_ANGLE, first + ids)
            val next = ConstructionEdits.addConstraint(scene, condition)
            val errors = SceneValidator.validate(next)
            if (errors.isNotEmpty()) return notice("서로 다른 두 각을 선택하세요. 각의 가운데 점이 꼭짓점입니다.")
            pendingEqualAngle = null; closePanel(); updateHint(); edit(next)
        }
    }

    private fun showPresets() {
        pendingEqualAngle = null; clearMeasurementReference()
        canvas.tool = ConstructionTool.SELECT; updateHint()
        selectedCondition = null; canvas.selectedMeasurementId = null
        showPanel("기본 도형", PanelKind.DETAIL) {
            info("현재 화면 가운데에 추가합니다. 점·선분과 관계로 만들어져 길이를 바꾸거나 움직일 수 있습니다.")
            ConstructionPreset.entries.forEach { preset -> action(preset.koreanName(), preset.icon()) {
                val center = canvas.viewportCenterWorld()
                runCatching { ConstructionEdits.createPreset(scene, preset, center.x, center.y, colorArgb = newColor, lineStyle = newLineStyle) }
                    .onSuccess { closePanel(); edit(it) }.onFailure { notice(it.message.orEmpty()) }
            } }
        }
    }

    private fun integerInput(initial: Int, description: String) = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); textSize = 14f; gravity = Gravity.CENTER
        setText(initial.toString()); setSelectAllOnFocus(true); contentDescription = description; tag = description
    }

    private fun showDivideSegment(segmentId: String) {
        selectedCondition = null; canvas.selectedMeasurementId = null
        val input = integerInput(3, "전체 등분 수")
        showPanel("${name(segmentId)} 등분점 만들기", PanelKind.DETAIL) {
            info("원래 선분은 나누어 삭제하지 않습니다. 새 점들이 선분의 길이 변화에 따라 함께 움직입니다.")
            addView(input, LinearLayout.LayoutParams(-1, dp(40)))
            fun divide(n: Int) {
                runCatching { ConstructionEdits.divideSegment(scene, segmentId, n, newColor) }
                    .onSuccess { closePanel(); edit(it) }.onFailure { input.error = it.message }
            }
            action("중점 하나 만들기", ConstructionIcon.DIVIDE) { divide(2) }
            action("3등분점 두 개 만들기", ConstructionIcon.DIVIDE) { divide(3) }
            action("입력한 수로 등분점 만들기", ConstructionIcon.DIVIDE) {
                val n = input.text.toString().toIntOrNull()
                if (n == null || n !in 2..SceneValidator.MAX_POINTS) input.error = "2~${SceneValidator.MAX_POINTS} 사이 정수" else divide(n)
            }
        }
    }

    /** Position rules replace only another rule for this same point and segment, not other relations. */
    private fun setPointLocation(condition: GeometryConstraint) {
        val types = setOf(ConstraintType.POINT_FRACTION, ConstraintType.POINT_DISTANCE, ConstraintType.POINT_ON_SEGMENT, ConstraintType.POINT_ON_LINE)
        val previous = scene.constraints.firstOrNull { it.id == condition.id }
            ?: scene.constraints.filter { it.entityIds == condition.entityIds && it.type in types }
                .maxByOrNull { if (it.type in setOf(ConstraintType.POINT_FRACTION, ConstraintType.POINT_DISTANCE)) 1 else 0 }
        val base = scene.copy(constraints = scene.constraints.filterNot { it.entityIds == condition.entityIds && it.type in types })
        val updated = condition.copy(id = previous?.id ?: condition.id, enabled = previous?.enabled ?: condition.enabled)
        if (selectedCondition == condition.id) { selectedCondition = updated.id; canvas.selectedConstraintId = updated.id }
        edit(base.copy(constraints = base.constraints + updated))
    }

    private fun LinearLayout.addFractionControls(pointId: String, segmentId: String, existing: GeometryConstraint? = null) {
        val k = integerInput(existing?.numerator ?: 1, "등분 위치 k")
        val n = integerInput(existing?.denominator ?: 2, "등분 수 n")
        val line = scene.segment(segmentId) ?: return
        info("${name(line.startPointId)} → ${name(line.endPointId)} 방향 · n등분 중 k번째")
        addView(LinearLayout(context).apply {
            addView(k, LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(TextView(context).apply { text = " / "; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(25), dp(38)))
            addView(n, LinearLayout.LayoutParams(0, dp(38), 1f))
        })
        action("등분 위치 적용", ConstructionIcon.DIVIDE) {
            val numerator = k.text.toString().toIntOrNull(); val denominator = n.text.toString().toIntOrNull()
            if (numerator == null || denominator == null || denominator !in 2..SceneValidator.MAX_DIVISIONS || numerator !in 1 until denominator) {
                n.error = "n은 2 이상, k는 1~(n−1) 정수"
            } else {
                hideKeyboard()
                val id = existing?.id ?: ConstructionEdits.id()
                if (panelKind != PanelKind.CONDITIONS) { selectedCondition = id; canvas.selectedConstraintId = id }
                setPointLocation(GeometryConstraint(id, ConstraintType.POINT_FRACTION,
                    listOf(pointId, segmentId), enabled = existing?.enabled ?: true, numerator = numerator, denominator = denominator))
            }
        }
    }

    private fun showPointLocation(pointId: String, segmentId: String, existing: GeometryConstraint? = null) {
        val line = scene.segment(segmentId) ?: return
        selectedCondition = null; canvas.selectedMeasurementId = null
        val fromEnd = CheckBox(context).apply { text = "${name(line.endPointId)}부터 재기 (기본: ${name(line.startPointId)})"; isChecked = existing?.fromEnd ?: false; textSize = 11f }
        val extension = CheckBox(context).apply { text = "반대 끝점을 넘어 연장 허용"; isChecked = existing?.allowExtension ?: false; textSize = 11f }
        val saved = existing ?: scene.constraints.firstOrNull { it.entityIds == listOf(pointId, segmentId) && it.type == ConstraintType.POINT_FRACTION }
        showPanel("${name(pointId)} · ${name(segmentId)} 위 위치", PanelKind.DETAIL) {
            info("등분 비율과 끝점부터 cm는 하나만 사용합니다. 적용하면 이 선분에 대한 이전 위치 조건을 바꿉니다.")
            action("선분 위에서 자유롭게 이동") {
                closePanel(); setPointLocation(GeometryConstraint(existing?.id ?: ConstructionEdits.id(), ConstraintType.POINT_ON_SEGMENT, listOf(pointId, segmentId), enabled = existing?.enabled ?: true))
            }
            for ((k, n) in listOf(1 to 2, 1 to 3, 2 to 3)) action(if (n == 2) "중점 · 1/2" else "3등분 · $k/3", ConstructionIcon.DIVIDE) {
                closePanel(); setPointLocation(GeometryConstraint(existing?.id ?: ConstructionEdits.id(), ConstraintType.POINT_FRACTION,
                    listOf(pointId, segmentId), enabled = existing?.enabled ?: true, numerator = k, denominator = n))
            }
            addFractionControls(pointId, segmentId, saved)
            action("내분비 m:n으로 지정") { showPointRatio(pointId, segmentId, existing) }
            addView(fromEnd); addView(extension)
            action("선택한 끝점부터 거리 (cm)", ConstructionIcon.MEASURE) {
                val reverse = fromEnd.isChecked; val extend = extension.isChecked
                numberInput("끝점부터 거리", existing?.value ?: 1.0, allowZero = true,
                    description = "${name(if (reverse) line.endPointId else line.startPointId)}부터 반대 끝점 방향 · ${if (extend) "연장 허용" else "선분 안쪽"}") { value ->
                    setPointLocation(GeometryConstraint(existing?.id ?: ConstructionEdits.id(), ConstraintType.POINT_DISTANCE,
                        listOf(pointId, segmentId), value = value, enabled = existing?.enabled ?: true, fromEnd = reverse, allowExtension = extend))
                }
            }
        }
    }

    private fun showPointRatio(pointId: String, segmentId: String, existing: GeometryConstraint?) {
        val line = scene.segment(segmentId) ?: return
        val m = integerInput(1, "내분비 m"); val n = integerInput(2, "내분비 n")
        showPanel("내분비 m:n", PanelKind.DETAIL) {
            info("${name(line.startPointId)}${name(pointId)} : ${name(pointId)}${name(line.endPointId)} = m:n\n1:2이면 시작점에서 1/3 위치입니다.")
            addView(m); addView(n)
            action("내분비 적용") {
                val a = m.text.toString().toIntOrNull(); val b = n.text.toString().toIntOrNull()
                if (a == null || b == null || a <= 0 || b <= 0 || a.toLong() + b > SceneValidator.MAX_DIVISIONS) n.error = "양의 정수, 합계 ${SceneValidator.MAX_DIVISIONS} 이하"
                else { closePanel(); setPointLocation(GeometryConstraint(existing?.id ?: ConstructionEdits.id(), ConstraintType.POINT_FRACTION,
                    listOf(pointId, segmentId), enabled = existing?.enabled ?: true, numerator = a, denominator = a + b)) }
            }
        }
    }

    private fun numberInput(title: String, initial: Double, angle: Boolean = false, allowZero: Boolean = false, description: String? = null, apply: (Double) -> Unit) {
        val token = generation
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatGeometry(initial)); selectAll(); setSingleLine(); contentDescription = "작도 치수 값"
        }
        panel.translationX = 0f; panel.translationY = 0f
        selectedCondition = null; canvas.selectedMeasurementId = null
        showPanel(title, PanelKind.DETAIL) {
            info(description ?: if (angle) "각도 · 0~180° (꼭짓점을 확인하세요)" else "조건 값 · cm (화면 확대와 무관)")
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

    private fun conditionUnit(c: GeometryConstraint) = when (c.type) {
        ConstraintType.ANGLE, ConstraintType.INTERIOR_ANGLE -> "°"
        ConstraintType.LENGTH_RATIO -> "배"
        else -> "cm"
    }
    private fun conditionLabel(c: GeometryConstraint): String = "${c.type.koreanName()} · ${c.entityIds.joinToString { name(it) }}${c.value?.let { " = ${formatGeometry(it)} ${conditionUnit(c)}" } ?: ""}" +
        if (c.type == ConstraintType.POINT_FRACTION) " · ${c.numerator}/${c.denominator}" else ""
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
                            if (selectedCondition != null) canvas.selectedIds = emptySet()
                            showConditions(); updateHint()
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

    /** The same controls are embedded in a list row or in the fixed top-right inspector. */
    private fun LinearLayout.addConditionControls(c: GeometryConstraint) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; tag = "condition-controls-${c.id}"
            setPadding(dp(5), 0, dp(5), dp(3))
            val numericValue = c.value
            if (numericValue != null) {
                val token = generation
                val angle = c.type in setOf(ConstraintType.ANGLE, ConstraintType.INTERIOR_ANGLE)
                val unit = conditionUnit(c)
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
                            val minimum = if (angle || c.type in setOf(ConstraintType.DISTANCE_POINT_LINE, ConstraintType.POINT_DISTANCE)) 0.0 else SceneValidator.MIN_LENGTH
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
                            val minimum = if (angle || c.type in setOf(ConstraintType.DISTANCE_POINT_LINE, ConstraintType.POINT_DISTANCE)) 0.0 else SceneValidator.MIN_LENGTH
                            val maximum = if (angle) 180.0 else SceneValidator.MAX_MAGNITUDE
                            if (value == null || !value.isFinite() || value !in minimum..maximum) {
                                input.error = if (angle) "0~180°를 입력하세요" else "유효한 길이를 입력하세요"
                            } else { hideKeyboard(); changeConditionValue(c.id, value) }
                        }
                    }.apply { tag = "condition-apply-${c.id}" }, LinearLayout.LayoutParams(0, dp(32), 1.3f))
                })
                if (c.type == ConstraintType.RADIUS) info("반지름 기준 · 둘레 ${formatGeometry(2 * Math.PI * numericValue)} cm")
                if (angle) info(if (c.type == ConstraintType.INTERIOR_ANGLE) "꼭짓점 ${name(c.entityIds[1])} · 0~180°" else "시작→끝 방향 사이 각도 · 0~180°")
                if (c.type == ConstraintType.LENGTH_RATIO) info("${name(c.entityIds[0])} = ${formatGeometry(numericValue)} × ${name(c.entityIds[1])}")
                if (c.type == ConstraintType.POINT_DISTANCE) {
                    val line = scene.segment(c.entityIds[1])!!
                    info("${name(if (c.fromEnd) line.endPointId else line.startPointId)}부터 반대 끝점 방향 · ${if (c.allowExtension) "연장 허용" else "선분 안쪽"}")
                    addView(CheckBox(context).apply {
                        tag = "condition-from-end-${c.id}"; text = "${name(line.endPointId)}부터 재기 (기본: ${name(line.startPointId)})"
                        textSize = 11f; isChecked = c.fromEnd
                        setOnClickListener {
                            if (isCurrent(token)) edit(scene.copy(constraints = scene.constraints.map { if (it.id == c.id) it.copy(fromEnd = isChecked) else it }))
                            else isChecked = scene.constraints.firstOrNull { it.id == c.id }?.fromEnd ?: false
                        }
                    })
                    addView(CheckBox(context).apply {
                        tag = "condition-extension-${c.id}"; text = "반대 끝점을 넘어 연장 허용"; textSize = 11f; isChecked = c.allowExtension
                        setOnClickListener {
                            if (isCurrent(token)) edit(scene.copy(constraints = scene.constraints.map { if (it.id == c.id) it.copy(allowExtension = isChecked) else it }))
                            else isChecked = scene.constraints.firstOrNull { it.id == c.id }?.allowExtension ?: false
                        }
                    })
                }
                if (!c.enabled) info("잠시 꺼짐 · 설정 값만 변경됩니다. 체크하면 다시 적용합니다.")
            } else info(if (c.enabled) "체크를 해제하면 관계를 잠시 풉니다." else "잠시 꺼짐 · 체크하면 다시 적용합니다.")
            if (c.type == ConstraintType.POINT_FRACTION) addFractionControls(c.entityIds[0], c.entityIds[1], c)
            if (c.type == ConstraintType.INTERIOR_ANGLE) action("이 각과 다른 각을 같게…", ConstructionIcon.ANGLE) { chooseEqualAngle(c.entityIds) }
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
        // Relation emphasis is not a destructive entity selection. An explicit target-selection
        // action below is required before deleting any of the associated geometry.
        canvas.selectedIds = emptySet()
        selectedCondition = id; canvas.selectedConstraintId = id; canvas.selectedMeasurementId = null
        showPanel("조건 · ${c.type.koreanName()}", PanelKind.DETAIL) {
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(conditionCheckBox(c), LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(TextView(context).apply {
                    text = conditionLabel(c); textSize = 11f; setTextColor(Color.rgb(80,91,83))
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }, LinearLayout.LayoutParams(-1, dp(48)))
            addConditionControls(c)
            action("대상 도형 선택", ConstructionIcon.SELECT, "condition-select-entities-$id") { selectAnnotationEntities(id, null) }
            action("조건 목록으로") { showConditions() }
        }
        updateHint()
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
        canvas.selectedIds = emptySet()
        selectedCondition = null; canvas.selectedConstraintId = null; canvas.selectedMeasurementId = id
        val value = ConstructionMeasurementGeometry.layout(scene, m)?.value
        val existing = scene.constraints.firstOrNull { ConstructionMeasurementGeometry.matchesConstraint(scene, m, it) }
        showPanel("측정 · 값 확인 / 조건 부여", PanelKind.DETAIL) {
            info(measurementLabel(m)); info("측정은 따라 변하는 값입니다. 고정하면 도형이 그 값을 유지합니다.")
            if (m.type != MeasurementType.AREA && value != null && value.isFinite()) {
                if (existing != null) {
                    action(if (existing.enabled) "고정 조건 보기" else "꺼진 조건 보기", ConstructionIcon.CONSTRAINT,
                        "measurement-existing-condition-$id") { showConditionDetails(existing.id) }
                } else {
                    action("현재 값으로 고정", ConstructionIcon.CONSTRAINT, "measurement-fix-current-$id") { fixMeasurement(id) }
                    action("값 입력 후 고정", ConstructionIcon.MEASURE, "measurement-fix-value-$id") {
                        numberInput("측정값 고정", value, angle = m.type == MeasurementType.ANGLE,
                            description = if (m.type == MeasurementType.ANGLE) "∠${m.entityIds.joinToString("") { name(it) }} · 가운데 점이 꼭짓점입니다" else "${m.entityIds.joinToString("~") { name(it) }} · cm") { entered -> fixMeasurement(id, entered) }
                    }
                }
            }
            if (m.type in setOf(MeasurementType.ANGLE, MeasurementType.DISTANCE)) {
                val first = scene.measurements.firstOrNull { it.id == pendingEqualMeasurement }
                if (first != null && first.id != id && first.type == m.type) {
                    info("기준: ${measurementLabel(first)}")
                    action("기준 측정과 같게", ConstructionIcon.EQUAL, "measurement-equal-apply-$id") { applyMeasurementEquality(first.id, id) }
                } else if (first != null) {
                    info(if (first.id == id) "이 표시가 기준입니다. 다른 ${if (m.type == MeasurementType.ANGLE) "각" else "길이"} 측정 표시를 누르세요." else "기준과 같은 종류의 측정 표시를 선택하세요.")
                }
                action(if (first == null) "다른 측정과 같게…" else "이 측정을 새 기준으로", ConstructionIcon.EQUAL, "measurement-equal-start-$id") { beginMeasurementEquality(id) }
            }
            if (m.type == MeasurementType.ANGLE && pendingEqualAngle != null) {
                action("기준 각과 이 각을 같게", ConstructionIcon.EQUAL) { chooseEqualAngle(m.entityIds) }
            }
            if (pendingEqualMeasurement != null) action("같은 측정 선택 취소", actionTag = "measurement-equal-cancel") {
                clearMeasurementReference(); updateHint(); showMeasurementDetails(id)
            }
            action("대상 도형 선택", ConstructionIcon.SELECT, "measurement-select-entities-$id") { selectAnnotationEntities(null, id) }
            action("표시 위치 초기화") { presentationEdit(scene.copy(measurements = scene.measurements.map { if (it.id == id) it.copy(offsetX = 0.0, offsetY = 0.0) else it })) }
            action("표시 지우기", ConstructionIcon.DELETE) { closePanel(); presentationEdit(scene.copy(measurements = scene.measurements.filterNot { it.id == id })) }
            action("측정으로") { showMeasurement() }
        }
        updateHint()
    }

    private fun clearMeasurementReference() {
        pendingEqualMeasurement = null
        canvas.referenceMeasurementId = null
    }

    private fun beginMeasurementEquality(id: String) {
        val measurement = scene.measurements.firstOrNull { it.id == id } ?: return
        if (measurement.type !in setOf(MeasurementType.ANGLE, MeasurementType.DISTANCE)) return
        pendingEqualAngle = null
        pendingEqualMeasurement = id
        canvas.referenceMeasurementId = id
        closePanel(); canvas.tool = ConstructionTool.SELECT; canvas.clearSelection(); updateHint()
    }

    private fun fixMeasurement(id: String, value: Double? = null) {
        runCatching { ConstructionEdits.constrainMeasurement(scene, id, value) }
            .onSuccess { next -> showNewMeasurementCondition(next) }
            .onFailure { notice(it.message ?: "이 측정값에는 고정 조건을 만들 수 없습니다.") }
    }

    private fun applyMeasurementEquality(firstId: String, secondId: String) {
        val existing = runCatching { ConstructionEdits.matchingEqualityConstraint(scene, firstId, secondId) }.getOrNull()
        if (existing != null) {
            pendingEqualAngle = null; clearMeasurementReference(); updateHint(); showConditionDetails(existing.id)
            return
        }
        runCatching { ConstructionEdits.equalMeasurements(scene, firstId, secondId) }
            .onSuccess { next -> showNewMeasurementCondition(next) }
            .onFailure { notice(it.message ?: "서로 다른 같은 종류의 측정을 선택하세요.") }
    }

    private fun showNewMeasurementCondition(next: ConstructionScene) {
        val condition = next.constraints.firstOrNull { candidate -> scene.constraints.none { it == candidate } }
        pendingEqualAngle = null; clearMeasurementReference(); updateHint()
        if (condition == null) { closePanel(); return notice("같은 관계가 이미 있습니다. 조건 목록에서 켜짐 상태를 확인하세요.") }
        // Keep the same fixed inspector after the atomic solve/save. If solving fails, the
        // previous durable scene stays intact and the nonexistent new detail is dismissed.
        selectedCondition = condition.id; canvas.selectedConstraintId = condition.id; canvas.selectedMeasurementId = null
        panelKind = PanelKind.DETAIL; detailSelection = canvas.selectedIds.toSet()
        edit(next)
    }
    private fun renamePoint() {
        val token = generation
        val p = selectedPoints().singleOrNull()?.takeIf { canvas.selectedIds.size == 1 } ?: return notice("이름을 바꿀 점 하나를 선택하세요.")
        val input = EditText(context).apply { setText(p.label); setSingleLine(); selectAll(); filters = arrayOf(android.text.InputFilter.LengthFilter(12)) }
        panel.translationX = 0f; panel.translationY = 0f
        selectedCondition = null; canvas.selectedMeasurementId = null
        showPanel("점 이름", PanelKind.DETAIL) {
            addView(input, LinearLayout.LayoutParams(-1, dp(42)))
            action("적용") { if (isCurrent(token)) { closePanel(); presentationEdit(scene.copy(points = scene.points.map { if (it.id == p.id) it.copy(label = input.text.toString().trim()) else it })) } }
        }
    }
    private fun deleteSelection() {
        val token = generation
        val selected = canvas.selectedIds.intersect(allIds())
        if (selected.isEmpty()) return notice("삭제할 점·선·원을 선택하세요.")
        val next = ConstructionEdits.remove(scene, selected)
        val errors = SceneValidator.validate(next)
        if (errors.isNotEmpty()) return notice("연결 상태를 확인하지 못해 삭제하지 않았습니다: ${errors.first()}")
        val count = scene.points.size + scene.segments.size + scene.circles.size - next.points.size - next.segments.size - next.circles.size
        val constraints = scene.constraints.size - next.constraints.size
        val measurements = scene.measurements.size - next.measurements.size
        AlertDialog.Builder(context).setTitle("선택한 도형 삭제")
            .setMessage("선택: ${selected.joinToString { name(it) }}\n연결된 도형을 포함해 ${count}개, 관련 조건 ${constraints}개, 관련 측정 표시 ${measurements}개를 삭제합니다.\n손필기는 지우지 않습니다. 되돌리기로 복구할 수 있습니다.")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                if (!isCurrent(token) || canvas.selectedIds.intersect(allIds()) != selected) {
                    notice("삭제 대상이 바뀌었습니다. 대상을 다시 선택해 주세요.")
                } else {
                    pendingEqualAngle = null; clearMeasurementReference(); closePanel()
                    // Removing entities only removes their dependent conditions. Preserve the
                    // coordinates of all surviving objects rather than rerunning a shape solve.
                    presentationEdit(next)
                }
            }.showChild()
    }

    /** Explicitly promote annotation emphasis into an entity selection. A highlighted segment
     * selects that segment, not its endpoints (which would also delete unrelated adjoining lines). */
    private fun selectAnnotationEntities(constraintId: String?, measurementId: String?) {
        val targets = ConstructionMeasurementGeometry.annotationTargets(scene, constraintId, measurementId).entityIds
        val shapes = targets.filterTo(linkedSetOf()) { scene.segment(it) != null || scene.circle(it) != null }
        val shapePoints = shapes.flatMapTo(hashSetOf()) { id ->
            scene.segment(id)?.let { listOf(it.startPointId, it.endPointId) }
                ?: scene.circle(id)?.let { listOf(it.centerPointId) }.orEmpty()
        }
        val selection = shapes + targets.filter { scene.point(it) != null && it !in shapePoints }
        if (selection.isEmpty()) return notice("선택할 도형이 없습니다.")
        pendingEqualAngle = null; clearMeasurementReference()
        canvas.tool = ConstructionTool.SELECT; closePanel()
        canvas.selectedIds = selection; updateSelection()
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
            .setMessage("1. 눌린 도구와 왼쪽 위 안내가 다음 동작입니다. 선분·원은 두 번 눌러 만듭니다. 선택한 도형은 색·선종류 버튼으로 바뀝니다.\n2. □ 기본 도형은 점·선분과 관계로 추가됩니다. 길이는 고정되지 않으므로 필요할 때 치수를 넣으세요.\n3. 점과 선분 선택 → 조건 추가 → 선분 위 위치. 중점·k/n·내분비·끝점부터 cm를 지정합니다. 같은 점/선분의 이전 위치 조건을 바꾸며, 꺼진 조건은 체크해야 적용됩니다.\n4. 선분 하나 → 등분점 만들기. 원래 선분은 그대로입니다. 선분 여러 개 → 같은 길이, 두 선분 → 길이 비율을 지정합니다.\n5. 각도는 세 점(끝→꼭짓점→끝) 또는 꼭짓점을 공유하는 두 선분을 선택하세요. ‘이 각과 다른 각을 같게’ → 다른 각 선택 → 조건 추가로 묶습니다. 측정된 각 표시를 눌러서도 연결할 수 있습니다. 각 이등분은 두 작은 각을 같게 구성하세요.\n6. 길이·반지름·각도 표시를 누르면 오른쪽 위 고정 메뉴에서 ±로 바꿉니다. 조건 목록은 항목 아래로 펼쳐지며 체크로 잠시 끄고 켭니다. 측정은 모양을 고정하지 않으며 글자를 끌어 옮길 수 있습니다. 측정 표시를 누르면 현재 값 또는 입력값으로 고정하거나 다른 길이·각과 같게 묶습니다. 고정·측정·꺼짐 글자로 구분하며 조건 아이콘을 누르면 관련 대상이 함께 강조됩니다.\n7. 조절 메뉴는 오른쪽 위에서 같은 크기를 유지합니다. 긴 내용은 메뉴 안에서 스크롤하세요. 열고 닫아도 도형은 밀리지 않습니다. 두 손가락으로 도형과 필기를 함께 확대·이동합니다.\n\n자석은 끝점·선분 안쪽·두 선분의 교점에 자동 연결합니다. 명시적으로 만드는 직선 위 조건·수선·직선 교점은 연장선을 포함합니다. 원과 선·두 원의 교점 자동 연결은 아직 지원하지 않습니다.\n\n${if (embedded) "필기와 도형은 같은 평면이지만 지우기·되돌리기는 서로 영향을 주지 않습니다. 학생 도형은 저장 후 자동 전송되고 선생 도형은 발행해야 전송됩니다. 새 관계와 종이 바깥 확장 필기를 동기화하려면 두 기기를 모두 업데이트하세요. 기존 필기 좌표는 바뀌지 않습니다." else "현재 기기에 자동 저장되고 앱 백업에 포함됩니다. 메모 안의 도형만 원격 동기화 대상입니다."}")
            .setPositiveButton("확인", null).showChild()
    }
    private fun requestClose() {
        if (busy || dragSolving) return notice("저장을 마친 뒤 닫을 수 있습니다.")
        canvas.cancelDrag(); onRequestClose()
    }
    fun handleBack() { if (panelKind != null) closePanel() else if (pendingEqualAngle != null || pendingEqualMeasurement != null) { pendingEqualAngle = null; clearMeasurementReference(); updateHint() } else requestClose() }
    fun undoEdit(): Boolean = if (canUndo) { history(true); true } else false
    fun redoEdit(): Boolean = if (canRedo) { history(false); true } else false
    fun cancelInteraction() { pendingEqualAngle = null; clearMeasurementReference(); canvas.cancelDrag(); canvas.tool = canvas.tool; dismissChildren(); updateHint() }

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
    fun publishMemo() = requestPublication()
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
        val labels = arrayOf("선생 노트·도형으로 학생 맞추기", "학생 노트·도형으로 선생 맞추기")
        AlertDialog.Builder(context).setTitle("노트 또는 도형에 변경이 있습니다")
            .setMessage("어느 내용을 사용할지 선택하세요. 선생이 필기를 수정한 노트는 그 필기도 함께 반영됩니다. 다른 노트는 변경하지 않습니다.")
            .setPositiveButton(labels[0]) { _, _ -> confirmConflict(bridge, expectedToken, token, ConstructionConflictChoice.USE_TEACHER, labels[0]) }
            .setNeutralButton(labels[1]) { _, _ -> confirmConflict(bridge, expectedToken, token, ConstructionConflictChoice.USE_STUDENT, labels[1]) }
            .setNegativeButton("취소", null).showChild()
    }
    private fun confirmConflict(bridge: ConstructionUiBridge, expectedToken: String, token: Long, choice: ConstructionConflictChoice, label: String) {
        if (!isCurrent(token) || bridge.state(target).conflictToken != expectedToken) {
            notice("비교하는 동안 도형이 바뀌었습니다. 최신 도형을 다시 확인해 주세요."); return
        }
        AlertDialog.Builder(context).setTitle(label).setMessage("선택한 노트·도형으로 맞출까요? 비교 후 상대 내용이 다시 바뀌면 덮어쓰지 않고 재확인합니다.")
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
