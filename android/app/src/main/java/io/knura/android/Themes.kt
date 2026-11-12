package io.knura.android

import android.content.SharedPreferences
import android.graphics.Color
import java.time.LocalDate

/**
 * The app's themes, the same set as the self-hosted dashboard:
 *
 * - An everyday look: Default, Midnight (true black, always dark), Mono (no glow, solid cards) or
 *   Your color (one of six accents; no green or red, which mean genuine and rejected).
 * - Holiday themes, shown by date when "Holiday themes by date" is on, or kept all year when picked.
 *   A holiday wins over the everyday look. Orange stays the accent and status colors never change.
 *
 * [Ui.color] asks [color] first, so every screen built with [Ui] follows the theme.
 */
object Themes {
    val LOOKS = listOf("default" to "Default", "midnight" to "Midnight", "mono" to "Mono", "custom" to "Your color")
    /** id, name, dark-theme color, light-theme color (darker, for contrast on light cards). */
    val ACCENTS = listOf(
        Accent("orange", "Orange", 0xFFF28C3A.toInt(), 0xFFB45309.toInt()),
        Accent("gold", "Gold", 0xFFF2C14E.toInt(), 0xFF8A5E08.toInt()),
        Accent("pink", "Pink", 0xFFFF7EB6.toInt(), 0xFFB0306E.toInt()),
        Accent("violet", "Violet", 0xFFA98BFF.toInt(), 0xFF6A3FC8.toInt()),
        Accent("blue", "Blue", 0xFF5B9CFF.toInt(), 0xFF1F5FD6.toInt()),
        Accent("ice", "Ice", 0xFF6CCFF6.toInt(), 0xFF0B6E99.toInt()),
    )

    data class Accent(val id: String, val label: String, val dark: Int, val light: Int)

    /** What is showing: the everyday look (default while a holiday shows), its accent, and the holiday. */
    data class Active(val look: String, val accent: String, val holiday: Holidays.Holiday?, val auto: Boolean, val until: LocalDate?)

    var active = Active("default", "orange", null, false, null)
        private set

    /** Works out what to show from saved choices. [look] may also be a holiday id the person picked. */
    fun resolve(look: String?, accent: String?, byDate: Boolean, today: LocalDate): Active {
        val l = look?.takeIf { id -> LOOKS.any { it.first == id } || Holidays.Holiday.byId(id) != null } ?: "default"
        val a = accent?.takeIf { id -> ACCENTS.any { it.id == id } } ?: "orange"
        val now = if (byDate) Holidays.on(today) else null
        val holiday = now?.holiday ?: Holidays.Holiday.byId(l)
        return Active(if (holiday != null) "default" else l, a, holiday, now != null, now?.until)
    }

    fun load(prefs: SharedPreferences, today: LocalDate = LocalDate.now()): Active {
        active = resolve(prefs.getString("look", null), prefs.getString("accent", null), prefs.getBoolean("holidays", true), today)
        return active
    }

    /** The mark's orange, changed only by Midnight and Your color. */
    fun markAccent(): Int? = when {
        active.holiday != null -> null
        active.look == "midnight" -> c(0xFFE07F33)
        active.look == "custom" -> ACCENTS.firstOrNull { it.id == active.accent && it.id != "orange" }?.dark
        else -> null
    }

    /** Midnight is always dark, whatever the light or dark setting says. */
    val alwaysDark: Boolean get() = active.look == "midnight"

    // ---------------------------------------------------------------- colors

    /** The holiday extras, for the background, decorations and the mark. */
    data class Season(val glow2: Int, val a: Int, val b: Int, val c: Int, val particle: Int, val decor: Int, val core: Int?, val cap: Int?,
                      val moon: Int? = null, val dirt: Int = 0, val grave: Int = 0, val face: Int = 0)

