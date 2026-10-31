package io.knura.android

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The background of the welcome, pairing and scan screens: dark gray with a soft Knura-orange glow
 * that drifts slowly around the screen. It stands still when the phone has animations turned off.
 */
class GlowView(context: Context) : View(context) {
    private val base = Paint()
    private val big = Paint(Paint.ANTI_ALIAS_FLAG)
    private val small = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bigMatrix = Matrix()
    private val smallMatrix = Matrix()
    private var seconds = 0f
    private var animator: TimeAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        base.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#3A2414"), Color.parseColor("#1A1E25"), Color.parseColor("#12161C")),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        // Both glows are drawn at the origin with radius 1 and moved into place by a matrix each frame.
        big.shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.parseColor("#F28C3A"), Color.parseColor("#C9651F"), Color.argb(191, 120, 62, 24), Color.argb(0, 26, 30, 37)),
            floatArrayOf(0f, 0.30f, 0.58f, 1f), Shader.TileMode.CLAMP)
        small.shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(97, 242, 140, 58), Color.argb(64, 120, 62, 24), Color.argb(0, 26, 30, 37)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) {
            animator = TimeAnimator().apply {
                setTimeListener { _, total, _ -> seconds = total / 1000f; invalidate() }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, base)
        val tau = (2 * Math.PI).toFloat()
        // Slow, never-quite-repeating paths: 16 and 11 second swings for the big glow, 21 and 13 for the small one.
        val bx = w * 0.5f + w * 0.16f * sin(tau * seconds / 16f)
        val by = h * 0.20f + h * 0.09f * sin(tau * seconds / 11f + 1f)
        val br = w * 0.95f * (1f + 0.06f * sin(tau * seconds / 9f))
        bigMatrix.setScale(br, br)
        bigMatrix.postTranslate(bx, by)
        big.shader.setLocalMatrix(bigMatrix)
        canvas.drawCircle(bx, by, br, big)

        val sx = w * 0.32f + w * 0.24f * sin(tau * seconds / 21f + 2f)
        val sy = h * 0.66f + h * 0.10f * cos(tau * seconds / 13f)
        val sr = w * 0.62f
        smallMatrix.setScale(sr, sr)
        smallMatrix.postTranslate(sx, sy)
        small.shader.setLocalMatrix(smallMatrix)
        canvas.drawCircle(sx, sy, sr, small)
    }
}

/** Seven small bars, oldest day first, today in orange: taps over the last week. */
class BarsView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values = IntArray(7)
    private val muted = context.getColor(R.color.fg)
    private val accent = context.getColor(R.color.accent_stroke)

    fun setValues(days: IntArray) {
        values = days
        contentDescription = "Taps over the last 7 days: " + days.joinToString(", ") + ", today last"
        invalidate()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val gap = width * 0.06f
        val barW = (width - gap * (n - 1)) / n
        val max = maxOf(1, values.maxOrNull() ?: 1)
        val r = barW * 0.3f
        for (i in 0 until n) {
            val h = maxOf(height * 0.12f, height * values[i] / max.toFloat())
            val x = i * (barW + gap)
            paint.color = if (i == n - 1) accent else Color.argb(56, Color.red(muted), Color.green(muted), Color.blue(muted))
            canvas.drawRoundRect(x, height - h, x + barW, height.toFloat(), r, r, paint)
        }
    }
}
