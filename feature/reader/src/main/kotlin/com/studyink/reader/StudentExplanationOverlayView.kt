package com.studyink.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.studyink.assistant.core.StudentExplanationCard
import com.studyink.assistant.core.StudentExplanationLayer
import com.studyink.assistant.core.StudentExplanationTarget
import com.studyink.core.model.PageBounds
import com.studyink.core.model.PagePoint
import com.studyink.document.pdf.PdfViewportAdapter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Read-only student overlay for one exact page/attempt explanation layer.
 *
 * The host owns persistence and transport. Call [showLayer] only with the layer loaded for the
 * current [StudentExplanationTarget], and call [notifyViewportChanged] from the reader's existing
 * viewport callback. Touches beginning outside a chip or expanded card deliberately return false,
 * so PDF navigation and ink keep receiving their normal gestures.
 */
class StudentExplanationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var viewportAdapter: PdfViewportAdapter? = null
        set(value) {
            field = value
            resetGeometry()
        }

    var onExpandedCardChanged: (cardId: String?) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(244, 250, 248, 240)
        setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(90, 0, 0, 0))
    }
    private val chipOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(180, 67, 91, 135)
    }
    private val chipTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 52, 68)
        textSize = sp(13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(250, 255, 253, 247)
        setShadowLayer(dp(8f), 0f, dp(3f), Color.argb(100, 0, 0, 0))
    }
    private val panelOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(175, 125, 118, 101)
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 40, 35)
        textSize = sp(17f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2f)
        color = Color.rgb(91, 88, 80)
    }
    private val bodyTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 52, 46)
        textSize = sp(15f)
    }
    private val overflowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(116, 106, 86)
        textSize = sp(12f)
    }

    private var expectedTarget: StudentExplanationTarget? = null
    private var cards: List<StudentExplanationCard> = emptyList()
    private var expandedCardId: String? = null
    private var contentBoundsOverride: RectF? = null

    private var chipPlacements: List<AssistantChipPlacement> = emptyList()
    private val anchorsByCardId = mutableMapOf<String, AssistantUiRect>()
    private var panelBounds: AssistantUiRect? = null
    private var closeBounds: AssistantUiRect? = null
    private var bodyBounds: AssistantUiRect? = null
    private var bodyLayout: StaticLayout? = null
    private var bodyLayoutWidth = -1
    private var bodyLayoutCardId: String? = null
    private var bodyWasTruncated = false
    private var scrollOffset = 0f
    private var maximumScroll = 0f

    private var gesture = Gesture.NONE
    private var gestureCardId: String? = null
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var gestureMoved = false

    init {
        visibility = GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "선생님 설명 카드"
    }

    /**
     * Binds only when [layer] belongs to [target]. A mismatch clears the previous page immediately,
     * preventing a late repository/transport result from appearing on a different attempt.
     */
    fun showLayer(
        target: StudentExplanationTarget,
        layer: StudentExplanationLayer?,
    ): Boolean {
        if (layer == null || layer.target != target) {
            clearLayer()
            return false
        }
        val previousExpansion = expandedCardId
        val retainedExpansion = previousExpansion?.takeIf { id -> layer.cards.any { it.cardId == id } }
        expectedTarget = target
        cards = layer.cards.toList()
        expandedCardId = retainedExpansion
        scrollOffset = 0f
        clearTextLayout()
        visibility = if (cards.isEmpty()) GONE else VISIBLE
        resetGeometry()
        if (previousExpansion != retainedExpansion) onExpandedCardChanged(retainedExpansion)
        return true
    }

    fun clearLayer() {
        val hadExpansion = expandedCardId != null
        expectedTarget = null
        cards = emptyList()
        expandedCardId = null
        scrollOffset = 0f
        clearTextLayout()
        resetGesture()
        visibility = GONE
        if (hadExpansion) onExpandedCardChanged(null)
    }

    /** Optional safe area for chips/cards. Null means this view's full bounds. */
    fun setContentBoundsInView(bounds: RectF?) {
        contentBoundsOverride = bounds?.let(::normalizedCopy)
        clearTextLayout()
        scrollOffset = 0f
        resetGeometry()
    }

    /** Call from the host's already-owned [PdfViewportAdapter.onViewportChanged] callback. */
    fun notifyViewportChanged() {
        resetGeometry()
    }

    fun expandedCardId(): String? = expandedCardId

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clearTextLayout()
        scrollOffset = 0f
        resetGeometry()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateGeometry()
        chipPlacements.forEach { placement ->
            val card = cards.firstOrNull { it.cardId == placement.id } ?: return@forEach
            drawChip(canvas, placement.bounds, card)
        }
        drawExpandedPanel(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updateGeometry()
                val hit = assistantOverlayHitTest(
                    x = event.x,
                    y = event.y,
                    chips = chipPlacements,
                    expandedCardId = expandedCardId,
                    panel = panelBounds,
                    close = closeBounds,
                )
                if (hit.kind == AssistantOverlayHitKind.OUTSIDE) return false
                gesture = when (hit.kind) {
                    AssistantOverlayHitKind.CHIP -> Gesture.CHIP
                    AssistantOverlayHitKind.CLOSE -> Gesture.CLOSE
                    AssistantOverlayHitKind.PANEL -> Gesture.PANEL
                    AssistantOverlayHitKind.OUTSIDE -> Gesture.NONE
                }
                gestureCardId = hit.cardId
                downX = event.x
                downY = event.y
                downScroll = scrollOffset
                gestureMoved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gesture == Gesture.NONE) return false
                if (!gestureMoved &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    gestureMoved = true
                }
                if (gesture == Gesture.PANEL && maximumScroll > 0f) {
                    scrollOffset = (downScroll + downY - event.y).coerceIn(0f, maximumScroll)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.NONE) return false
                updateGeometry()
                when (gesture) {
                    Gesture.CHIP -> if (!gestureMoved) {
                        val hit = assistantOverlayHitTest(
                            event.x,
                            event.y,
                            chipPlacements,
                            expandedCardId,
                            panelBounds,
                            closeBounds,
                        )
                        if (hit.kind == AssistantOverlayHitKind.CHIP && hit.cardId == gestureCardId) {
                            expandCard(checkNotNull(hit.cardId))
                        }
                    }

                    Gesture.CLOSE -> if (!gestureMoved && closeBounds?.contains(event.x, event.y) == true) {
                        collapseCard()
                    }

                    Gesture.PANEL, Gesture.NONE -> Unit
                }
                performClick()
                resetGesture()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (gesture == Gesture.NONE) return false
                resetGesture()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return gesture != Gesture.NONE
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun expandCard(cardId: String) {
        if (expandedCardId == cardId) return
        expandedCardId = cardId
        scrollOffset = 0f
        clearTextLayout()
        resetGeometry()
        onExpandedCardChanged(cardId)
    }

    private fun collapseCard() {
        if (expandedCardId == null) return
        expandedCardId = null
        scrollOffset = 0f
        clearTextLayout()
        resetGeometry()
        onExpandedCardChanged(null)
    }

    private fun updateGeometry() {
        val target = expectedTarget
        val adapter = viewportAdapter
        val viewport = resolvedContentBounds()
        if (target == null || adapter == null || adapter.activePage() != target.page.pageNumber ||
            viewport.width <= 0f || viewport.height <= 0f
        ) {
            clearGeometry()
            return
        }

        anchorsByCardId.clear()
        cards.forEach { card ->
            canonicalBoundsInView(adapter, target.page.pageNumber, card.anchorBounds)?.let { mapped ->
                anchorsByCardId[card.cardId] = mapped
            }
        }
        val chipHeight = dp(CHIP_HEIGHT_DP)
        chipPlacements = placeAssistantChips(
            chips = cards.mapNotNull { card ->
                val anchor = anchorsByCardId[card.cardId] ?: return@mapNotNull null
                AssistantAnchoredChip(
                    id = card.cardId,
                    anchor = anchor,
                    width = chipWidth(card),
                    height = chipHeight,
                )
            },
            viewport = viewport,
            gap = dp(CHIP_GAP_DP),
        )

        val expandedId = expandedCardId
        val expanded = cards.firstOrNull { it.cardId == expandedId }
        val anchor = expandedId?.let(anchorsByCardId::get)
        if (expanded == null || anchor == null ||
            viewport.width < dp(MIN_PANEL_VIEWPORT_WIDTH_DP) ||
            viewport.height < dp(MIN_PANEL_VIEWPORT_HEIGHT_DP)
        ) {
            panelBounds = null
            closeBounds = null
            bodyBounds = null
            maximumScroll = 0f
            return
        }

        val desiredWidth = min(dp(MAX_PANEL_WIDTH_DP), viewport.width)
        val innerWidth = max(1, (desiredWidth - dp(PANEL_HORIZONTAL_PADDING_DP * 2f)).toInt())
        ensureTextLayout(expanded, innerWidth)
        val layoutHeight = bodyLayout?.height?.toFloat() ?: 0f
        val maximumHeight = min(dp(MAX_PANEL_HEIGHT_DP), viewport.height * MAX_PANEL_HEIGHT_FRACTION)
            .coerceAtLeast(min(dp(MIN_PANEL_HEIGHT_DP), viewport.height))
        val desiredHeight = (dp(PANEL_HEADER_HEIGHT_DP + PANEL_BOTTOM_PADDING_DP) + layoutHeight)
            .coerceIn(min(dp(MIN_PANEL_HEIGHT_DP), viewport.height), maximumHeight)
        panelBounds = placeAssistantPanel(
            anchor = anchor,
            viewport = viewport,
            width = desiredWidth,
            height = desiredHeight,
            gap = dp(PANEL_GAP_DP),
        )
        val panel = checkNotNull(panelBounds)
        val closeSize = dp(CLOSE_HIT_SIZE_DP)
        closeBounds = AssistantUiRect(
            left = panel.right - closeSize - dp(4f),
            top = panel.top + dp(4f),
            right = panel.right - dp(4f),
            bottom = panel.top + dp(4f) + closeSize,
        )
        bodyBounds = AssistantUiRect(
            left = panel.left + dp(PANEL_HORIZONTAL_PADDING_DP),
            top = panel.top + dp(PANEL_HEADER_HEIGHT_DP),
            right = panel.right - dp(PANEL_HORIZONTAL_PADDING_DP),
            bottom = panel.bottom - dp(PANEL_BOTTOM_PADDING_DP),
        )
        maximumScroll = max(0f, layoutHeight - checkNotNull(bodyBounds).height)
        scrollOffset = scrollOffset.coerceIn(0f, maximumScroll)
    }

    private fun drawChip(canvas: Canvas, bounds: AssistantUiRect, card: StudentExplanationCard) {
        val rect = bounds.toRectF()
        val radius = dp(12f)
        canvas.drawRoundRect(rect, radius, radius, chipPaint)
        canvas.drawRoundRect(rect, radius, radius, chipOutlinePaint)
        val label = TextUtils.ellipsize(
            card.title.ifBlank { "설명" },
            chipTextPaint,
            bounds.width - dp(22f),
            TextUtils.TruncateAt.END,
        ).toString()
        val metrics = chipTextPaint.fontMetrics
        val baseline = bounds.top + (bounds.height - metrics.ascent - metrics.descent) / 2f
        canvas.drawText(label, bounds.left + dp(11f), baseline, chipTextPaint)
    }

    private fun drawExpandedPanel(canvas: Canvas) {
        val card = cards.firstOrNull { it.cardId == expandedCardId } ?: return
        val panel = panelBounds ?: return
        val close = closeBounds ?: return
        val body = bodyBounds ?: return
        val layout = bodyLayout ?: return
        val panelRect = panel.toRectF()
        val radius = dp(15f)
        canvas.drawRoundRect(panelRect, radius, radius, panelPaint)
        canvas.drawRoundRect(panelRect, radius, radius, panelOutlinePaint)

        val availableTitleWidth = max(0f, close.left - panel.left - dp(28f))
        val title = TextUtils.ellipsize(
            card.title.ifBlank { "설명" },
            titlePaint,
            availableTitleWidth,
            TextUtils.TruncateAt.END,
        ).toString()
        val titleMetrics = titlePaint.fontMetrics
        val titleBaseline = panel.top + dp(12f) - titleMetrics.ascent
        canvas.drawText(title, panel.left + dp(PANEL_HORIZONTAL_PADDING_DP), titleBaseline, titlePaint)

        val crossInset = dp(11f)
        canvas.drawLine(close.left + crossInset, close.top + crossInset, close.right - crossInset, close.bottom - crossInset, closePaint)
        canvas.drawLine(close.right - crossInset, close.top + crossInset, close.left + crossInset, close.bottom - crossInset, closePaint)

        val saveCount = canvas.save()
        canvas.clipRect(body.left, body.top, body.right, body.bottom)
        canvas.translate(body.left, body.top - scrollOffset)
        layout.draw(canvas)
        canvas.restoreToCount(saveCount)

        if (bodyWasTruncated) {
            canvas.drawText(
                "긴 설명의 앞부분만 표시됨",
                panel.left + dp(PANEL_HORIZONTAL_PADDING_DP),
                panel.bottom - dp(5f),
                overflowPaint,
            )
        }
    }

    private fun ensureTextLayout(card: StudentExplanationCard, widthPixels: Int) {
        if (bodyLayoutCardId == card.cardId && bodyLayoutWidth == widthPixels && bodyLayout != null) return
        val normalized = card.text.ifBlank { "설명 내용이 없습니다." }
        val visibleText = normalized.take(MAX_VISIBLE_BODY_CHARS)
        bodyWasTruncated = normalized.length > visibleText.length
        bodyLayout = StaticLayout.Builder
            .obtain(visibleText, 0, visibleText.length, bodyTextPaint, widthPixels.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(dp(3f), 1f)
            .build()
        bodyLayoutCardId = card.cardId
        bodyLayoutWidth = widthPixels
    }

    private fun canonicalBoundsInView(
        adapter: PdfViewportAdapter,
        pageNumber: Int,
        bounds: PageBounds,
    ): AssistantUiRect? {
        val mapped = listOf(
            PagePoint(bounds.left, bounds.top),
            PagePoint(bounds.right, bounds.top),
            PagePoint(bounds.left, bounds.bottom),
            PagePoint(bounds.right, bounds.bottom),
        ).mapNotNull { adapter.canonicalToView(pageNumber, it) }
        if (mapped.size != 4) return null
        return AssistantUiRect(
            left = mapped.minOf { it.x },
            top = mapped.minOf { it.y },
            right = mapped.maxOf { it.x },
            bottom = mapped.maxOf { it.y },
        )
    }

    private fun chipWidth(card: StudentExplanationCard): Float =
        (chipTextPaint.measureText(card.title.ifBlank { "설명" }) + dp(22f))
            .coerceIn(dp(MIN_CHIP_WIDTH_DP), dp(MAX_CHIP_WIDTH_DP))

    private fun resolvedContentBounds(): AssistantUiRect {
        val viewBounds = RectF(0f, 0f, width.toFloat().coerceAtLeast(0f), height.toFloat().coerceAtLeast(0f))
        val requested = contentBoundsOverride ?: return viewBounds.toAssistantRect()
        val intersection = RectF(
            max(viewBounds.left, requested.left),
            max(viewBounds.top, requested.top),
            min(viewBounds.right, requested.right),
            min(viewBounds.bottom, requested.bottom),
        )
        return if (intersection.width() > 0f && intersection.height() > 0f) {
            intersection.toAssistantRect()
        } else {
            viewBounds.toAssistantRect()
        }
    }

    private fun resetGeometry() {
        clearGeometry()
        invalidate()
    }

    private fun clearGeometry() {
        chipPlacements = emptyList()
        anchorsByCardId.clear()
        panelBounds = null
        closeBounds = null
        bodyBounds = null
        maximumScroll = 0f
    }

    private fun clearTextLayout() {
        bodyLayout = null
        bodyLayoutWidth = -1
        bodyLayoutCardId = null
        bodyWasTruncated = false
    }

    private fun resetGesture() {
        gesture = Gesture.NONE
        gestureCardId = null
        gestureMoved = false
    }

    private fun normalizedCopy(rect: RectF): RectF = RectF(
        min(rect.left, rect.right),
        min(rect.top, rect.bottom),
        max(rect.left, rect.right),
        max(rect.top, rect.bottom),
    )

    private fun AssistantUiRect.toRectF(): RectF = RectF(left, top, right, bottom)
    private fun RectF.toAssistantRect(): AssistantUiRect = AssistantUiRect(left, top, right, bottom)
    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private enum class Gesture { NONE, CHIP, PANEL, CLOSE }

    private companion object {
        const val CHIP_HEIGHT_DP = 38f
        const val CHIP_GAP_DP = 7f
        const val MIN_CHIP_WIDTH_DP = 82f
        const val MAX_CHIP_WIDTH_DP = 172f
        const val PANEL_GAP_DP = 10f
        const val MAX_PANEL_WIDTH_DP = 420f
        const val MIN_PANEL_VIEWPORT_WIDTH_DP = 96f
        const val MIN_PANEL_VIEWPORT_HEIGHT_DP = 96f
        const val MIN_PANEL_HEIGHT_DP = 138f
        const val MAX_PANEL_HEIGHT_DP = 430f
        const val MAX_PANEL_HEIGHT_FRACTION = 0.62f
        const val PANEL_HEADER_HEIGHT_DP = 54f
        const val PANEL_HORIZONTAL_PADDING_DP = 16f
        const val PANEL_BOTTOM_PADDING_DP = 14f
        const val CLOSE_HIT_SIZE_DP = 44f
        const val MAX_VISIBLE_BODY_CHARS = 16_000
    }
}
