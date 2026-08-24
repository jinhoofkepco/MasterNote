package com.studyink.reader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

/** A short-lived, non-touchable parent message shown over the worksheet. */
class ParentMessageOverlayView(context: Context) : TextView(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideMessage = Runnable {
        text = ""
        visibility = View.GONE
    }

    init {
        setTextColor(Color.rgb(49, 46, 40))
        textSize = 17f
        gravity = android.view.Gravity.CENTER
        maxLines = 4
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(235, 255, 252, 245))
            setStroke(dp(1), Color.argb(150, 193, 182, 163))
        }
        elevation = dp(5).toFloat()
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        contentDescription = "부모님 메시지"
        visibility = View.GONE
    }

    fun showMessage(message: String, durationMillis: Long = DISPLAY_MILLIS) {
        val normalized = message.trim().take(MAX_MESSAGE_CHARS)
        if (normalized.isEmpty()) return
        mainHandler.removeCallbacks(hideMessage)
        text = normalized
        visibility = View.VISIBLE
        mainHandler.postDelayed(hideMessage, durationMillis.coerceAtLeast(1L))
    }

    /** Shows a progress message until its owner explicitly replaces or clears it. */
    fun showPersistentMessage(message: String) {
        val normalized = message.trim().take(MAX_MESSAGE_CHARS)
        if (normalized.isEmpty()) {
            clearMessage()
            return
        }
        mainHandler.removeCallbacks(hideMessage)
        text = normalized
        visibility = View.VISIBLE
    }

    /** Visually separates local wake/dictation guidance from a parent's inbound message. */
    fun useStudentStatusStyle() {
        setTextColor(Color.rgb(35, 70, 62))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(238, 235, 247, 242))
            setStroke(dp(1), Color.argb(175, 79, 139, 123))
        }
        contentDescription = "학생 메시지 입력 안내"
    }

    fun updateVerticalAnchor(parentHeight: Int) {
        val params = layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val target = (parentHeight * VERTICAL_FRACTION).roundToInt()
        if (params.topMargin == target) return
        params.topMargin = target
        layoutParams = params
    }

    fun clearMessage() {
        mainHandler.removeCallbacks(hideMessage)
        hideMessage.run()
    }

    // This view is deliberately display-only. S Pen input in its visible rectangle still belongs
    // to the worksheet underneath.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(hideMessage)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val DISPLAY_MILLIS = 5_000L
        const val VERTICAL_FRACTION = 0.30f
        const val MAX_MESSAGE_CHARS = 500
    }
}
