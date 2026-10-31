package io.knura.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The Knura mark (two rounded squares around a core) drawn on a dark tile, and animated:
 *
 * - [Mode.SCANNING]: a light runs round the outer square, then the inner one, then the core flashes
 *   and a faint square ripples out, every 2.6 seconds. Waiting for a tag.
 * - [Mode.CONNECTING]: lights circle both squares in opposite directions while the core breathes.
 * - [Mode.VERIFIED] and [Mode.REJECTED]: once, the squares turn green (or red), the core grows and a
 *   check (or a cross) draws in.
 * - [Mode.STILL], [Mode.DIM] (server offline) and [Mode.DASHED] (no tags yet) don't move.
 *
 * The tile takes two thirds of the view; the rest is room for the glow and the ripple. With
 * animations turned off on the phone, each mode shows its resting frame.
 */
class MarkView(context: Context, private var mode: Mode = Mode.STILL, private val showTile: Boolean = true) : View(context) {
    enum class Mode { STILL, SCANNING, CONNECTING, VERIFIED, REJECTED, DIM, DASHED }

    private val orange = Color.parseColor("#F28C3A")
    private val trace = Color.parseColor("#FFB86B")
    private val core = Color.parseColor("#E8EDF3")
    private val ok = Color.parseColor("#4ADE9B")
    private val bad = Color.parseColor("#F87171")
    private val grey = Color.parseColor("#8B97A6")
    private val ink = Color.parseColor("#0A0D12")

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val outer = Path().apply { addRoundRect(RectF(2f, 2f, 30f, 30f), 8f, 8f, Path.Direction.CW) }
    private val middle = Path().apply { addRoundRect(RectF(8.5f, 8.5f, 23.5f, 23.5f), 4.5f, 4.5f, Path.Direction.CW) }
    private val outerMeasure = PathMeasure(outer, true)
    private val middleMeasure = PathMeasure(middle, true)
    private val segment = Path()
    private val check = Path().apply { moveTo(13f, 16.1f); rLineTo(2.1f, 2.1f); rLineTo(4f, -4.2f) }
    private val cross = Path().apply { moveTo(14f, 14f); rLineTo(4f, 4f); moveTo(18f, 14f); rLineTo(-4f, 4f) }
    private val markMeasure = PathMeasure()
    private val rect = RectF()

