package io.knura.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The small moving extras of a holiday theme, drawn on a canvas: falling snow, rising embers,
 * drifting leaves and confetti, floating hearts, twinkling stars ([ParticlesView]); the decoration
 * under the home header ([StripView]); and the lights around the tap button ([RingView]).
 * With animations turned off on the phone, each one shows a still frame.
 */
private fun loop(view: View, periodMs: Long, onFrame: (Float) -> Unit): ValueAnimator? {
    if (!ValueAnimator.areAnimatorsEnabled()) return null
    return ValueAnimator.ofFloat(0f, 1f).apply {
        duration = periodMs
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { onFrame(it.animatedValue as Float); view.invalidate() }
        start()
    }
}

private fun withAlpha(color: Int, a: Float): Int = Themes.withAlpha(color, (Color.alpha(color) * a.coerceIn(0f, 1f)).toInt())

/** A heart 1 unit wide, centered on 0,0. */
private val HEART = Path().apply {
    moveTo(0f, 0.42f)
    cubicTo(-0.55f, 0.05f, -0.55f, -0.45f, -0.22f, -0.45f)
    cubicTo(-0.08f, -0.45f, 0f, -0.35f, 0f, -0.25f)
    cubicTo(0f, -0.35f, 0.08f, -0.45f, 0.22f, -0.45f)
    cubicTo(0.55f, -0.45f, 0.55f, 0.05f, 0f, 0.42f)
    close()
}

/** A five-point star 1 unit wide, centered on 0,0. */
private val STAR = Path().apply {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) 0.5f else 0.21f
        val a = -PI / 2 + i * PI / 5
        val x = (cos(a) * r).toFloat()
        val y = (sin(a) * r).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

class ParticlesView(context: Context, private val holiday: Holidays.Holiday, private val s: Themes.Season) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private var t = 0f
    private var animator: ValueAnimator? = null
    private val count = 22

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = loop(this, 60_000) { t = it }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        s.moon?.let { moon ->
            paint.color = withAlpha(moon, 0.25f)
            canvas.drawCircle(w * 0.72f, -10 * d, 70 * d, paint)
            paint.color = moon
            canvas.drawCircle(w * 0.72f, -10 * d, 42 * d, paint)
        }
        for (i in 0 until count) {
            val x0 = ((i * 37 + 11) % 97) / 100f * w
            val period = 14f + (i * 7) % 12 // seconds for one trip
            val still = animator == null
            val p = if (still) ((i * 53 + 29) % 95) / 100f else ((t * 60f / period) + i * 0.137f) % 1f
            val colors = intArrayOf(s.a, s.b, s.c)
            when (holiday) {
                Holidays.Holiday.CHRISTMAS -> {
                    paint.color = s.particle
                    canvas.drawCircle(x0 + sin(p * 6.28f + i) * 6 * d, p * (h + 20 * d) - 10 * d, 1.6f * d, paint)
                }
                Holidays.Holiday.HALLOWEEN -> {
                    val y = h - p * h * 0.85f
                    paint.color = withAlpha(s.particle, if (p < 0.15f) p / 0.15f else 1f - (p - 0.15f) / 0.85f)
                    canvas.drawCircle(x0, y, 1.6f * d, paint)
                }
                Holidays.Holiday.VALENTINE -> {
                    val y = h - p * h * 0.85f
                    paint.color = withAlpha(if (i % 2 == 0) s.a else s.c, 0.55f * (1f - p))
                    drawShape(canvas, HEART, x0, y, (8 + (i % 3) * 2) * d, 0f)
                }
                Holidays.Holiday.JULY4 -> {
                    val y = ((i * 53 + 29) % 95) / 100f * h
                    val tw = if (still) 0.45f else 0.15f + 0.35f * (0.5f + 0.5f * sin((t * 60f / period + i) * 6.28f))
                    paint.color = withAlpha(if (i % 3 == 0) s.c else s.particle, tw)
                    drawShape(canvas, STAR, x0, y, (6 + (i % 3) * 2) * d, 0f)
                }
                Holidays.Holiday.THANKSGIVING, Holidays.Holiday.NEW_YEAR -> {
                    paint.color = withAlpha(colors[i % 3], 0.65f)
                    val x = x0 + sin(p * 6.28f + i) * 16 * d
                    val y = p * (h + 24 * d) - 12 * d
                    canvas.save()
                    canvas.translate(x, y)
                    canvas.rotate(p * 340f + i * 20)
                    if (holiday == Holidays.Holiday.THANKSGIVING) {
                        val r = 4.5f * d
                        canvas.drawPath(Path().apply {
                            moveTo(-r, r); quadTo(-r, -r, r, -r); quadTo(r, r, -r, r); close()
                        }, paint)
                    } else {
                        canvas.drawRect(-1.5f * d, -4 * d, 1.5f * d, 4 * d, paint)
                    }
                    canvas.restore()
                }
            }
        }
    }

    private fun drawShape(canvas: Canvas, path: Path, x: Float, y: Float, size: Float, angle: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle)
        canvas.scale(size, size)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}

