package com.studyink.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.studyink.construction.core.GeometryLineStyle
import kotlin.math.roundToInt

internal enum class ConstructionIcon { SELECT, POINT, SEGMENT, CIRCLE, CONSTRAINT, MEASURE, LIST, UNDO, REDO, FIT, CLOSE, MORE, MAGNET, DELETE, LINE_SOLID, LINE_DASHED, LINE_DOTTED }

internal fun GeometryLineStyle.koreanName(): String = when (this) {
    GeometryLineStyle.SOLID -> "실선"
    GeometryLineStyle.DASHED -> "점선"
    GeometryLineStyle.DOTTED -> "점점선"
}

internal fun GeometryLineStyle.icon(): ConstructionIcon = when (this) {
    GeometryLineStyle.SOLID -> ConstructionIcon.LINE_SOLID
    GeometryLineStyle.DASHED -> ConstructionIcon.LINE_DASHED
    GeometryLineStyle.DOTTED -> ConstructionIcon.LINE_DOTTED
}

internal fun constructionButton(context: Context, label: String, icon: ConstructionIcon? = null, iconOnly: Boolean = false, onClick: () -> Unit): Button {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    val foreground = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(Color.rgb(160,167,162), Color.WHITE, Color.rgb(55,66,59)))
    fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
    val surface = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_selected), shape(Color.rgb(216,224,218)))
        addState(intArrayOf(android.R.attr.state_selected), shape(Color.rgb(91,119,101)))
        addState(intArrayOf(), shape(Color.TRANSPARENT))
    }
    return Button(context).apply {
        text = if (iconOnly && icon != null) "" else label
        textSize = 11f; isAllCaps = false; includeFontPadding = false; maxLines = 1
        ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        minWidth = dp(36); minimumWidth = dp(36); minHeight = dp(36); minimumHeight = dp(36)
        setPadding(dp(if (iconOnly) 9 else 8), 0, dp(if (iconOnly) 9 else 8), 0)
        setTextColor(foreground); backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40,69,93,76)), surface, shape(Color.WHITE))
        stateListAnimator = null; elevation = 0f; contentDescription = "작도 $label"; tooltipText = label; tag = label
        if (icon != null) {
            val glyph = ConstructionGlyph(icon, dp(18), foreground)
            glyph.setBounds(0,0,dp(18),dp(18)); compoundDrawablePadding = if (iconOnly) 0 else dp(5)
            setCompoundDrawablesRelative(glyph, null, null, null)
        }
        layoutParams = LinearLayout.LayoutParams(if (iconOnly && icon != null) dp(36) else ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
        setOnClickListener { onClick() }
    }
}

/** Original geometric glyphs; Lucide's 24-unit line-icon convention was reviewed, no paths/code copied. */
private class ConstructionGlyph(private val icon: ConstructionIcon, private val sizePx: Int, private val colors: ColorStateList) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.defaultColor; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val next = colors.getColorForState(state, colors.defaultColor)
        if (next == paint.color) return false
        paint.color = next; invalidateSelf(); return true
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width()/24f, bounds.height()/24f)
        paint.style = Paint.Style.STROKE
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1,y1,x2,y2,paint)
        fun dot(x: Float,y: Float,r: Float=1.3f) { paint.style=Paint.Style.FILL; canvas.drawCircle(x,y,r,paint); paint.style=Paint.Style.STROKE }
        fun path(block: Path.()->Unit) = canvas.drawPath(Path().apply(block),paint)
        fun undo() { path { moveTo(8f,5f); lineTo(3f,10f); lineTo(8f,15f) }; path { moveTo(3f,10f); lineTo(13f,10f); cubicTo(20f,10f,22f,14f,20f,19f) } }
        when(icon) {
            ConstructionIcon.SELECT -> path { moveTo(5f,3f); lineTo(19f,12f); lineTo(12f,13f); lineTo(9f,20f); close() }
            ConstructionIcon.POINT -> { canvas.drawCircle(12f,12f,2.8f,paint); dot(12f,12f,.8f) }
            ConstructionIcon.SEGMENT -> { line(5f,19f,19f,5f); canvas.drawCircle(5f,19f,2f,paint); canvas.drawCircle(19f,5f,2f,paint) }
            ConstructionIcon.CIRCLE -> { canvas.drawCircle(12f,12f,8f,paint); dot(12f,12f,.9f) }
            ConstructionIcon.LINE_SOLID -> line(3f,12f,21f,12f)
            ConstructionIcon.LINE_DASHED -> { line(3f,12f,6f,12f); line(10.5f,12f,13.5f,12f); line(18f,12f,21f,12f) }
            ConstructionIcon.LINE_DOTTED -> for (x in floatArrayOf(3f,7.5f,12f,16.5f,21f)) dot(x,12f,1f)
            ConstructionIcon.CONSTRAINT -> { canvas.drawCircle(8f,9f,4f,paint); canvas.drawCircle(16f,15f,4f,paint); line(10f,11f,14f,13f) }
            ConstructionIcon.MEASURE -> { canvas.rotate(-35f,12f,12f); canvas.drawRoundRect(RectF(3f,8f,21f,16f),1.3f,1.3f,paint); for(x in floatArrayOf(7f,11f,15f,19f)) line(x,8f,x,if(x==7f||x==15f)12f else 10.5f) }
            ConstructionIcon.LIST -> for(y in floatArrayOf(6f,12f,18f)) { dot(4f,y,1f); line(9f,y,21f,y) }
            ConstructionIcon.UNDO -> undo()
            ConstructionIcon.REDO -> { canvas.translate(24f,0f); canvas.scale(-1f,1f); undo() }
            ConstructionIcon.FIT -> { path { moveTo(9f,4f); lineTo(4f,4f); lineTo(4f,9f) }; path { moveTo(15f,4f); lineTo(20f,4f); lineTo(20f,9f) }; path { moveTo(4f,15f); lineTo(4f,20f); lineTo(9f,20f) }; path { moveTo(15f,20f); lineTo(20f,20f); lineTo(20f,15f) } }
            ConstructionIcon.CLOSE -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            ConstructionIcon.MORE -> { dot(5f,12f); dot(12f,12f); dot(19f,12f) }
            ConstructionIcon.MAGNET -> { line(6f,5f,6f,13f); line(18f,5f,18f,13f); canvas.drawArc(RectF(6f,7f,18f,19f),0f,180f,false,paint); line(4f,5f,8f,5f); line(16f,5f,20f,5f); line(4f,8f,8f,8f); line(16f,8f,20f,8f) }
            ConstructionIcon.DELETE -> { line(4f,6f,20f,6f); path { moveTo(9f,6f); lineTo(9f,3f); lineTo(15f,3f); lineTo(15f,6f) }; path { moveTo(6f,6f); lineTo(7f,21f); lineTo(17f,21f); lineTo(18f,6f) }; line(10f,10f,10.5f,17f); line(14f,10f,13.5f,17f) }
        }
        canvas.restoreToCount(save)
    }
}
