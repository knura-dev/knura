package io.knura.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small helpers that build the app's views in code, in the look of the brand guide: see-through
 * cards with hairline borders, pill buttons, stroke icons tinted to the text color.
 */
class Ui(val ctx: Context) {
    val density = ctx.resources.displayMetrics.density

    fun dp(value: Number): Int = (value.toFloat() * density + 0.5f).toInt()

    fun color(id: Int): Int = ctx.getColor(id)

    fun font(weight: Int, mono: Boolean = false): Typeface =
        Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, weight, false)

    fun text(value: CharSequence, sp: Float, color: Int, weight: Int = 400, mono: Boolean = false): TextView = TextView(ctx).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = font(weight, mono)
        includeFontPadding = true
    }

    /** Uppercase small label, like TAG UID or TODAY. */
    fun label(value: String): TextView = text(value.uppercase(), 12f, color(R.color.muted), 600).apply {
        letterSpacing = 0.1f
    }

    fun singleLine(view: TextView, middle: Boolean = false): TextView = view.apply {
        maxLines = 1
        ellipsize = if (middle) TextUtils.TruncateAt.MIDDLE else TextUtils.TruncateAt.END
    }

    fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
    }

    fun oval(fill: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    /** A see-through card with a hairline border. */
    fun glass(radiusDp: Float = 22f): GradientDrawable = rounded(color(R.color.glass), radiusDp, color(R.color.glass_line))

    /** [background] with a touch ripple, for anything people press. */
    fun pressable(view: View, background: Drawable, onClick: (() -> Unit)?): View {
        view.background = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), background, null)
        if (onClick != null) {
            view.isClickable = true
            view.isFocusable = true
            view.setOnClickListener { onClick() }
        }
        return view
    }

    fun icon(res: Int, sizeDp: Int, tint: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** An icon centered in a rounded or round box, like the tiles' icons. */
    fun iconBox(res: Int, boxDp: Int, iconDp: Int, tint: Int, fill: Int, radiusDp: Float? = null): FrameLayout = FrameLayout(ctx).apply {
        background = if (radiusDp == null) oval(fill) else rounded(fill, radiusDp)
        addView(icon(res, iconDp, tint), FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A round icon-only button with a spoken [label]. */
    fun circleButton(res: Int, label: String, sizeDp: Int = 44, fill: Int = color(R.color.glass), tint: Int = color(R.color.fg),
                     stroke: Int? = color(R.color.glass_line), onClick: () -> Unit): FrameLayout {
        val box = FrameLayout(ctx)
        box.addView(icon(res, 22, tint), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        box.contentDescription = label
        val bg = oval(fill, stroke)
        box.background = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), bg, null)
        box.isClickable = true
        box.isFocusable = true
        box.setOnClickListener { onClick() }
        box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return box
    }

    enum class Kind { PRIMARY, OUTLINE, DANGER, LIGHT, ORANGE, ON_DARK }

    fun button(label: String, kind: Kind = Kind.PRIMARY, heightDp: Int = 56, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        typeface = font(600)
        minHeight = dp(heightDp)
        minimumHeight = dp(heightDp)
        setPadding(dp(18), 0, dp(18), 0)
        val (fill, stroke, fg) = when (kind) {
            Kind.PRIMARY -> Triple(color(R.color.accent), null, color(R.color.on_accent))
            Kind.OUTLINE -> Triple(color(R.color.glass), color(R.color.line_strong), color(R.color.fg))
            Kind.DANGER -> Triple(Color.TRANSPARENT, color(R.color.bad) and 0x00FFFFFF or 0x59000000, color(R.color.bad_text))
            Kind.LIGHT -> Triple(color(R.color.fg), null, color(R.color.bg))
            // The welcome, pairing and scan screens are dark in both themes.
            Kind.ORANGE -> Triple(Color.parseColor("#F28C3A"), null, Color.parseColor("#160B02"))
            Kind.ON_DARK -> Triple(Color.argb(10, 255, 255, 255), Color.argb(41, 255, 255, 255), Color.parseColor("#E8EDF3"))
        }
        setTextColor(fg)
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), rounded(fill, 18f, stroke), null)
        setOnClickListener { onClick() }
    }

    /** A pill with a colored dot and a word, like "Online". */
    fun statusPill(word: String, dot: Int, fg: Int, fill: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(fill, 999f)
        addView(View(ctx).apply { background = oval(dot) }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) })
        addView(text(word, 13f, fg, 600))
    }

    fun vertical(gapDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, dp(gapDp)); setColor(Color.TRANSPARENT) }
        }
    }

    fun horizontal(gapDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dp(gapDp), 1); setColor(Color.TRANSPARENT) }
        }
    }

    /** Layout params for a child of a LinearLayout. */
    fun lp(width: Int = MATCH, height: Int = WRAP, weight: Float = 0f, top: Int = 0, bottom: Int = 0,
           start: Int = 0, end: Int = 0, gravity: Int = -1): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height, weight).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
            marginStart = dp(start)
            marginEnd = dp(end)
            if (gravity != -1) this.gravity = gravity
        }

    fun space(heightDp: Int = 0, weight: Float = 0f): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp), weight)
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