    fun season(night: Boolean): Season? {
        val h = active.holiday ?: return null
        val n = night || alwaysDark
        return when (h) {
            Holidays.Holiday.CHRISTMAS -> if (n) Season(c(0x5C1F7A55), c(0xFFFFB86B), c(0xFFCFE6FF), c(0xFF4FB58A), c(0x99F4F8FF), c(0xFF4FB58A), null, c(0xFFF4F8FF))
                else Season(c(0x3D2E8C60), c(0xFFE06B12), c(0xFF6F9CC8), c(0xFF1F7A55), c(0x616E8CAA), c(0xFF1F7A55), null, c(0xFFFFFFFF))
            Holidays.Holiday.HALLOWEEN -> if (n) Season(c(0x66683AB8), c(0xFFFF9A3D), c(0xFFB39BFF), c(0xFFFFD27A), c(0xFFFF9A3D), c(0xFFB39BFF), c(0xFFFFB86B), null,
                    moon = c(0xEBFFECCD), dirt = c(0xFF24170F), grave = c(0x33B39BFF), face = c(0xFF2A1406))
                else Season(c(0x428C5AD2), c(0xFFE06B12), c(0xFF7A4FD0), c(0xFFC98A12), c(0xFFE06B12), c(0xFF6A3FC8), c(0xFFE06B12), null,
                    moon = c(0xF2FFD6A0), dirt = c(0xFF7A5A40), grave = c(0x336A3FC8), face = c(0xFF3A1A05))
            Holidays.Holiday.THANKSGIVING -> if (n) Season(c(0x66965F1E), c(0xFFE3B04B), c(0xFFC9682A), c(0xFF9DB062), c(0xFFE3B04B), c(0xFFE3B04B), c(0xFFE3B04B), null)
                else Season(c(0x4DCD913C), c(0xFFB07A12), c(0xFFB4501A), c(0xFF5E7A2E), c(0xFFB07A12), c(0xFF8A5E08), c(0xFFB07A12), null)
            Holidays.Holiday.NEW_YEAR -> if (n) Season(c(0x663C50AA), c(0xFFF2D27A), c(0xFFDCE3EE), c(0xFFFFB86B), c(0xFFF2D27A), c(0xFFF2D27A), c(0xFFF2D27A), null)
                else Season(c(0x3D6E82D2), c(0xFFB8860B), c(0xFF7A8699), c(0xFFE06B12), c(0xFFB8860B), c(0xFF8A6A10), c(0xFFB8860B), null)
            Holidays.Holiday.VALENTINE -> if (n) Season(c(0x57C83C82), c(0xFFFF8FC0), c(0xFFFFB86B), c(0xFFFFD1E3), c(0xFFFF8FC0), c(0xFFFF8FC0), c(0xFFFF8FC0), null)
                else Season(c(0x42EB78AA), c(0xFFC2307A), c(0xFFE06B12), c(0xFFE58AB0), c(0xFFC2307A), c(0xFFB0306E), c(0xFFC2307A), null)
            Holidays.Holiday.JULY4 -> if (n) Season(c(0x6B2846AA), c(0xFF5B85F0), c(0xFFF4F7FF), c(0xFFFF9A3D), c(0xFFF4F7FF), c(0xFF8FB0FF), c(0xFFF4F7FF), null)
                else Season(c(0x3D5A78DC), c(0xFF2347A8), c(0xFFC9D2E3), c(0xFFE06B12), c(0xFF2347A8), c(0xFF2347A8), c(0xFF2347A8), null)
        }
    }

    /** The theme's color for a color resource, or null to use the app's own. */
    fun color(id: Int, night: Boolean): Int? {
        if (alwaysDark) MIDNIGHT[id]?.let { return it }
        active.holiday?.let { h -> holidayColor(h, id, night)?.let { return it } }
        if (active.look == "mono") (if (night) MONO_DARK else MONO_LIGHT)[id]?.let { return it }
        if (active.look == "custom") customColor(id, night)?.let { return it }
        return null
    }

