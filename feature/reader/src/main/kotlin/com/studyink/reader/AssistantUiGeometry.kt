package com.studyink.reader

import kotlin.math.max
import kotlin.math.min

/** Small Android-free rectangle used by the assistant overlays and their JVM tests. */
internal data class AssistantUiRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left <= right && top <= bottom)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun intersects(other: AssistantUiRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun inset(amount: Float): AssistantUiRect {
        require(amount >= 0f && amount.isFinite())
        val horizontal = min(amount, width / 2f)
        val vertical = min(amount, height / 2f)
        return AssistantUiRect(left + horizontal, top + vertical, right - horizontal, bottom - vertical)
    }

    fun expanded(amount: Float): AssistantUiRect {
        require(amount >= 0f && amount.isFinite())
        return AssistantUiRect(left - amount, top - amount, right + amount, bottom + amount)
    }
}

internal fun normalizedAssistantRect(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    limit: AssistantUiRect,
): AssistantUiRect {
    val clampedStartX = startX.coerceIn(limit.left, limit.right)
    val clampedStartY = startY.coerceIn(limit.top, limit.bottom)
    val clampedEndX = endX.coerceIn(limit.left, limit.right)
    val clampedEndY = endY.coerceIn(limit.top, limit.bottom)
    return AssistantUiRect(
        left = min(clampedStartX, clampedEndX),
        top = min(clampedStartY, clampedEndY),
        right = max(clampedStartX, clampedEndX),
        bottom = max(clampedStartY, clampedEndY),
    )
}