/** The decoration under the home header: lights, a pumpkin patch, leaves, fireworks, hearts or bunting. */
class StripView(context: Context, private val holiday: Holidays.Holiday, private val s: Themes.Season, private val wire: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val d = resources.displayMetrics.density
    private var t = 0f
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** How tall to make it, in dp. */
    val heightDp: Int get() = when (holiday) {
        Holidays.Holiday.HALLOWEEN, Holidays.Holiday.NEW_YEAR -> 38
        Holidays.Holiday.VALENTINE, Holidays.Holiday.JULY4 -> 24
        else -> 16
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = loop(this, 4_000) { t = it }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun pulse(i: Int): Float = if (animator == null) 1f else 0.65f + 0.35f * cos((t + i * 0.5f) * 2 * PI).toFloat()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val colors = intArrayOf(s.a, s.b, s.c)
        line.strokeWidth = 1f * d
        line.color = withAlpha(wire, 0.2f)
        when (holiday) {
            Holidays.Holiday.CHRISTMAS -> {
                canvas.drawLine(0f, 3 * d, w, 3 * d, line)
                val n = (w / (22 * d)).toInt().coerceAtLeast(8)
                for (i in 0 until n) {
                    val x = (i + 0.5f) * w / n
                    val c = colors[i % 3]
                    paint.color = withAlpha(c, 0.25f * pulse(i))
                    canvas.drawCircle(x, 9 * d, 6 * d, paint)
                    paint.color = withAlpha(c, pulse(i))
                    canvas.drawOval(RectF(x - 3 * d, 4.5f * d, x + 3 * d, 13 * d), paint)
                }
            }
            Holidays.Holiday.THANKSGIVING -> {
                line.color = withAlpha(s.c, 0.4f)
                canvas.drawLine(0f, 5 * d, w, 5 * d, line)
                val n = (w / (20 * d)).toInt().coerceAtLeast(8)
                val r = 5 * d
                for (i in 0 until n) {
                    paint.color = colors[i % 3]
                    canvas.save()
                    canvas.translate((i + 0.5f) * w / n, 6 * d)
                    canvas.rotate(if (i % 2 == 0) 0f else 90f)
                    canvas.drawPath(Path().apply { moveTo(-r, r); quadTo(-r, -r, r, -r); quadTo(r, r, -r, r); close() }, paint)
                    canvas.restore()
                }
            }
            Holidays.Holiday.HALLOWEEN -> {
                // Faint graves in the distance, a dirt mound, and pumpkins sitting on it.
                paint.color = s.grave
                for ((x, gh) in listOf(0.06f to 15f, 0.19f to 11f, 0.33f to 17f, 0.47f to 12f, 0.61f to 16f, 0.76f to 11f, 0.90f to 15f)) {
                    val gw = gh * 0.7f * d
                    val top = h - 9 * d - gh * d
                    canvas.drawRoundRect(RectF(w * x - gw / 2, top, w * x + gw / 2, h - 6 * d), gw / 2, gw / 2, paint)
                }
                paint.color = s.dirt
                canvas.drawPath(Path().apply {
                    moveTo(0f, h - 7 * d)
                    cubicTo(w * 0.25f, h - 10 * d, w * 0.5f, h - 6 * d, w * 0.7f, h - 9 * d)
                    cubicTo(w * 0.85f, h - 11 * d, w * 0.95f, h - 8 * d, w, h - 9 * d)
                    lineTo(w, h); lineTo(0f, h); close()
                }, paint)
                for ((i, p) in listOf(0.12f to 1f, 0.22f to 0.75f, 0.44f to 1.15f, 0.53f to 0.8f, 0.71f to 1f, 0.86f to 0.85f).withIndex()) {
                    pumpkin(canvas, w * p.first, h - 6 * d, p.second, pulse(i))
                }
            }
            Holidays.Holiday.NEW_YEAR -> {
                for ((i, f) in listOf(0.08f to 11f, 0.24f to 8f, 0.41f to 13f, 0.58f to 9f, 0.74f to 12f, 0.91f to 9f).withIndex()) {
                    val phase = if (animator == null) 0.3f else (t * 4f / 3.6f + i * 0.33f) % 1f
                    val grow = (phase / 0.12f).coerceAtMost(1f)
                    val fade = if (phase < 0.55f) 1f else (1f - (phase - 0.55f) / 0.25f).coerceAtLeast(0f)
                    line.color = withAlpha(colors[i % 3], fade)
                    line.strokeWidth = 1.3f * d
                    line.strokeCap = Paint.Cap.ROUND
                    val r = f.second * d * (0.3f + 0.7f * grow)
                    val cx = w * f.first
                    val cy = h / 2
                    for (k in 0 until 12) {
                        val a = k / 12f * 2 * PI.toFloat()
                        canvas.drawLine(cx + cos(a) * r * 0.3f, cy + sin(a) * r * 0.3f, cx + cos(a) * r * 0.85f, cy + sin(a) * r * 0.85f, line)
                    }
                }
            }
            Holidays.Holiday.VALENTINE, Holidays.Holiday.JULY4 -> {
                canvas.drawLine(0f, 3 * d, w, 3 * d, line)
                val n = (w / ((if (holiday == Holidays.Holiday.VALENTINE) 24 else 20) * d)).toInt().coerceAtLeast(8)
                for (i in 0 until n) {
                    val x = (i + 0.5f) * w / n
                    val sway = if (animator == null) 0f else 6f * sin((t + i * 0.5f) * 2 * PI).toFloat()
                    canvas.save()
                    canvas.translate(x, 3 * d)
                    canvas.rotate(sway)
                    if (holiday == Holidays.Holiday.VALENTINE) {
                        paint.color = colors[i % 3]
                        val size = (if (i % 2 == 0) 15 else 12) * d
                        canvas.translate(0f, size * 0.5f)
                        canvas.scale(size, size)
                        canvas.drawPath(HEART, paint)
                    } else {
                        paint.color = colors[i % 3]
                        canvas.drawPath(Path().apply { moveTo(-7 * d, 0f); lineTo(7 * d, 0f); lineTo(0f, 16 * d); close() }, paint)
                        if (i % 3 == 0) {
                            paint.color = s.b
                            canvas.translate(0f, 6 * d)
                            canvas.scale(6 * d, 6 * d)
                            canvas.drawPath(STAR, paint)
                        }
                    }
                    canvas.restore()
                }
            }
        }
    }

    /** A smiling jack-o'-lantern sitting on [bottom], [scale] 1 is about 20dp wide. */
    private fun pumpkin(canvas: Canvas, cx: Float, bottom: Float, scale: Float, glow: Float) {
        val u = d * scale
        val cy = bottom - 6.3f * u
        paint.color = withAlpha(s.a, 0.3f * glow)
        canvas.drawCircle(cx, cy, 11 * u, paint)
        line.color = Color.parseColor("#6E7B34")
        line.strokeWidth = 1.6f * u
        canvas.drawLine(cx, cy - 6 * u, cx + 2.4f * u, cy - 8.5f * u, line)
        paint.color = s.a
        canvas.drawOval(RectF(cx - 9 * u, cy - 6.8f * u, cx + 9 * u, cy + 6.8f * u), paint)
        paint.color = s.face
        canvas.drawPath(Path().apply {
            moveTo(cx - 5 * u, cy - 1.5f * u); lineTo(cx - 2.6f * u, cy - 3.2f * u); lineTo(cx - 1.8f * u, cy - 0.6f * u); close()
            moveTo(cx + 5 * u, cy - 1.5f * u); lineTo(cx + 2.6f * u, cy - 3.2f * u); lineTo(cx + 1.8f * u, cy - 0.6f * u); close()
            moveTo(cx - 4.6f * u, cy + 1.4f * u)
            quadTo(cx, cy + 5.5f * u, cx + 4.6f * u, cy + 1.4f * u)
            lineTo(cx + 2.8f * u, cy + 2.2f * u); lineTo(cx + 1.5f * u, cy + 1.6f * u); lineTo(cx, cy + 2.4f * u)
            lineTo(cx - 1.5f * u, cy + 1.6f * u); lineTo(cx - 2.8f * u, cy + 2.2f * u); close()
        }, paint)
    }
}

/** Twelve small lights in a ring, drawn around the tap button. */
class RingView(context: Context, private val s: Themes.Season) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private var t = 0f
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = loop(this, 3_200) { t = it }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 4 * d
        val colors = intArrayOf(s.a, s.b, s.c)
        for (i in 0 until 12) {
            val a = i / 12f * 2 * PI.toFloat() - PI.toFloat() / 2
            val on = if (animator == null) 1f else 0.6f + 0.4f * cos((t + (i % 2) * 0.5f) * 2 * PI).toFloat()
            val c = colors[i % 3]
            paint.color = withAlpha(c, 0.3f * on)
            canvas.drawCircle(cx + cos(a) * r, cy + sin(a) * r, 4.5f * d, paint)
            paint.color = withAlpha(c, on)
            canvas.drawCircle(cx + cos(a) * r, cy + sin(a) * r, 2.5f * d, paint)
        }
    }
}
