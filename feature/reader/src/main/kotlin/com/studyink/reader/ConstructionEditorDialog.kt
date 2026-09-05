package com.studyink.reader

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintSolver
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.DragTarget
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.SolveResult
import com.studyink.construction.storage.ConstructionSceneSnapshot
import com.studyink.construction.storage.ConstructionSceneStore
import com.studyink.construction.storage.ConstructionTarget
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Local mathematical memo editor. All hard solving and durable writes run on one worker.
 * A complete drag is one persisted/undoable command; stale preview results cannot cross targets.
 */
internal class ConstructionEditorDialog(
    context: Context,
    private val target: ConstructionTarget,
    private val titleText: String,
) : Dialog(context, android.R.style.Theme_Material_Light_NoActionBar) {
    private val store = ConstructionSceneStore(File(context.applicationContext.filesDir, "masternote"))
    private val solver = ConstraintSolver()
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "construction-editor").apply { isDaemon = true } }
    private val density = context.resources.displayMetrics.density
    private val canvas = ConstructionCanvasView(context)
    private val status = TextView(context)
    private val selectionInfo = TextView(context)
    private val actionButtons = mutableListOf<Button>()
    private var closeButton: Button? = null
    private var snapshot: ConstructionSceneSnapshot? = null
    private var scene = ConstructionScene()
    private val undo = ArrayDeque<ConstructionScene>()
    private val redo = ArrayDeque<ConstructionScene>()
    private var restoreListener: AutoCloseable? = null
    private var busy = true
    private var generation = 0L
    private var closed = false
    private var dragBase: ConstructionScene? = null
    private var pendingDrag: Pair<DragTarget, Boolean>? = null
    private var dragSolving = false
    private var dragRequest = 0L
    private var fitAfterCommit = false
    private val childDialogs = mutableSetOf<AlertDialog>()

    private fun isCurrent(token: Long) = token == generation && !closed && !busy && dragBase == null
    private fun showChild(dialog: AlertDialog): AlertDialog {
        childDialogs += dialog
        dialog.setOnDismissListener { childDialogs -= dialog }
        dialog.show()
        return dialog
    }
    private fun AlertDialog.Builder.showChild() = showChild(create())
    private fun dismissChildren() { childDialogs.toList().forEach { it.dismiss() }; childDialogs.clear() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCanceledOnTouchOutside(false)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.rgb(255, 254, 249))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = "작도 메모 · $titleText"
            textSize = 16f; setTextColor(Color.rgb(33, 47, 66)); setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        closeButton = button("닫기", register = false) { requestClose() }
        header.addView(closeButton)
        root.addView(header)
        root.addView(TextView(context).apply {
            text = "이 기기에 자동 저장 · 앱 백업 포함 · 원격 자동 전송은 지원하지 않음"
            textSize = 11f; setTextColor(Color.rgb(85, 92, 98)); setPadding(dp(8), 0, dp(8), dp(5))
        })
        root.addView(toolbar {
            addView(button("선택") { chooseTool(ConstructionTool.SELECT) })
            addView(button("점") { chooseTool(ConstructionTool.POINT) })
            addView(button("선분") { chooseTool(ConstructionTool.SEGMENT) })
            addView(button("원") { chooseTool(ConstructionTool.CIRCLE) })
            addView(button("맞춤") { canvas.fitScene() })
            addView(button("−") { canvas.zoom(.8f) })
            addView(button("+") { canvas.zoom(1.25f) })
        })
        root.addView(toolbar {
            addView(button("조건 추가") { showRelationMenu() })
            addView(button("조건 목록") { showConditions() })
            addView(button("측정") { showMeasurement() })
            addView(button("이름") { renamePoint() })
            addView(button("삭제") { deleteSelection() })
            addView(button("되돌리기") { history(backward = true) })
            addView(button("다시") { history(backward = false) })
            addView(button("예제") { showExamples() })
            addView(button("도움") { showHelp() })
        })
        selectionInfo.apply { textSize = 12f; setTextColor(Color.rgb(37, 86, 133)); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        root.addView(selectionInfo)
        root.addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status.apply { textSize = 12f; setTextColor(Color.rgb(64, 77, 70)); setPadding(dp(8), dp(8), dp(8), dp(3)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        setContentView(root)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        canvas.onSelectionChanged = { updateSelection() }
        canvas.onPoint = { p -> edit(ConstructionEdits.addPoint(scene, p.x, p.y)) }
        canvas.onSegment = { a, b -> runCatching { ConstructionEdits.addSegment(scene, a, b) }.onSuccess { edit(it) }.onFailure { notice(it.message.orEmpty()) } }
        canvas.onCircle = { center, radius -> edit(ConstructionEdits.addCircle(scene, center, radius)) }
        canvas.onDragPoint =(::onDrag)
        restoreListener = store.addRestoreListener {
            canvas.post {
                if (!closed) {
                    generation++; dragBase = null; pendingDrag = null; dragSolving = false
                    undo.clear(); redo.clear(); canvas.cancelDrag(); load()
                }
            }
        }
        load()
    }

    private fun dp(value: Int) = (value * density).toInt()
    private fun button(label: String, register: Boolean = true, action: () -> Unit): Button = Button(context).apply {
        text = label; textSize = 12f; isAllCaps = false; minWidth = dp(56); minimumHeight = dp(44)
        setPadding(dp(8), 0, dp(8), 0); contentDescription = "작도 $label"
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
        setOnClickListener { if (!busy && dragBase == null) action() else notice("계산 또는 저장을 마친 뒤 다시 눌러 주세요.") }
        if (register) actionButtons += this
    }
    private fun toolbar(build: LinearLayout.() -> Unit) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; build() })
    }
    private fun notice(message: String) { status.text = message; Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    private fun setBusy(value: Boolean) {
        busy = value
        actionButtons.forEach { it.isEnabled = !value && dragBase == null }
        closeButton?.isEnabled = !value
        canvas.editable = !value
    }
    private fun chooseTool(tool: ConstructionTool) {
        canvas.tool = tool; canvas.clearSelection()
        status.text = when (tool) {
            ConstructionTool.SELECT -> "대상을 눌러 함께 선택 · 점을 끌면 조건을 유지합니다 · 빈 곳을 끌면 화면 이동"
            ConstructionTool.POINT -> "원하는 위치를 눌러 점을 만드세요. 겹친 점도 별개로 생성됩니다."
            ConstructionTool.SEGMENT -> "시작점과 끝점을 차례로 누르세요. 기존 점을 누르면 그 점에 연결됩니다."
            ConstructionTool.CIRCLE -> "중심과 원 위의 위치를 차례로 누르세요. 반지름은 조건 추가에서 정합니다."
        }
    }
    private fun load() {
        dismissChildren()
        canvas.tool = canvas.tool // Discard an unfinished two-tap construction from the old scene.
        val token = ++generation
        setBusy(true); status.text = "작도 메모 불러오는 중…"
        worker.execute {
            val result = runCatching { store.load(target) }
            canvas.post {
                if (closed || generation != token) return@post
                result.onSuccess { loaded ->
                    snapshot = loaded; scene = loaded.scene; canvas.scene = scene
                    canvas.clearSelection(); canvas.fitScene(); setBusy(false)
                    status.text = "선분·원을 그린 뒤 대상을 선택해 조건을 추가하세요. 예제로 시작할 수도 있습니다."
                }.onFailure {
                    status.text = "저장된 작도를 읽지 못했습니다. 기존 파일은 보존됩니다: ${it.message}"
                    closeButton?.isEnabled = true
                    closeButton?.setOnClickListener { dismiss() }
                }
            }
        }
    }

    private fun edit(candidate: ConstructionScene) {
        if (busy || dragBase != null || closed) return
        val token = ++generation; setBusy(true); status.text = "조건 확인 중…"
        worker.execute {
            val result = runCatching { solver.solve(candidate) }
            canvas.post {
                if (closed || generation != token) return@post
                result.onSuccess { solved ->
                    if (solved.success) persist(solved.scene, solved = solved)
                    else { fitAfterCommit = false; setBusy(false); showSolveFailure(solved) }
                }.onFailure { fitAfterCommit = false; setBusy(false); notice("계산하지 못했습니다: ${it.message}") }
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
            canvas.post {
                if (closed || generation != token) return@post
                result.onSuccess { committed ->
                    when (historyDirection) {
                        -1 -> { undo.removeLastOrNull(); redo.addLast(before) }
                        1 -> { redo.removeLastOrNull(); undo.addLast(before) }
                        else -> if (before != next) { undo.addLast(before); redo.clear() }
                    }
                    while (undo.size > 80) undo.removeFirst()
                    snapshot = committed; scene = committed.scene; canvas.scene = scene
                    if (fitAfterCommit) { fitAfterCommit = false; canvas.fitScene() }
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
                        scene = expected.scene; canvas.scene = scene; setBusy(false)
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
            canvas.post {
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
                    if (scene != before) persist(scene, solved = result.getOrNull()) else { setBusy(false); status.text = "조건 때문에 더 이동할 수 없습니다. 조건 목록에서 값을 바꿀 수 있습니다." }
                } else runPendingDrag()
            }
        }
    }

    private fun history(backward: Boolean) {
        val next = if (backward) undo.lastOrNull() else redo.lastOrNull()
        if (next == null) return notice(if (backward) "되돌릴 작업이 없습니다." else "다시 실행할 작업이 없습니다.")
        persist(next, historyDirection = if (backward) -1 else 1)
    }
    private fun selectedPoints() = scene.points.filter { it.id in canvas.selectedIds }
    private fun selectedSegments() = scene.segments.filter { it.id in canvas.selectedIds }
    private fun selectedCircles() = scene.circles.filter { it.id in canvas.selectedIds }
    private fun name(id: String): String = scene.points.firstOrNull { it.id == id }?.label?.ifBlank { "점" }
        ?: scene.segments.firstOrNull { it.id == id }?.let { "${name(it.startPointId)}${name(it.endPointId)}" }
        ?: scene.circles.firstOrNull { it.id == id }?.let { "원(${name(it.centerPointId)})" } ?: "대상"
    private fun updateSelection() {
        selectionInfo.text = if (canvas.selectedIds.isEmpty()) "선택: 없음 · 여러 대상을 차례로 눌러 관계를 지정합니다."
        else "선택: ${canvas.selectedIds.joinToString { name(it) }}"
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
        if (actions.isEmpty()) return notice("점·선·원을 한 개 또는 두 개 선택하세요. 예: 점 두 개 → 일치, 선분 한 개 → 길이")
        AlertDialog.Builder(context).setTitle("선택한 대상의 조건")
            .setItems(actions.map { it.label }.toTypedArray()) { _, which -> if (isCurrent(token)) actions[which].run() }
            .setNegativeButton("취소", null).showChild()
    }

    private fun numberInput(title: String, initial: Double, angle: Boolean = false, allowZero: Boolean = false, apply: (Double) -> Unit) {
        val token = generation
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatGeometry(initial)); selectAll(); setSingleLine(); contentDescription = "작도 치수 값"
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(context).apply { text = if (angle) "선분을 만든 시작→끝 방향 사이의 각도입니다. (0~180°)" else "수학상의 길이입니다. 화면 확대와 관계없이 cm 단위를 유지합니다."; textSize = 12f })
            addView(input)
            addView(LinearLayout(context).apply {
                for (step in listOf(-1.0, -.1, .1, 1.0)) addView(Button(context).apply {
                    text = if (step > 0) "+${formatGeometry(step)}" else formatGeometry(step)
                    textSize = 12f; minWidth = 0; setPadding(0, 0, 0, 0)
                    setOnClickListener { input.setText(formatGeometry(((input.text.toString().toDoubleOrNull() ?: initial) + step).coerceAtLeast(if (angle || allowZero) 0.0 else .01))) }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            })
        }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(content).setNegativeButton("취소", null).setPositiveButton("적용", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().toDoubleOrNull()
                if (value == null || !value.isFinite() || value > 10000 || value < 0 || (!angle && !allowZero && value == 0.0) || (angle && value > 180)) {
                    input.error = if (angle) "0~180 사이 각도를 입력하세요." else "유효한 길이를 입력하세요."
                } else if (isCurrent(token)) { dialog.dismiss(); apply(value) }
            }
        }
        showChild(dialog)
    }

    private fun conditionLabel(c: GeometryConstraint): String = "${if (c.enabled) "●" else "○"} ${c.type.koreanName()} · ${c.entityIds.joinToString { name(it) }}${c.value?.let { " = ${formatGeometry(it)}${if (c.type == ConstraintType.ANGLE) "°" else " cm"}" } ?: ""}"
    private fun showConditions() {
        val token = generation
        if (scene.constraints.isEmpty()) return notice("아직 조건이 없습니다. 대상을 선택하고 조건 추가를 누르세요.")
        val listed = scene.constraints.toList()
        AlertDialog.Builder(context).setTitle("조건 목록 · 눌러서 값 변경 / 켜기 / 끄기")
            .setItems(listed.map(::conditionLabel).toTypedArray()) outer@ { _, which ->
                if (!isCurrent(token)) return@outer
                val c = listed[which]
                val options = buildList { if (c.value != null) add("값 바꾸기"); add(if (c.enabled) "조건 잠시 끄기" else "조건 다시 켜기"); add("조건 삭제") }
                AlertDialog.Builder(context).setTitle(conditionLabel(c)).setItems(options.toTypedArray()) inner@ { _, selected ->
                    if (!isCurrent(token)) return@inner
                    when (options[selected]) {
                        "값 바꾸기" -> numberInput(c.type.koreanName(), c.value!!, angle = c.type == ConstraintType.ANGLE, allowZero = c.type == ConstraintType.DISTANCE_POINT_LINE) { value ->
                            edit(scene.copy(constraints = scene.constraints.map { if (it.id == c.id) it.copy(value = value, enabled = true) else it }))
                        }
                        "조건 삭제" -> edit(scene.copy(constraints = scene.constraints.filterNot { it.id == c.id }))
                        else -> edit(scene.copy(constraints = scene.constraints.map { if (it.id == c.id) it.copy(enabled = !c.enabled) else it }))
                    }
                }.setNegativeButton("닫기", null).showChild()
            }.setNegativeButton("닫기", null).showChild()
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
        val values = mutableListOf<String>()
        s.forEach { values += "${name(it.id)} = ${formatGeometry(length(it))} cm" }
        c.forEach { values += "${name(it.id)} 반지름 ${formatGeometry(it.radius)} cm\n넓이 ${formatGeometry(Math.PI * it.radius * it.radius)} cm²" }
        if (p.size == 2) values += "${name(p[0].id)}~${name(p[1].id)} = ${formatGeometry(hypot(p[0].x - p[1].x, p[0].y - p[1].y))} cm"
        if (s.size == 2) values += "시작→끝 방향 사이 각도 = ${formatGeometry(angle(s[0], s[1]))}°"
        if (p.size == 3) {
            val area = kotlin.math.abs((p[1].x - p[0].x) * (p[2].y - p[0].y) - (p[1].y - p[0].y) * (p[2].x - p[0].x)) / 2
            values += "삼각형 ${p.joinToString { it.label }} 넓이 = ${formatGeometry(area)} cm²"
        }
        if (p.size == 1 && s.size == 1) values += "점~직선 수선 거리 = ${formatGeometry(distanceToLine(p[0], s[0]))} cm"
        if (values.isEmpty()) return notice("선분·원, 점 두 개, 또는 삼각형의 점 세 개를 선택하세요.")
        AlertDialog.Builder(context).setTitle("측정값 · 모양을 고정하지 않습니다")
            .setMessage(values.joinToString("\n\n")).setPositiveButton("확인", null).showChild()
    }
    private fun renamePoint() {
        val token = generation
        val p = selectedPoints().singleOrNull()?.takeIf { canvas.selectedIds.size == 1 } ?: return notice("이름을 바꿀 점 하나를 선택하세요.")
        val input = EditText(context).apply { setText(p.label); setSingleLine(); selectAll(); filters = arrayOf(android.text.InputFilter.LengthFilter(12)) }
        AlertDialog.Builder(context).setTitle("점 이름").setView(input).setNegativeButton("취소", null)
            .setPositiveButton("적용") { _, _ -> if (isCurrent(token)) edit(scene.copy(points = scene.points.map { if (it.id == p.id) it.copy(label = input.text.toString().trim()) else it })) }.showChild()
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
        val p = GeometryPoint(ConstructionEdits.id(), a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), ConstructionEdits.nextPointLabel(scene))
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
        val e = GeometryPoint(ConstructionEdits.id(), a.x + t * dx, a.y + t * dy, ConstructionEdits.nextPointLabel(scene))
        val perpendicular = GeometrySegment(ConstructionEdits.id(), p.id, e.id)
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
            .setMessage("1. 선분·원은 두 위치를 차례로 눌러 만듭니다.\n2. 선택 모드에서 여러 대상을 누르고 조건 추가를 누르세요.\n3. 점 두 개에 일치를 걸면 함께 움직입니다. 길이·각도는 지정한 조건만 유지합니다.\n4. 조건 목록에서 값을 바꾸거나 조건을 잠시 끌 수 있습니다.\n5. 손가락 두 개로 확대·이동합니다.\n\n빈 곳을 누르면 선택 해제. 겹친 점은 반복해서 누르면 따로 선택됩니다. 두 직선 각도는 각 선분의 시작→끝 방향 기준입니다. 수선·교점은 연장선을 포함합니다.\n\n작도는 현재 기기에 자동 저장되고 앱 백업에 포함됩니다. 기존 필기·메모와 원격 전송 데이터는 변경하지 않습니다.")
            .setPositiveButton("확인", null).showChild()
    }
    private fun requestClose() {
        if (busy || dragSolving) return notice("저장을 마친 뒤 닫을 수 있습니다.")
        canvas.cancelDrag(); dismiss()
    }
    @Deprecated("Android dialog back navigation")
    override fun onBackPressed() { requestClose() }
    override fun dismiss() {
        if (!closed) {
            closed = true; generation++; dragRequest++
            dismissChildren()
            restoreListener?.close(); restoreListener = null; worker.shutdown()
        }
        super.dismiss()
    }
}