    private var t = 0f
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        restart()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun restart() {
        animator?.cancel()
        animator = null
        val animated = ValueAnimator.areAnimatorsEnabled()
        val (duration, loop) = when (mode) {
            Mode.SCANNING -> 2600L to true
            Mode.CONNECTING -> 4800L to true
            Mode.VERIFIED, Mode.REJECTED -> 1400L to false
            else -> 0L to false
        }
        if (duration == 0L || !animated || !isAttachedToWindow) {
            // The resting frame: lights showing while waiting, the finished result.
            t = when (mode) {
                Mode.SCANNING -> 0.2f
                Mode.CONNECTING -> 0.1f
                else -> 1f
            }
            invalidate()
            return
        }
        t = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            if (loop) repeatCount = ValueAnimator.INFINITE
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val tile = if (showTile) size / 1.5f else size
        val cx = width / 2f
        val cy = height / 2f
        val radius = tile * 0.28f
        val status = when (mode) {
            Mode.VERIFIED -> ok
            Mode.REJECTED -> bad
            else -> 0
        }

        // Glow and ripple behind the tile.
        val glow = when (mode) {
            Mode.SCANNING -> ramp(t, 0.68f, 0.80f) - ramp(t, 0.80f, 1f)
            Mode.VERIFIED, Mode.REJECTED -> ramp(t, 0.4f, 0.7f)
            else -> 0f
        }
        if (glow > 0f && showTile) {
            val c = if (status != 0) status else orange
            glowPaint.shader = RadialGradient(cx, cy, tile * 0.85f, intArrayOf(alpha(c, 0.45f * glow), alpha(c, 0f)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, tile * 0.85f, glowPaint)
        }
        val echo = when (mode) {
            Mode.SCANNING -> if (t >= 0.76f) (t - 0.76f) / 0.24f else -1f
            Mode.VERIFIED, Mode.REJECTED -> if (t in 0.5f..1f && animator != null) (t - 0.5f) / 0.5f else -1f
            else -> -1f
        }
        if (echo >= 0f && showTile) {
            val s = tile * (1f + 0.45f * echo)
            stroke.strokeWidth = 1.5f * resources.displayMetrics.density
            stroke.pathEffect = null
            stroke.color = alpha(if (status != 0) status else trace, 0.8f * (1f - echo))
            rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
            canvas.drawRoundRect(rect, radius * (s / tile), radius * (s / tile), stroke)
        }
        if (showTile) {
            fill.color = Color.argb(170, 10, 13, 18)
            rect.set(cx - tile / 2, cy - tile / 2, cx + tile / 2, cy + tile / 2)
            canvas.drawRoundRect(rect, radius, radius, fill)
            stroke.strokeWidth = resources.displayMetrics.density
            stroke.pathEffect = null
            stroke.color = when (mode) {
                Mode.VERIFIED, Mode.REJECTED -> blend(Color.argb(36, 255, 255, 255), alpha(status, 0.4f), ramp(t, 0.4f, 0.7f))
                else -> Color.argb(36, 255, 255, 255)
            }
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }

        // The mark itself, in its own 32-unit space.
        val markSize = if (showTile) tile * 0.62f else size
        canvas.save()
        canvas.translate(cx - markSize / 2, cy - markSize / 2)
        canvas.scale(markSize / 32f, markSize / 32f)
        stroke.pathEffect = null
        val squares: Int
        val coreColor: Int
        var coreScale = 1f
        var coreAlpha = 1f
        when (mode) {
            Mode.SCANNING, Mode.CONNECTING -> {
                squares = alpha(orange, 0.30f)
                coreColor = core
                if (mode == Mode.SCANNING) {
                    coreAlpha = 0.5f + 0.5f * (ramp(t, 0.68f, 0.80f) - ramp(t, 0.80f, 1f))
                    coreScale = 1f + 0.18f * (ramp(t, 0.68f, 0.80f) - ramp(t, 0.80f, 1f))
                } else {
                    coreAlpha = 0.55f + 0.45f * (0.5f + 0.5f * Math.sin(t * 3 * 2 * Math.PI).toFloat())
                }
            }
            Mode.VERIFIED, Mode.REJECTED -> {
                squares = blend(orange, status, ramp(t, 0.30f, 0.60f))
                coreColor = blend(core, status, ramp(t, 0.35f, 0.60f))
                coreScale = 1f + 0.8f * ramp(t, 0.35f, 0.60f)
            }
            Mode.DIM -> {
                squares = alpha(grey, 0.55f)
                coreColor = alpha(grey, 0.5f)
            }
            Mode.DASHED -> {
                squares = alpha(orange, 0.55f)
                coreColor = alpha(core, 0.35f)
                stroke.pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
            }
            Mode.STILL -> {
                squares = orange
                coreColor = core
            }
        }
        stroke.color = squares
        stroke.strokeWidth = 2f
        canvas.drawPath(outer, stroke)
        stroke.strokeWidth = 1.9f
        if (mode == Mode.DASHED) stroke.pathEffect = DashPathEffect(floatArrayOf(2.5f, 2.5f), 0f)
        if (mode == Mode.VERIFIED || mode == Mode.REJECTED) stroke.color = blend(orange, status, ramp(t, 0.37f, 0.67f))
        canvas.drawPath(middle, stroke)
        stroke.pathEffect = null

        if (mode == Mode.SCANNING) {
            val outerShow = ramp(t, 0f, 0.08f) - ramp(t, 0.40f, 0.52f)
            val middleShow = ramp(t, 0.34f, 0.44f) - ramp(t, 0.70f, 0.80f)
            traceOn(canvas, outerMeasure, (t * 2.6f / 1.2f) % 1f, 0.26f, 2.2f, outerShow)
            traceOn(canvas, middleMeasure, 1f - (t * 2.6f / 0.9f) % 1f, 0.30f, 2.1f, middleShow)
        } else if (mode == Mode.CONNECTING) {
            traceOn(canvas, outerMeasure, (t * 3f) % 1f, 0.26f, 2.2f, 1f)
            traceOn(canvas, middleMeasure, 1f - (t * 4f) % 1f, 0.30f, 2.1f, 1f)
        }

        fill.color = alpha(coreColor, coreAlpha * Color.alpha(coreColor) / 255f)
        canvas.save()
        canvas.scale(coreScale, coreScale, 16f, 16f)
        rect.set(12.75f, 12.75f, 19.25f, 19.25f)
        canvas.drawRoundRect(rect, 1.8f, 1.8f, fill)
        canvas.restore()

        if (mode == Mode.VERIFIED || mode == Mode.REJECTED) {
            val drawn = ramp(t, 0.61f, 0.93f)
            if (drawn > 0f) {
                markMeasure.setPath(if (mode == Mode.VERIFIED) check else cross, false)
                segment.reset()
                do {
                    markMeasure.getSegment(0f, markMeasure.length * drawn, segment, true)
                } while (markMeasure.nextContour())
                stroke.color = ink
                stroke.strokeWidth = 1.9f
                stroke.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(segment, stroke)
            }
        }
        canvas.restore()
    }

    /** A light [length] long (as a fraction of the path) starting at [start], wrapping round. */
    private fun traceOn(canvas: Canvas, measure: PathMeasure, start: Float, length: Float, width: Float, opacity: Float) {
        if (opacity <= 0f) return
        val total = measure.length
        segment.reset()
        val from = start * total
        val to = from + length * total
        measure.getSegment(from, minOf(to, total), segment, true)
        if (to > total) measure.getSegment(0f, to - total, segment, true)
        stroke.color = alpha(trace, opacity)
        stroke.strokeWidth = width
        canvas.drawPath(segment, stroke)
    }

    private companion object {
        /** 0 before [a], 1 after [b], straight in between. */
        fun ramp(x: Float, a: Float, b: Float): Float = ((x - a) / (b - a)).coerceIn(0f, 1f)

        fun alpha(color: Int, a: Float): Int = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        fun blend(from: Int, to: Int, f: Float): Int = Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt())
    }
}
