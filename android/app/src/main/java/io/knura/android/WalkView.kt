package io.knura.android

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * The pictures of the first-tag walkthrough, one per step, each a short loop: the tag bobbing, the
 * tag sliding onto the back of the phone, an action being picked, the tag being stuck on, and a tap
 * turning the tag green. With animations off each shows one still frame.
 */
class WalkView(context: Context, private val step: Int) : View(context) {
    private val orange = Color.parseColor("#F28C3A")
    private val green = Color.parseColor("#4ADE9B")
    private val fg = Color.parseColor("#E8EDF3")
    private val muted = Color.parseColor("#8B97A6")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, 600, false) }
    private val rect = RectF()
    private var seconds = 0f
    private var animator: TimeAnimator? = null
    private val still = !ValueAnimator.areAnimatorsEnabled()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!still) {
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

    /** Where in a loop of [period] seconds we are, 0 to 1; [rest] is the still frame. */
    private fun phase(period: Float, rest: Float): Float = if (still) rest else (seconds % period) / period

    /** 0 before [a], 1 after [b], eased in between. */
    private fun ramp(t: Float, a: Float, b: Float): Float {
        val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    private fun alpha(color: Int, a: Float) = Color.argb((Color.alpha(color) * a).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        canvas.save()
        canvas.translate((width - s) / 2, (height - s) / 2)
        when (step) {
            0 -> blankTag(canvas, s)
            1 -> holdToPhone(canvas, s)
            2 -> pickAction(canvas, s)
            3 -> stickIt(canvas, s)
            else -> tryIt(canvas, s)
        }
        canvas.restore()
    }

    /** A round tag with the Knura mark; [ring] is its edge color, [core] the mark's center. */
    private fun tag(canvas: Canvas, cx: Float, cy: Float, r: Float, ring: Int = orange, core: Int = fg, coreScale: Float = 1f) {
        fill.color = Color.parseColor("#1C232D")
        canvas.drawCircle(cx, cy, r, fill)
        stroke.strokeWidth = r * 0.06f
        stroke.color = ring
        canvas.drawCircle(cx, cy, r * 0.97f, stroke)
        val q = r * 0.42f
        stroke.strokeWidth = r * 0.07f
        stroke.color = alpha(fg, 0.85f)
        rect.set(cx - q, cy - q, cx + q, cy + q)
        canvas.drawRoundRect(rect, q * 0.3f, q * 0.3f, stroke)
        val i = q * 0.55f
        stroke.color = ring
        rect.set(cx - i, cy - i, cx + i, cy + i)
        canvas.drawRoundRect(rect, i * 0.3f, i * 0.3f, stroke)
        fill.color = core
        val c = q * 0.2f * coreScale
        rect.set(cx - c, cy - c, cx + c, cy + c)
        canvas.drawRoundRect(rect, c * 0.35f, c * 0.35f, fill)
    }

    private fun phone(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        fill.color = Color.argb(20, 255, 255, 255)
        canvas.drawRoundRect(rect, w * 0.16f, w * 0.16f, fill)
        stroke.strokeWidth = w * 0.025f
        stroke.color = Color.argb(90, 255, 255, 255)
        canvas.drawRoundRect(rect, w * 0.16f, w * 0.16f, stroke)
        // The camera, so it reads as the back of the phone.
        fill.color = Color.argb(60, 255, 255, 255)
        rect.set(cx - w * 0.36f, cy - h / 2 + w * 0.1f, cx - w * 0.06f, cy - h / 2 + w * 0.4f)
        canvas.drawRoundRect(rect, w * 0.08f, w * 0.08f, fill)
    }

    private fun blankTag(canvas: Canvas, s: Float) {
        val t = phase(3f, 0.25f)
        val wave = sin(2 * PI * t).toFloat()
        canvas.save()
        canvas.rotate(4f * wave, s / 2, s / 2)
        tag(canvas, s / 2, s / 2 - s * 0.035f * wave, s * 0.26f)
        canvas.restore()
        fill.color = alpha(Color.BLACK, 0.25f - 0.08f * wave)
        rect.set(s * 0.34f, s * 0.86f, s * 0.66f, s * 0.9f)
        canvas.drawOval(rect, fill)
    }

    private fun holdToPhone(canvas: Canvas, s: Float) {
        val t = phase(3.2f, 0.6f)
        val cx = s / 2
        val cy = s / 2
        phone(canvas, cx, cy, s * 0.46f, s * 0.86f)
        // The tag slides in to the middle of the phone, waits, and slides back out.
        val inAmount = ramp(t, 0.08f, 0.42f) * (1 - ramp(t, 0.74f, 1f))
        val tx = cx + s * 0.3f * (1 - inAmount)
        val ty = cy + s * 0.22f * (1 - inAmount)
        val waves = ramp(t, 0.40f, 0.52f) * (1 - ramp(t, 0.72f, 0.84f))
        if (waves > 0f) {
            stroke.strokeWidth = s * 0.008f
            for ((k, r) in listOf(0.19f, 0.25f).withIndex()) {
                stroke.color = alpha(orange, waves * (0.8f - k * 0.3f))
                canvas.drawCircle(cx, cy, s * r * (0.9f + 0.1f * waves), stroke)
            }
        }
        tag(canvas, tx, ty, s * 0.13f)
    }

    private fun pickAction(canvas: Canvas, s: Float) {
        val t = phase(3.4f, 0.6f)
        val picked = ramp(t, 0.30f, 0.45f) * (1 - ramp(t, 0.90f, 1f))
        val left = s * 0.06f
        val right = s * 0.94f
        val h = s * 0.25f
        val tops = listOf(s * 0.2f, s * 0.53f)
        val titles = listOf("Home Assistant", "Send a webhook")
        val hints = listOf("Turn on the hallway light", "Tell another app")
        for (i in 0..1) {
            rect.set(left, tops[i], right, tops[i] + h)
            val on = if (i == 0) picked else 0f
            fill.color = mix(Color.parseColor("#1A2028"), Color.parseColor("#3A2616"), on)
            canvas.drawRoundRect(rect, s * 0.06f, s * 0.06f, fill)
            stroke.strokeWidth = s * 0.006f
            stroke.color = mix(Color.parseColor("#2A323C"), orange, on)
            canvas.drawRoundRect(rect, s * 0.06f, s * 0.06f, stroke)
            fill.color = Color.argb(30, 255, 255, 255)
            val box = s * 0.12f
            val by = tops[i] + (h - box) / 2
            rect.set(left + s * 0.05f, by, left + s * 0.05f + box, by + box)
            canvas.drawRoundRect(rect, box * 0.3f, box * 0.3f, fill)
            stroke.color = if (i == 0) orange else fg
            stroke.strokeWidth = s * 0.008f
            val ix = left + s * 0.05f + box / 2
            val iy = by + box / 2
            if (i == 0) {
                canvas.drawLine(ix - box * 0.25f, iy + box * 0.05f, ix, iy - box * 0.22f, stroke)
                canvas.drawLine(ix, iy - box * 0.22f, ix + box * 0.25f, iy + box * 0.05f, stroke)
                canvas.drawLine(ix - box * 0.17f, iy, ix - box * 0.17f, iy + box * 0.25f, stroke)
                canvas.drawLine(ix + box * 0.17f, iy, ix + box * 0.17f, iy + box * 0.25f, stroke)
                canvas.drawLine(ix - box * 0.17f, iy + box * 0.25f, ix + box * 0.17f, iy + box * 0.25f, stroke)
            } else {
                canvas.drawCircle(ix - box * 0.15f, iy + box * 0.12f, box * 0.1f, stroke)
                canvas.drawCircle(ix + box * 0.18f, iy + box * 0.12f, box * 0.1f, stroke)
                canvas.drawCircle(ix, iy - box * 0.18f, box * 0.1f, stroke)
            }
            val tx = left + s * 0.22f
            text.color = fg
            text.textSize = s * 0.056f
            canvas.drawText(titles[i], tx, tops[i] + h * 0.45f, text)
            text.color = muted
            text.textSize = s * 0.044f
            canvas.drawText(hints[i], tx, tops[i] + h * 0.72f, text)
        }
        // The check on the picked card.
        val tick = ramp(t, 0.40f, 0.50f) * (1 - ramp(t, 0.90f, 1f))
        if (tick > 0f) {
            val cx = right - s * 0.09f
            val cy = tops[0] + h / 2
            fill.color = alpha(orange, tick)
            canvas.drawCircle(cx, cy, s * 0.045f * (0.5f + 0.5f * tick), fill)
            stroke.color = alpha(Color.parseColor("#160B02"), tick)
            stroke.strokeWidth = s * 0.01f
            canvas.drawLine(cx - s * 0.02f, cy, cx - s * 0.005f, cy + s * 0.016f, stroke)
            canvas.drawLine(cx - s * 0.005f, cy + s * 0.016f, cx + s * 0.022f, cy - s * 0.016f, stroke)
        }
    }

    private fun stickIt(canvas: Canvas, s: Float) {
        val t = phase(3.2f, 0.6f)
        // A door frame: a tall panel the tag lands on.
        rect.set(s * 0.22f, s * 0.04f, s * 0.78f, s * 0.98f)
        fill.color = Color.argb(18, 255, 255, 255)
        canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, fill)
        stroke.strokeWidth = s * 0.006f
        stroke.color = Color.argb(50, 255, 255, 255)
        canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, stroke)
        fill.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(s * 0.68f, s * 0.58f, s * 0.025f, fill)
        val land = ramp(t, 0f, 0.28f)
        val squash = if (t in 0.28f..0.44f) 1f - 0.1f * sin(PI * (t - 0.28f) / 0.16f).toFloat() else 1f
        val fade = 1 - ramp(t, 0.86f, 1f)
        val cy = s * 0.4f - s * 0.45f * (1 - land)
        canvas.save()
        canvas.scale(squash, squash, s / 2, cy)
        val ring = if (fade < 1f) alpha(orange, fade) else orange
        if (land > 0f && fade > 0f) tag(canvas, s / 2, cy, s * 0.14f, ring, alpha(fg, fade))
        canvas.restore()
        // Press marks once it lands.
        val press = ramp(t, 0.36f, 0.44f) * fade
        if (press > 0f) {
            stroke.color = alpha(orange, press * 0.7f)
            stroke.strokeWidth = s * 0.008f
            for (k in 0..2) {
                val a = (-60 + k * 60) * PI / 180
                val x = s / 2 + (s * 0.19f) * sin(a).toFloat()
                val y = cy - (s * 0.19f) * kotlin.math.cos(a).toFloat()
                val x2 = s / 2 + (s * 0.23f) * sin(a).toFloat()
                val y2 = cy - (s * 0.23f) * kotlin.math.cos(a).toFloat()
                canvas.drawLine(x, y, x2, y2, stroke)
            }
        }
    }

    private fun tryIt(canvas: Canvas, s: Float) {
        val t = phase(3.4f, 0.65f)
        val ok = ramp(t, 0.42f, 0.55f) * (1 - ramp(t, 0.80f, 1f))
        val tx = s * 0.4f
        val ty = s * 0.6f
        if (ok > 0f) {
            fill.color = alpha(green, 0.18f * ok)
            canvas.drawCircle(tx, ty, s * 0.27f, fill)
        }
        tag(canvas, tx, ty, s * 0.17f, mix(orange, green, ok), mix(fg, green, ok), 1f + 0.8f * ok)
        // The phone comes in from the top right, touches the tag, and leaves.
        val near = ramp(t, 0.10f, 0.40f) * (1 - ramp(t, 0.70f, 1f))
        canvas.save()
        canvas.translate(s * (0.25f * (1 - near) + 0.03f * near), -s * (0.18f * (1 - near) + 0.01f * near))
        canvas.rotate(12f - 8f * near, s * 0.62f, s * 0.36f)
        phone(canvas, s * 0.62f, s * 0.36f, s * 0.3f, s * 0.56f)
        canvas.restore()
    }
}
