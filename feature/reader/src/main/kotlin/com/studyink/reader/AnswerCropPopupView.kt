package com.studyink.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

/** Compact answer preview whose header can be dragged without owning touches outside its bounds. */
internal class AnswerCropPopupView(context: Context) : FrameLayout(context) {
    var onOpenPdf: () -> Unit = {}
    var onClose: () -> Unit = {}
    var onPositionChanged: (Float, Float) -> Unit = { _, _ -> }

    private val density = resources.displayMetrics.density
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.WHITE)
        contentDescription = "저장된 답안 영역"
    }
    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
        contentDescription = "답안 불러오는 중"
    }
    private val content = FrameLayout(context).apply {
        setBackgroundColor(Color.WHITE)
        addView(image, LayoutParams(MATCH, MATCH))
        addView(progress, LayoutParams(dp(28), dp(28), Gravity.CENTER))
    }
    private val dragHandle = TextView(context).apply {
        text = "  답  ···"
        textSize = 11f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(Color.rgb(45, 49, 56))
        contentDescription = "답 창 이동"
    }
    private val openPdf = compactAction("PDF") { onOpenPdf() }
    private val close = compactAction("×") { onClose() }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.rgb(242, 240, 234))
        addView(dragHandle, LinearLayout.LayoutParams(0, MATCH, 1f))
        addView(openPdf, LinearLayout.LayoutParams(dp(48), MATCH))
        addView(close, LinearLayout.LayoutParams(dp(44), MATCH))
    }

    private var dragBounds = RectF()
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartViewX = 0f
    private var dragStartViewY = 0f

    init {
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.argb(90, 48, 51, 58))
        }
        clipToOutline = true
        isClickable = true
        isFocusable = true
        addView(content, LayoutParams(MATCH, MATCH).apply { topMargin = dp(HEADER_HEIGHT_DP) })
        addView(header, LayoutParams(MATCH, dp(HEADER_HEIGHT_DP), Gravity.TOP))
        dragHandle.setOnTouchListener(::handleDrag)
    }

    fun setDragBounds(boundsInParent: RectF) {
        dragBounds = RectF(boundsInParent)
        clampToBounds()
    }

    fun showBitmap(bitmap: Bitmap) {
        image.setBackgroundColor(Color.WHITE)
        image.contentDescription = "저장된 답안 영역"
        image.setImageBitmap(bitmap)
        image.visibility = VISIBLE
        progress.visibility = GONE
    }

    fun showLoading() {
        image.setImageDrawable(null)
        image.setBackgroundColor(Color.WHITE)
        image.contentDescription = "저장된 답안 영역"
        image.visibility = VISIBLE
        progress.visibility = VISIBLE
    }

    fun showError() {
        progress.visibility = GONE
        image.setImageDrawable(null)
        image.setBackgroundColor(Color.rgb(246, 244, 239))
        image.contentDescription = "답안을 불러오지 못했습니다"
    }

    fun clearBitmap() {
        image.setImageDrawable(null)
    }

    private fun handleDrag(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartViewX = x
                dragStartViewY = y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                x = dragStartViewX + event.rawX - dragStartRawX
                y = dragStartViewY + event.rawY - dragStartRawY
                clampToBounds()
                return true
            }
            MotionEvent.ACTION_UP -> {
                clampToBounds()
                parent?.requestDisallowInterceptTouchEvent(false)
                onPositionChanged(x, y)
                view.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                clampToBounds()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun clampToBounds() {
        if (dragBounds.width() <= 0f || dragBounds.height() <= 0f || width <= 0 || height <= 0) return
        val maximumX = (dragBounds.right - width).coerceAtLeast(dragBounds.left)
        val maximumY = (dragBounds.bottom - height).coerceAtLeast(dragBounds.top)
        x = x.coerceIn(dragBounds.left, maximumX)
        y = y.coerceIn(dragBounds.top, maximumY)
    }

    private fun compactAction(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = if (label == "×") 18f else 10f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(36, 77, 142))
        isClickable = true
        isFocusable = true
        contentDescription = if (label == "×") "답 창 닫기" else "전체 답안 PDF 열기"
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val HEADER_HEIGHT_DP = 44
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