internal enum class AssistantSelectionHandle {
    NONE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

internal fun assistantSelectionHandleAt(
    rect: AssistantUiRect,
    x: Float,
    y: Float,
    radius: Float,
): AssistantSelectionHandle {
    require(radius >= 0f && radius.isFinite())
    val radiusSquared = radius * radius
    fun near(targetX: Float, targetY: Float): Boolean {
        val dx = x - targetX
        val dy = y - targetY
        return dx * dx + dy * dy <= radiusSquared
    }
    return when {
        near(rect.left, rect.top) -> AssistantSelectionHandle.TOP_LEFT
        near(rect.right, rect.top) -> AssistantSelectionHandle.TOP_RIGHT
        near(rect.left, rect.bottom) -> AssistantSelectionHandle.BOTTOM_LEFT
        near(rect.right, rect.bottom) -> AssistantSelectionHandle.BOTTOM_RIGHT
        else -> AssistantSelectionHandle.NONE
    }
}

internal fun resizeAssistantSelection(
    original: AssistantUiRect,
    handle: AssistantSelectionHandle,
    pointerX: Float,
    pointerY: Float,
    limit: AssistantUiRect,
    minimumSize: Float,
): AssistantUiRect {
    require(minimumSize >= 0f && minimumSize.isFinite())
    if (handle == AssistantSelectionHandle.NONE) return original
    val x = pointerX.coerceIn(limit.left, limit.right)
    val y = pointerY.coerceIn(limit.top, limit.bottom)
    val minWidth = min(minimumSize, limit.width)
    val minHeight = min(minimumSize, limit.height)
    return when (handle) {
        AssistantSelectionHandle.TOP_LEFT -> AssistantUiRect(
            left = min(x, original.right - minWidth).coerceAtLeast(limit.left),
            top = min(y, original.bottom - minHeight).coerceAtLeast(limit.top),
            right = original.right,
            bottom = original.bottom,
        )
        AssistantSelectionHandle.TOP_RIGHT -> AssistantUiRect(
            left = original.left,
            top = min(y, original.bottom - minHeight).coerceAtLeast(limit.top),
            right = max(x, original.left + minWidth).coerceAtMost(limit.right),
            bottom = original.bottom,
        )
        AssistantSelectionHandle.BOTTOM_LEFT -> AssistantUiRect(
            left = min(x, original.right - minWidth).coerceAtLeast(limit.left),
            top = original.top,
            right = original.right,
            bottom = max(y, original.top + minHeight).coerceAtMost(limit.bottom),
        )
        AssistantSelectionHandle.BOTTOM_RIGHT -> AssistantUiRect(
            left = original.left,
            top = original.top,
            right = max(x, original.left + minWidth).coerceAtMost(limit.right),
            bottom = max(y, original.top + minHeight).coerceAtMost(limit.bottom),
        )
        AssistantSelectionHandle.NONE -> original
    }
}

internal data class AssistantAnchoredChip(
    val id: String,
    val anchor: AssistantUiRect,
    val width: Float,
    val height: Float,
) {
    init {
        require(id.isNotBlank())
        require(width > 0f && width.isFinite() && height > 0f && height.isFinite())
    }
}

internal data class AssistantChipPlacement(
    val id: String,
    val bounds: AssistantUiRect,
)

/**
 * Places compact cards beside their canonical anchors. Collisions move down first, then wrap to
 * the top of the usable viewport. The input order is retained so card-id canonical ordering stays
 * stable across zoom and process restarts.
 */
internal fun placeAssistantChips(
    chips: List<AssistantAnchoredChip>,
    viewport: AssistantUiRect,
    gap: Float,
): List<AssistantChipPlacement> {
    require(gap >= 0f && gap.isFinite())
    if (viewport.width <= 0f || viewport.height <= 0f) return emptyList()
    val placed = mutableListOf<AssistantChipPlacement>()
    chips.forEach { chip ->
        val width = min(chip.width, viewport.width)
        val height = min(chip.height, viewport.height)
        val preferredRightX = chip.anchor.right + gap
        val preferredLeftX = chip.anchor.left - gap - width
        val startX = when {
            preferredRightX + width <= viewport.right -> preferredRightX
            preferredLeftX >= viewport.left -> preferredLeftX
            else -> chip.anchor.left
        }.coerceIn(viewport.left, viewport.right - width)
        var candidate = AssistantUiRect(
            left = startX,
            top = chip.anchor.top.coerceIn(viewport.top, viewport.bottom - height),
            right = startX + width,
            bottom = chip.anchor.top.coerceIn(viewport.top, viewport.bottom - height) + height,
        )
        var attempts = 0
        while (placed.any { it.bounds.expanded(gap / 2f).intersects(candidate) } &&
            attempts <= placed.size
        ) {
            val nextTop = placed.asSequence()
                .filter { it.bounds.expanded(gap / 2f).intersects(candidate) }
                .maxOf { it.bounds.bottom + gap }
            candidate = if (nextTop + height <= viewport.bottom) {
                AssistantUiRect(candidate.left, nextTop, candidate.right, nextTop + height)
            } else {
                val wrappedX = (candidate.left - width - gap)
                    .coerceIn(viewport.left, viewport.right - width)
                AssistantUiRect(wrappedX, viewport.top, wrappedX + width, viewport.top + height)
            }
            attempts += 1
        }
        placed += AssistantChipPlacement(chip.id, candidate)
    }
    return placed
}

internal fun placeAssistantPanel(
    anchor: AssistantUiRect,
    viewport: AssistantUiRect,
    width: Float,
    height: Float,
    gap: Float,
): AssistantUiRect {
    require(width > 0f && width.isFinite() && height > 0f && height.isFinite())
    require(gap >= 0f && gap.isFinite())
    val panelWidth = min(width, viewport.width)
    val panelHeight = min(height, viewport.height)
    val preferredRight = anchor.right + gap
    val preferredLeft = anchor.left - gap - panelWidth
    val left = when {
        preferredRight + panelWidth <= viewport.right -> preferredRight
        preferredLeft >= viewport.left -> preferredLeft
        else -> anchor.left
    }.coerceIn(viewport.left, viewport.right - panelWidth)
    val top = anchor.top.coerceIn(viewport.top, viewport.bottom - panelHeight)
    return AssistantUiRect(left, top, left + panelWidth, top + panelHeight)
}

internal enum class AssistantOverlayHitKind { OUTSIDE, CHIP, PANEL, CLOSE }

internal data class AssistantOverlayHit(
    val kind: AssistantOverlayHitKind,
    val cardId: String? = null,
)

internal fun assistantOverlayHitTest(
    x: Float,
    y: Float,
    chips: List<AssistantChipPlacement>,
    expandedCardId: String?,
    panel: AssistantUiRect?,
    close: AssistantUiRect?,
): AssistantOverlayHit {
    if (expandedCardId != null && close?.contains(x, y) == true) {
        return AssistantOverlayHit(AssistantOverlayHitKind.CLOSE, expandedCardId)
    }
    if (expandedCardId != null && panel?.contains(x, y) == true) {
        return AssistantOverlayHit(AssistantOverlayHitKind.PANEL, expandedCardId)
    }
    chips.asReversed().firstOrNull { it.bounds.contains(x, y) }?.let {
        return AssistantOverlayHit(AssistantOverlayHitKind.CHIP, it.id)
    }
    return AssistantOverlayHit(AssistantOverlayHitKind.OUTSIDE)
}
