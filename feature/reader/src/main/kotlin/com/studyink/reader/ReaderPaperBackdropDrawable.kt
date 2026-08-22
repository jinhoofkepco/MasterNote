package com.studyink.reader

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Static paper drawn behind AndroidX PdfView.
 *
 * The palette and grain recipe intentionally mirror LibraryActivity's PaperBackdrop. PdfView paints
 * every PDF page over this drawable, so the texture is visible only in the surrounding margins and
 * never changes document pixels or the viewport used for ink coordinates.
 */
internal class ReaderPaperBackdropDrawable(
    private val density: Float,
) : Drawable() {
    private data class Speck(
        val x: Float,
        val y: Float,
        val radius: Float,
        val color: Int,
    )

    private data class Fiber(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val strokeWidth: Float,
        val color: Int,
    )

    private data class Mottle(
        val x: Float,
        val y: Float,
        val radius: Float,
        val shader: Shader,
    )

    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mottlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fiberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val speckPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var washShader: Shader? = null
    private var mottles: List<Mottle> = emptyList()
    private var fibers: List<Fiber> = emptyList()
    private var specks: List<Speck> = emptyList()
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildTexture(bounds.width().toFloat(), bounds.height().toFloat())
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return

        val saveCount = if (drawableAlpha < 255) {
            canvas.saveLayerAlpha(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                drawableAlpha,
            )
        } else {
            canvas.save()
        }
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())

        washPaint.shader = washShader
        washPaint.colorFilter = drawableColorFilter
        canvas.drawRect(0f, 0f, width, height, washPaint)

        mottlePaint.colorFilter = drawableColorFilter
        mottles.forEach { mottle ->
            mottlePaint.shader = mottle.shader
            canvas.drawCircle(mottle.x, mottle.y, mottle.radius, mottlePaint)
        }
        mottlePaint.shader = null

        fiberPaint.colorFilter = drawableColorFilter
        fibers.forEach { fiber ->
            fiberPaint.color = fiber.color
            fiberPaint.strokeWidth = fiber.strokeWidth
            canvas.drawLine(fiber.startX, fiber.startY, fiber.endX, fiber.endY, fiberPaint)
        }

        speckPaint.colorFilter = drawableColorFilter
        specks.forEach { speck ->
            speckPaint.color = speck.color
            canvas.drawCircle(speck.x, speck.y, speck.radius, speckPaint)
        }
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in the Android graphics API")
    override fun getOpacity(): Int = if (drawableAlpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    private fun rebuildTexture(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) {
            washShader = null
            mottles = emptyList()
            fibers = emptyList()
            specks = emptyList()
            return
        }

        val safeDensity = density.coerceAtLeast(1f)
        val areaDp = (width / safeDensity) * (height / safeDensity)
        val speckCount = (areaDp / 4_100f).roundToInt().coerceIn(110, 390)
        val fiberCount = (areaDp / 11_500f).roundToInt().coerceIn(42, 150)

        washShader = LinearGradient(
            -width * 0.08f,
            -height * 0.06f,
            width * 1.06f,
            height * 1.04f,
            intArrayOf(PAPER_WASH_LIGHT, PAPER_BACKGROUND, PAPER_WASH_SHADE),
            null,
            Shader.TileMode.CLAMP,
        )
        mottles = List(8) { index ->
            val light = paperNoise(index, 31) > 0.46f
            val x = paperNoise(index, 17) * width
            val y = paperNoise(index, 23) * height
            val radius = max(width, height) * (0.13f + paperNoise(index, 29) * 0.15f)
            val color = if (light) {
                withAlpha(PAPER_HIGHLIGHT, 0.030f + paperNoise(index, 37) * 0.022f)
            } else {
                withAlpha(PAPER_WARM_FIBER, 0.018f + paperNoise(index, 41) * 0.015f)
            }
            Mottle(
                x = x,
                y = y,
                radius = radius,
                shader = RadialGradient(x, y, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP),
            )
        }
        specks = List(speckCount) { index ->
            val light = paperNoise(index, 47) > 0.62f
            Speck(
                x = paperNoise(index, 53) * width,
                y = paperNoise(index, 59) * height,
                radius = (0.22f + paperNoise(index, 61) * 0.72f) * safeDensity,
                color = if (light) {
                    withAlpha(PAPER_HIGHLIGHT, 0.045f + paperNoise(index, 67) * 0.045f)
                } else {
                    withAlpha(PAPER_FIBER, 0.022f + paperNoise(index, 71) * 0.032f)
                },
            )
        }
        fibers = List(fiberCount) { index ->
            val startX = paperNoise(index, 73) * width
            val startY = paperNoise(index, 79) * height
            val length = (5f + paperNoise(index, 83) * 24f) * safeDensity
            val slope = (paperNoise(index, 89) - 0.5f) * 0.72f
            val light = paperNoise(index, 97) > 0.7f
            Fiber(
                startX = startX,
                startY = startY,
                endX = (startX + length).coerceAtMost(width),
                endY = (startY + length * slope).coerceIn(0f, height),
                strokeWidth = (0.28f + paperNoise(index, 101) * 0.52f) * safeDensity,
                color = if (light) {
                    withAlpha(PAPER_HIGHLIGHT, 0.055f + paperNoise(index, 103) * 0.050f)
                } else {
                    withAlpha(PAPER_WARM_FIBER, 0.027f + paperNoise(index, 107) * 0.033f)
                },
            )
        }
    }

    private fun paperNoise(index: Int, channel: Int): Float {
        val raw = sin((index + 1) * 12.9898 + (channel + 1) * 78.233) * 43_758.5453
        return (raw - floor(raw)).toFloat()
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    internal companion object {
        /** Opaque midpoint of the paper wash, also used for the system navigation bar. */
        const val NAVIGATION_BAR_COLOR: Int = 0xFFF2EEE5.toInt()

        private const val PAPER_WASH_LIGHT: Int = 0xFFF9F6EF.toInt()
        private const val PAPER_BACKGROUND: Int = NAVIGATION_BAR_COLOR
        private const val PAPER_WASH_SHADE: Int = 0xFFEDE7DC.toInt()
        private const val PAPER_HIGHLIGHT: Int = 0xFFFFFFFF.toInt()
        private const val PAPER_FIBER: Int = 0xFF9C907E.toInt()
        private const val PAPER_WARM_FIBER: Int = 0xFFC6B79F.toInt()
    }
}