    private fun holidayColor(h: Holidays.Holiday, id: Int, night: Boolean): Int? {
        val n = night || alwaysDark
        val (bg, line, glow) = when (h) {
            Holidays.Holiday.CHRISTMAS -> if (n) Triple(0xFF080D0CL, 0x26CFE6FFL, 0x4DF28C3AL) else Triple(0xFFF7FAF6L, 0xFFE2E8E3L, 0x2EE06B12L)
            Holidays.Holiday.HALLOWEEN -> if (n) Triple(0xFF0B0912L, 0x26C8B4FFL, 0x5CF28C3AL) else Triple(0xFFFBF7FFL, 0xFFE8E2F2L, 0x33E06B12L)
            Holidays.Holiday.THANKSGIVING -> if (n) Triple(0xFF0F0B08L, 0x24FFDCAAL, 0x57F28C3AL) else Triple(0xFFFFF6EAL, 0xFFEFE4D4L, 0x33E06B12L)
            Holidays.Holiday.NEW_YEAR -> if (n) Triple(0xFF070A14L, 0x24FFE6B4L, 0x47F28C3AL) else Triple(0xFFF8F8FCL, 0xFFE3E5EEL, 0x2EE06B12L)
            Holidays.Holiday.VALENTINE -> if (n) Triple(0xFF0F0A0EL, 0x24FFBEDCL, 0x4DF28C3AL) else Triple(0xFFFFF6F9L, 0xFFF1E1E8L, 0x2EE06B12L)
            Holidays.Holiday.JULY4 -> if (n) Triple(0xFF070B16L, 0x24C8D7FFL, 0x4DF28C3AL) else Triple(0xFFF6F8FDL, 0xFFE0E5F1L, 0x2EE06B12L)
        }
        return when (id) {
            R.color.bg -> bg.toInt()
            R.color.glass_line -> line.toInt()
            R.color.glow -> glow.toInt()
            else -> null
        }
    }

    private fun customColor(id: Int, night: Boolean): Int? {
        val a = ACCENTS.firstOrNull { it.id == active.accent } ?: return null
        if (a.id == "orange") return null
        val main = if (night) a.dark else a.light
        return when (id) {
            R.color.accent, R.color.accent_stroke, R.color.accent_text, R.color.code_text -> main
            R.color.on_accent -> if (night) c(0xFF0A0D12) else Color.WHITE
            R.color.accent_tint -> withAlpha(main, if (night) 0x1F else 0x17)
            R.color.accent_ring_1 -> withAlpha(main, 0x2E)
            R.color.accent_ring_2 -> withAlpha(main, 0x66)
            R.color.glow -> withAlpha(a.dark, if (night) 0x47 else 0x2E)
            else -> null
        }
    }

    private val MONO_DARK = mapOf(R.color.bg to c(0xFF0C0C0D), R.color.glass to c(0xFF161618), R.color.glass_line to c(0x17FFFFFF),
        R.color.glow to Color.TRANSPARENT, R.color.icon_bg to c(0x12FFFFFF), R.color.nav_bg to c(0xF2161618))
    private val MONO_LIGHT = mapOf(R.color.bg to c(0xFFF4F4F5), R.color.glass to c(0xFFFFFFFF), R.color.glass_line to c(0xFFE4E6EA),
        R.color.glow to Color.TRANSPARENT, R.color.nav_bg to c(0xF7FFFFFF))
    private val MIDNIGHT = mapOf(
        R.color.bg to c(0xFF000000), R.color.card to c(0xFF0A0A0C), R.color.line to c(0xFF15181D), R.color.line_strong to c(0xFF20252C),
        R.color.fg to c(0xFFD5DCE4), R.color.muted to c(0xFF7E8996), R.color.soft to c(0xFFA8B1BC),
        R.color.accent to c(0xFFE07F33), R.color.accent_text to c(0xFFE8A666), R.color.accent_stroke to c(0xFFE07F33),
        R.color.accent_tint to c(0x1FE07F33), R.color.accent_ring_1 to c(0x2EE07F33), R.color.accent_ring_2 to c(0x66E07F33), R.color.on_accent to c(0xFF140A02),
        R.color.ok to c(0xFF3FC98B), R.color.ok_text to c(0xFF3FC98B), R.color.ok_tint to c(0x1A3FC98B),
        R.color.bad to c(0xFFF07070), R.color.bad_text to c(0xFFF07070), R.color.bad_tint to c(0x1FF07070),
        R.color.term to c(0xFF050608), R.color.code_text to c(0xFFE8A666), R.color.logo_core to c(0xFFD5DCE4), R.color.ripple to c(0x24FFFFFF),
        R.color.glass to c(0x09FFFFFF), R.color.glass_line to c(0x14FFFFFF), R.color.icon_bg to c(0x0FFFFFFF), R.color.nav_bg to c(0xE6080809),
        R.color.glow to c(0x1FF28C3A), R.color.glow_ok to c(0x2A3FC98B), R.color.glow_bad to c(0x26F07070),
    )

    private fun c(argb: Long): Int = argb.toInt()
    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
