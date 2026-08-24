package com.studyink.reader

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AbstractComposeView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The polar area owned by the stylus menu, expressed in this overlay's local pixels.
 *
 * The whole annular sector from [a] through [b] belongs to the menu, including otherwise blank
 * space between controls. A tool corridor extends that ownership from [a] through [c] around each
 * angle in [toolCorridorAnglesDegrees]. Everything else is intentionally left to the reader view
 * below the overlay.
 */
data class StylusMenuInputRegion(
    val originX: Float,
    val originY: Float,
    val a: Float,
    val b: Float,
    val c: Float,
    val startAngleDegrees: Float,
    val endAngleDegrees: Float,
    val itemAnglesDegrees: List<Float> = emptyList(),
    val itemCenterRadius: Float = 0f,
    val itemHitRadius: Float = 0f,
    val toolCorridorAnglesDegrees: List<Float> = emptyList(),
    val toolCorridorHalfWidth: Float = 0f,
) {
    init {
        require(originX.isFinite() && originY.isFinite()) { "The menu origin must be finite." }
        require(a.isFinite() && b.isFinite() && c.isFinite()) { "Menu radii must be finite." }
        require(a >= 0f && a <= b && b <= c) { "Menu radii must satisfy 0 <= a <= b <= c." }
        require(startAngleDegrees.isFinite() && endAngleDegrees.isFinite()) {
            "Menu angles must be finite."
        }
        require(toolCorridorHalfWidth.isFinite() && toolCorridorHalfWidth >= 0f) {
            "The tool corridor half-width must be finite and non-negative."
        }
        require(toolCorridorAnglesDegrees.all(Float::isFinite)) {
            "Tool corridor angles must be finite."
        }
        require(itemCenterRadius.isFinite() && itemCenterRadius >= 0f) {
            "The item centre radius must be finite and non-negative."
        }
        require(itemHitRadius.isFinite() && itemHitRadius >= 0f) {
            "The item hit radius must be finite and non-negative."
        }
        require(itemAnglesDegrees.all(Float::isFinite)) { "Item angles must be finite." }
    }

    /** Pure polar hit test, suitable for both the Android view and local unit tests. */
    fun contains(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val dx = x - originX
        val dy = y - originY
        val radiusSquared = dx * dx + dy * dy

        if (radiusSquared >= (a - HIT_EPSILON).coerceAtLeast(0f).let { it * it } &&
            radiusSquared <= (b + HIT_EPSILON).let { it * it }
        ) {
            val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (isAngleInSector(angle)) return true
        }

        // First and last button centres sit exactly on the declared fan endpoints. Their visible
        // circular targets extend slightly beyond that angular line and must remain menu UI.
        if (itemHitRadius > 0f) {
            for (angleDegrees in itemAnglesDegrees) {
                val angleRadians = angleDegrees * PI.toFloat() / 180f
                val centerX = cos(angleRadians) * itemCenterRadius
                val centerY = sin(angleRadians) * itemCenterRadius
                val itemDx = dx - centerX
                val itemDy = dy - centerY
                val hitRadius = itemHitRadius + HIT_EPSILON
                if (itemDx * itemDx + itemDy * itemDy <= hitRadius * hitRadius) return true
            }
        }

        if (toolCorridorHalfWidth == 0f || toolCorridorAnglesDegrees.isEmpty()) return false
        for (angleDegrees in toolCorridorAnglesDegrees) {
            val angleRadians = angleDegrees * PI.toFloat() / 180f
            val directionX = cos(angleRadians)
            val directionY = sin(angleRadians)
            val radial = dx * directionX + dy * directionY
            val tangential = abs(-dx * directionY + dy * directionX)
            if (radial >= a - HIT_EPSILON &&
                radial <= c + HIT_EPSILON &&
                tangential <= toolCorridorHalfWidth + HIT_EPSILON
            ) return true
        }
        return false
    }

    private fun isAngleInSector(angleDegrees: Float): Boolean {
        val rawSweep = endAngleDegrees - startAngleDegrees
        if (abs(rawSweep) >= FULL_CIRCLE_DEGREES) return true
        val sweep = positiveDegrees(rawSweep)
        val offset = positiveDegrees(angleDegrees - startAngleDegrees)
        return offset <= sweep + HIT_EPSILON
    }

    private fun positiveDegrees(value: Float): Float {
        val remainder = value % FULL_CIRCLE_DEGREES
        return if (remainder < 0f) remainder + FULL_CIRCLE_DEGREES else remainder
    }

    private companion object {
        const val FULL_CIRCLE_DEGREES = 360f
        const val HIT_EPSILON = 0.001f
    }
}

/**
 * A full-screen Compose host whose input ownership is limited to [StylusMenuInputRegion].
 *
 * [androidx.compose.ui.platform.ComposeView] is final, so this uses its public base class and
 * exposes the same small [setContent] surface. Returning false outside the polar region lets the
 * parent ViewGroup try the reader siblings underneath. A touch that began inside stays captured
 * until its UP/CANCEL so a menu gesture can never turn into half a page stroke.
 */
class StylusMenuOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val screenLocation = IntArray(2)

    private var inputRegion: StylusMenuInputRegion? = null
    private var activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
    private var hoverCaptured = false
    private var lastHoverEvent: MotionEvent? = null

    protected override var shouldCreateCompositionOnAttachedToWindow: Boolean = false

    @Composable
    override fun Content() {
        content.value?.invoke()
    }

    override fun getAccessibilityClassName(): CharSequence = javaClass.name

    fun setContent(content: @Composable () -> Unit) {
        shouldCreateCompositionOnAttachedToWindow = true
        this.content.value = content
        if (isAttachedToWindow) createComposition()
    }

    /** Replaces the menu-owned region. Values are local pixels, not dp or screen coordinates. */
    fun updateMenuInputRegion(region: StylusMenuInputRegion) {
        inputRegion = region.copy(
            itemAnglesDegrees = region.itemAnglesDegrees.toList(),
            toolCorridorAnglesDegrees = region.toolCorridorAnglesDegrees.toList(),
        )
        Log.d(
            PEN_INPUT_LOG_TAG,
            "region origin=${region.originX},${region.originY} abc=${region.a},${region.b},${region.c}",
        )
        val hover = lastHoverEvent
        if (hoverCaptured && hover != null && !region.contains(hover.x, hover.y)) endCapturedHover(hover)
    }

    /**
     * Stops accepting new menu gestures. An already captured touch is deliberately retained until
     * UP/CANCEL so its remainder cannot leak into the page below.
     */
    fun clearMenuInputRegion() {
        inputRegion = null
        Log.d(PEN_INPUT_LOG_TAG, "region cleared")
        lastHoverEvent?.let(::endCapturedHover)
    }

    fun isMenuUiAtLocal(x: Float, y: Float): Boolean = inputRegion?.contains(x, y) == true

    /** Hit test for Activity-level events, whose coordinates are expressed on the screen. */
    fun isMenuUiAtRaw(rawX: Float, rawY: Float): Boolean {
        if (!isAttachedToWindow || !rawX.isFinite() || !rawY.isFinite()) return false
        getLocationOnScreen(screenLocation)
        return isMenuUiAtLocal(rawX - screenLocation[0], rawY - screenLocation[1])
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (activeTouchPointerId != MotionEvent.INVALID_POINTER_ID) {
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                Log.d(PEN_INPUT_LOG_TAG, "touch end action=${event.actionMasked} handled=$handled")
            }
            if (endsActiveTouch(event)) activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
            // The stream belongs to this overlay even when no composable occupies blank ring space.
            return true
        }

        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        val pointerIndex = event.actionIndex.takeIf { it in 0 until event.pointerCount } ?: return false
        if (event.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_STYLUS) return false
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (!isMenuUiAtLocal(x, y)) {
            Log.d(PEN_INPUT_LOG_TAG, "touch down outside x=$x y=$y")
            return false
        }

        activeTouchPointerId = event.getPointerId(pointerIndex)
        val handled = super.dispatchTouchEvent(event)
        Log.d(PEN_INPUT_LOG_TAG, "touch down inside x=$x y=$y handled=$handled")
        // Consume the annular sector even when the DOWN landed between visible controls.
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            if (!hoverCaptured) return false
            hoverCaptured = false
            replaceLastHoverEvent(null)
            return super.dispatchGenericMotionEvent(event)
        }

        val pointerIndex = event.actionIndex.takeIf { it in 0 until event.pointerCount }
        val normalStylus = pointerIndex != null &&
            event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS
        val inside = normalStylus &&
            isMenuUiAtLocal(event.getX(pointerIndex), event.getY(pointerIndex))

        if (!inside) {
            if (hoverCaptured) endCapturedHover(event)
            return false
        }

        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE
        ) {
            hoverCaptured = true
            replaceLastHoverEvent(MotionEvent.obtain(event))
        }
        super.dispatchGenericMotionEvent(event)
        // Blank portions of the menu ring are still menu UI for hover, scroll, and button events.
        return true
    }

    override fun onDetachedFromWindow() {
        activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
        hoverCaptured = false
        replaceLastHoverEvent(null)
        super.onDetachedFromWindow()
    }

    private fun endsActiveTouch(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> true

            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                index in 0 until event.pointerCount &&
                    event.getPointerId(index) == activeTouchPointerId
            }

            else -> false
        }
    }

    private fun endCapturedHover(referenceEvent: MotionEvent) {
        if (!hoverCaptured) return
        val exit = MotionEvent.obtain(referenceEvent)
        exit.action = MotionEvent.ACTION_HOVER_EXIT
        super.dispatchGenericMotionEvent(exit)
        exit.recycle()
        hoverCaptured = false
        replaceLastHoverEvent(null)
    }

    private fun replaceLastHoverEvent(replacement: MotionEvent?) {
        lastHoverEvent?.recycle()
        lastHoverEvent = replacement
    }

    private companion object {
        const val PEN_INPUT_LOG_TAG = "MasterNotePenInput"
    }
}
