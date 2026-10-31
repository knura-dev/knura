package io.knura.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import java.time.ZoneId
import org.json.JSONObject

/**
 * The home screen widgets and the Quick Settings tile. The widgets show what the app last read
 * from the server: reading it fresh needs the token, and the token needs your fingerprint, so a
 * widget can't do that by itself. Every look the app takes updates them.
 */
object Widgets {
    const val ACTION_CHECK_TAP = "io.knura.android.CHECK_TAP"

    /** Opens the app straight into Check a tap. */
    fun checkTapIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_CHECK_TAP)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun pending(context: Context, intent: Intent, code: Int): PendingIntent =
        PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openApp(context: Context) = pending(context,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), 1)

    /** Redraws every widget the person has placed. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        for (provider in listOf(LastTapWidget::class.java, CheckTapWidget::class.java, TodayWidget::class.java)) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isNotEmpty()) update(context, manager, ids, provider)
        }
    }

    fun update(context: Context, manager: AppWidgetManager, ids: IntArray, provider: Class<*>) {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val snapshot = Snapshot.fromJson(prefs.getString("snapshot", null))
        val names = try {
            JSONObject(prefs.getString("tagNames", "{}") ?: "{}")
        } catch (e: org.json.JSONException) {
            JSONObject()
        }
        val zone = ZoneId.systemDefault()
        val views = when (provider) {
            LastTapWidget::class.java -> RemoteViews(context.packageName, R.layout.widget_last_tap).apply {
                val last = WidgetText.lastTap(snapshot, { names.optString(it, "").ifEmpty { null } }, zone)
                setTextViewText(R.id.widget_name, last.title)
                setTextViewText(R.id.widget_detail, last.detail)
                val (icon, color) = when (last.ok) {
                    true -> R.drawable.ic_ui_check to context.getColor(R.color.ok_text)
                    false -> R.drawable.ic_ui_cross to context.getColor(R.color.bad_text)
                    null -> R.drawable.ic_ui_tag to context.getColor(R.color.muted)
                }
                setImageViewResource(R.id.widget_icon, icon)
                setInt(R.id.widget_icon, "setColorFilter", color)
                val online = prefs.getString("link", null)
                setTextViewText(R.id.widget_status, when (online) {
                    "ONLINE" -> "Server online"
                    "OFFLINE" -> "Server offline"
                    else -> ""
                })
                setTextColor(R.id.widget_status, context.getColor(if (online == "OFFLINE") R.color.bad_text else R.color.ok_text))
                setOnClickPendingIntent(R.id.widget_root, openApp(context))
            }
            TodayWidget::class.java -> RemoteViews(context.packageName, R.layout.widget_today).apply {
                val today = WidgetText.today(snapshot, zone)
                setTextViewText(R.id.widget_count, today.count)
                setTextViewText(R.id.widget_detail, today.detail)
                val days = snapshot?.lastSevenDays(zone) ?: IntArray(7)
                setImageViewBitmap(R.id.widget_bars, bars(context, days))
                setContentDescription(R.id.widget_bars, "Taps over the last 7 days: " + days.joinToString(", ") + ", today last")
                setOnClickPendingIntent(R.id.widget_root, openApp(context))
            }
            else -> RemoteViews(context.packageName, R.layout.widget_check).apply {
                setOnClickPendingIntent(R.id.widget_root, pending(context, checkTapIntent(context), 2))
            }
        }
        manager.updateAppWidget(ids, views)
    }

    /** The 7-day chart as a picture, since widgets can't hold custom views. */
    private fun bars(context: Context, days: IntArray): Bitmap {
        val density = context.resources.displayMetrics.density
        val w = (160 * density).toInt()
        val h = (36 * density).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val gap = w * 0.04f
        val barW = (w - gap * (days.size - 1)) / days.size
        val max = maxOf(1, days.maxOrNull() ?: 1)
        val fg = context.getColor(R.color.fg)
        for (i in days.indices) {
            val bh = maxOf(h * 0.12f, h * days[i] / max.toFloat())
            val x = i * (barW + gap)
            paint.color = if (i == days.size - 1) context.getColor(R.color.accent_stroke) else Color.argb(56, Color.red(fg), Color.green(fg), Color.blue(fg))
            canvas.drawRoundRect(x, h - bh, x + barW, h.toFloat(), barW * 0.25f, barW * 0.25f, paint)
        }
        return bitmap
    }
}

/** "Last tap": the newest tap's tag and result, and whether the server answered last time. */
class LastTapWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        Widgets.update(context, manager, ids, LastTapWidget::class.java)
}

/** One touch to Check a tap. */
class CheckTapWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        Widgets.update(context, manager, ids, CheckTapWidget::class.java)
}

/** Today's taps, rejected ones, and the 7-day chart. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        Widgets.update(context, manager, ids, TodayWidget::class.java)
}

/** The Quick Settings tile: Check a tap from the pull-down, without finding the app first. */
class CheckTapTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.check_a_tap)
            subtitle = getString(R.string.app_name)
            icon = Icon.createWithResource(this@CheckTapTile, R.drawable.ic_ui_tap)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Widgets.checkTapIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
