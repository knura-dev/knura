package io.knura.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.CancellationSignal
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.knura.android.Ui.Companion.MATCH
import io.knura.android.Ui.Companion.WRAP
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pair with knurd, pick what to do, unlock, hold a tag to the phone. The phone only relays: knurd
 * sends each command for the tag and checks every reply, and the tag's keys never reach the phone.
 *
 * The screens are built in code ([Ui]) and redrawn from the activity's state by [render]. Home,
 * Tags, Taps and Server share the bottom menu; welcome, pairing and the scan screen sit on the
 * moving orange background ([GlowView]) with the animated mark ([MarkView]).
 */
class MainActivity : Activity() {
    /** What the app can do, with knurd's name for each and the words the screens use for it. */
    private enum class Action(val api: String, val label: String, val doing: String, val short: String, val again: String) {
        PROVISION("provision", "Set up a tag", "knurd writes the keys", "Writing keys", "Set up another"),
        INFO("info", "Tag info", "knurd reads the tag", "Reading", "Read another"),
        TAP("tap", "Check a tap", "knurd checks the signature", "Checking", "Check another"),
        RESET("reset", "Reset tag", "knurd resets the keys", "Resetting", "Reset another"),
        /** A provision from the claim keys on a ready-to-use tag's claim card. */
        CLAIM("provision", "Claim a tag", "knurd swaps in your keys", "Claiming", "Claim another"),
    }

    private enum class Screen { WELCOME, PAIRING, HOME, TAGS, TAPS, SERVER, HOLD, RESULT, TAG, NOTES, WALK }

    private enum class Step { PENDING, ACTIVE, DONE }

    /** What the app knows about reaching the server. */
    private enum class Link { UNKNOWN, ONLINE, OFFLINE }

    private data class Shown(val label: String, val result: TagResults.Result, val log: String, val again: String?)

    private lateinit var ui: Ui
    private lateinit var vault: TokenVault
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout

    private val worker = Executors.newSingleThreadExecutor()
    private var nfc: NfcAdapter? = null
    private var screen = Screen.HOME
    private var topInset = 0
    private var bottomInset = 0
    private var notice: String? = null
    private var noticeBad = false

    /** What the server last told the app, kept on the phone between visits. */
    private var snapshot: Snapshot? = null
    private var link = Link.UNKNOWN
    private var linkError: String? = null
    @Volatile private var loading = false
    /**
     * The server, unlocked, for reading status, tags and taps without asking for a fingerprint each
     * time. Kept only while the app is on screen. Tag sessions still ask to unlock every time.
     */
    @Volatile private var session: KnurdClient? = null
    private var tapFilter = 0

    /** The tag the tag screen shows, and the tab it was opened from. */
    private var openTag: String? = null
    private var tagFrom = Screen.TAGS
    /** The app's own notifications, newest first, and the list as it was when the screen opened. */
    private var notes: List<Note> = emptyList()
    private var notesShown: List<Note> = emptyList()
    private var offlineSince: Instant? = null
    /** The first-tag walkthrough: its step (0 to 4), and whether a tag session belongs to it. */
    private var walkStep = 0
    private var walking = false
    /** Set by pairing, so the first load can offer the walkthrough when there are no tags. */
    private var offerWalk = false
    /** Opened from the Quick Settings tile or the Check a tap widget. */
    private var pendingTap = false

    // Pairing screen.
    private var pairStep = 1
    private var pairHosted = false
    private var pairUrl = ""

    // Tag sessions.
    private var action = Action.PROVISION
    /** Set while waiting for a tag or relaying to one. */
    @Volatile private var client: KnurdClient? = null
    @Volatile private var running: Action? = null
    /** The keys the tag has now, as knurd names them, or null when the action doesn't need it. */
    @Volatile private var from: String? = null
    /** The claim card's code while claiming a tag. */
    @Volatile private var claim: ClaimCodes.Claim? = null
    /**
     * A pack card (a claim card with no UID, for several tags) kept after a claim so "Claim next
     * tag" needs no new scan, and how many tags it has claimed. UI thread only; cleared on leaving
     * the result screen.
     */
    private var packCard: ClaimCodes.Claim? = null
    private var packClaimed = 0

    // The scan screen's changing parts, kept so a fold or unfold redraws it as it was.
    private var holdTitle = ""
    private var holdHint = ""
    private var holdHintBad = false
    private var holdFound = false
    private val holdSteps = arrayOf(Step.DONE, Step.ACTIVE, Step.PENDING)
    private var holdTitleView: TextView? = null
    private var holdHintView: TextView? = null
    private var holdStepsView: LinearLayout? = null

    private var shown: Shown? = null
    private var resultFelt = false
    private var logOpen = false
    /** The last result, as text for Copy details. */
    private var details = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        ui = Ui(this)
        vault = TokenVault(this)
        prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        nfc = NfcAdapter.getDefaultAdapter(this)
        snapshot = Snapshot.fromJson(prefs.getString("snapshot", null))
        notes = Note.listFromJson(prefs.getString("notes", null))
        pendingTap = intent?.action == Widgets.ACTION_CHECK_TAP && savedInstanceState == null

        // Draw behind the status and navigation bars, so the orange glow reaches the top of the screen.
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root = FrameLayout(this)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            if (bars.top != topInset || bars.bottom != bottomInset) {
                topInset = bars.top
                bottomInset = bars.bottom
                render()
            }
            insets
        }

        screen = if (vault.isPaired()) Screen.HOME else Screen.WELCOME
        render()
        CrashLog.takeLast(this)?.let {
            showResult("Knura", TagResults.Result(false, "The app crashed last time",
                "Please send this to the Knura developers. Copy details copies it.", emptyList()), it, again = null)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding or unfolding the phone, or a light/dark switch: the manifest keeps the activity, so redraw.
        ui = Ui(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        listenForTags()
        if (pendingTap) {
            // From the Quick Settings tile or the widget: go straight to Check a tap.
            pendingTap = false
            root.post { if (vault.isPaired() && running == null) start(Action.TAP) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Widgets.ACTION_CHECK_TAP) pendingTap = true
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
        if (running != null) {
            stop()
            showHome("Stopped: the app went to the background.")
        }
    }

    override fun onStop() {
        super.onStop()
        // Out of sight: the next look at the server asks for the fingerprint again.
        session = null
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    @Deprecated("Still the simplest way to handle Back on Android 11 and 12.")
    override fun onBackPressed() {
        when (screen) {
            Screen.HOLD -> cancelHold()
            Screen.RESULT -> closeResult()
            Screen.PAIRING -> showHome(null)
            Screen.TAGS, Screen.TAPS, Screen.SERVER, Screen.NOTES -> go(Screen.HOME)
            Screen.TAG -> go(tagFrom)
            Screen.WALK -> if (walkStep > 0) { walkStep--; render() } else endWalk()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- drawing

    private val wide: Boolean get() = resources.configuration.screenWidthDp >= 600
    private val night: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val hosted: Boolean get() = vault.url?.contains("/o/") == true

    private fun go(to: Screen) {
        if (to != screen) notice = null
        screen = to
        render()
    }

    /** Rebuilds the current screen from the state above. */
    private fun render() {
        if (!::root.isInitialized) return
        holdTitleView = null
        holdHintView = null
        holdStepsView = null
        root.removeAllViews()
        val page = when (screen) {
            Screen.WELCOME -> welcome()
            Screen.PAIRING -> pairing()
            Screen.HOME -> home()
            Screen.TAGS -> tags()
            Screen.TAPS -> taps()
            Screen.SERVER -> server()
            Screen.HOLD -> hold()
            Screen.RESULT -> result()
            Screen.TAG -> tagDetail()
            Screen.NOTES -> notifications()
            Screen.WALK -> walk()
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        if (screen in TABS) {
            root.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, ui.color(R.color.bg) and 0x00FFFFFF or 0xEB000000.toInt()))
            }, FrameLayout.LayoutParams(MATCH, ui.dp(110) + bottomInset, Gravity.BOTTOM))
            root.addView(navBar(), FrameLayout.LayoutParams(if (wide) ui.dp(420) else MATCH, WRAP,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = bottomInset + ui.dp(20)
                leftMargin = ui.dp(16)
                rightMargin = ui.dp(16)
            })
        }
        val darkPage = screen == Screen.WELCOME || screen == Screen.PAIRING || screen == Screen.HOLD || screen == Screen.WALK
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (darkPage || night) 0 else lightBars, lightBars)
    }

    /** A tab's page: the background with a faint orange glow at the top, and a scrolling column. */
    private fun tabPage(glow: Int = R.color.glow, fill: (LinearLayout) -> Unit): View {
        val page = FrameLayout(this)
        page.background = LayerDrawable(arrayOf(ColorDrawable(ui.color(R.color.bg)), GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(ui.color(glow), Color.TRANSPARENT)
            gradientRadius = resources.displayMetrics.widthPixels * if (wide) 0.45f else 0.85f
            setGradientCenter(if (wide) 0.25f else 0.5f, 0f)
        }))
        // No fillViewport here: stretching the column to the screen made the action tiles grow to fill it.
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val column = ui.vertical()
        val side = if (wide) ui.dp(32) else ui.dp(20)
        column.setPadding(side, topInset + ui.dp(12), side, bottomInset + ui.dp(if (screen in TABS) 120 else 28))
        fill(column)
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        page.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    /** The dark page with the moving orange glow, for welcome, pairing and the scan screen. */
    private fun glowPage(fill: (LinearLayout) -> Unit): View {
        val page = FrameLayout(this)
        page.addView(GlowView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = ui.vertical()
        column.setPadding(ui.dp(20), topInset + ui.dp(12), ui.dp(20), bottomInset + ui.dp(24))
        fill(column)
        val holder = FrameLayout(this)
        holder.addView(column, FrameLayout.LayoutParams(if (wide) ui.dp(560) else MATCH, MATCH, Gravity.CENTER_HORIZONTAL))
        scroll.addView(holder, FrameLayout.LayoutParams(MATCH, MATCH))
        page.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    /** Narrow content in the middle of a wide screen, for the tabs that are a single list. */
    private fun centered(column: LinearLayout): LinearLayout {
        if (!wide) return column
        val inner = ui.vertical()
        column.addView(inner, ui.lp(width = ui.dp(560), gravity = Gravity.CENTER_HORIZONTAL))
        return inner
    }

    private fun navBar(): View {
        val bar = ui.horizontal(10)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), 0, ui.dp(8), 0)
            background = ui.rounded(ui.color(R.color.nav_bg), 32f, ui.color(R.color.glass_line))
            elevation = ui.dp(6).toFloat()
        }
        val items = listOf(
            Triple(Screen.HOME, R.drawable.ic_ui_home, "Home"),
            Triple(Screen.TAGS, R.drawable.ic_ui_grid, "Tags"),
            Triple(Screen.TAPS, R.drawable.ic_ui_activity, "Taps"),
            Triple(Screen.SERVER, R.drawable.ic_ui_server, "Server"),
        )
        items.forEachIndexed { i, (target, icon, label) ->
            if (i > 0) pill.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
            val active = target == screen
            val button = ui.circleButton(icon, label, 48,
                fill = if (active) ui.color(R.color.fg) else Color.TRANSPARENT,
                tint = if (active) ui.color(R.color.bg) else ui.color(R.color.muted), stroke = null) { go(target) }
            button.isSelected = active
            if (active) button.stateDescription = "Selected"
            pill.addView(button)
        }
        bar.addView(pill, LinearLayout.LayoutParams(0, ui.dp(64), 1f))
        val fab = ui.circleButton(R.drawable.ic_ui_tap, "Check a tap", 64, fill = ui.color(R.color.accent),
            tint = ui.color(R.color.on_accent), stroke = null) { start(Action.TAP) }
        fab.elevation = ui.dp(8).toFloat()
        bar.addView(fab)
        return bar
    }

    // ---------------------------------------------------------------- welcome and pairing

    private fun welcome(): View = glowPage { col ->
        val top = ui.horizontal()
        top.addView(darkPill().apply {
            addView(MarkView(this@MainActivity, MarkView.Mode.STILL, showTile = false), LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
            addView(ui.text("Knura", 18f, ON_DARK, 700).apply { letterSpacing = -0.02f }, ui.lp(WRAP, WRAP, start = 8))
            setPadding(ui.dp(6), ui.dp(6), ui.dp(14), ui.dp(6))
        })
        top.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(darkPill().apply {
            addView(View(this@MainActivity).apply { background = ui.oval(ON_DARK_MUTED) }, LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)))
            addView(ui.text("Not paired", 13f, ON_DARK, 600), ui.lp(WRAP, WRAP, start = 6))
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        })
        col.addView(top, ui.lp(height = ui.dp(48)))
        col.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(1, 0, 1f).apply { height = 0 })
        col.addView(MarkView(this, MarkView.Mode.STILL), ui.lp(ui.dp(210), ui.dp(210), gravity = Gravity.CENTER_HORIZONTAL))
        col.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(kicker("Welcome"))
        col.addView(ui.text("Pair this phone with your server", 32f, ON_DARK, 800).apply { letterSpacing = -0.025f }, ui.lp(top = 10))
        col.addView(ui.text("Your keys stay on knurd. The phone only passes messages between it and the tag.", 15.5f, ON_DARK_SOFT)
            .apply { setLineSpacing(0f, 1.25f) }, ui.lp(top = 10))

        val card = ui.vertical(14).apply {
            background = darkGlass()
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        val first = ui.horizontal(12).apply { gravity = Gravity.TOP }
        first.addView(stepNumber(1))
        val firstText = ui.vertical(8)
        firstText.addView(ui.text("On the server's computer, run", 14f, ON_DARK))
        firstText.addView(ui.text("$ knurctl admin pair-phone <name>", 12.5f, Color.parseColor("#D5DDE6"), mono = true).apply {
            background = ui.rounded(Color.parseColor("#070A0E"), 12f)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            setTextIsSelectable(true)
        }, ui.lp())
        first.addView(firstText, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(first)
        val second = ui.horizontal(12)
        second.addView(stepNumber(2))
        second.addView(ui.text("Scan the QR code it shows", 14f, ON_DARK))
        card.addView(second)
        col.addView(card, ui.lp(top = 20))

        col.addView(ui.button("Scan pairing code", Ui.Kind.ORANGE) { scanPairingCode() }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_ui_qr, Color.parseColor("#160B02")), null, null, null)
            compoundDrawablePadding = ui.dp(8)
        }, ui.lp(top = 16))
        col.addView(ui.text("Using a hosted account? Pair a phone on its account page and scan that code instead.", 13f, ON_DARK_MUTED)
            .apply { gravity = Gravity.CENTER }, ui.lp(top = 12))
        noticeLine(col, onDark = true)
    }

    private fun pairing(): View = glowPage { col ->
        val top = ui.horizontal()
        top.addView(ui.circleButton(R.drawable.ic_ui_back, "Cancel pairing", 44, fill = DARK_PILL, tint = ON_DARK, stroke = null) {
            showHome(null)
        })
        top.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(darkPill().apply {
            addView(ui.text("Pairing", 14f, ON_DARK, 600))
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        })
        top.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ui.space(), LinearLayout.LayoutParams(ui.dp(44), 1))
        col.addView(top, ui.lp(height = ui.dp(48)))
        col.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(MarkView(this, MarkView.Mode.CONNECTING), ui.lp(ui.dp(260), ui.dp(260), gravity = Gravity.CENTER_HORIZONTAL))
        col.addView(ui.text(if (pairHosted) "Connecting to your account" else "Connecting to your server", 28f, ON_DARK, 800).apply {
            gravity = Gravity.CENTER
            letterSpacing = -0.025f
        }, ui.lp(top = 8))
        col.addView(ui.singleLine(ui.text(pairUrl.removePrefix("https://"), 13.5f, ON_DARK_ACCENT, mono = true), middle = true).apply {
            gravity = Gravity.CENTER
        }, ui.lp(top = 8))
        col.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        val steps = ui.vertical().apply {
            background = darkGlass()
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4))
        }
        val names = listOf("Code scanned", if (pairHosted) "Account answered" else "Server certificate pinned", "Lock the token to your fingerprint")
        names.forEachIndexed { i, name ->
            val state = when {
                i < pairStep -> Step.DONE
                i == pairStep -> Step.ACTIVE
                else -> Step.PENDING
            }
            val row = ui.horizontal(12)
            row.minimumHeight = ui.dp(52)
            row.addView(stepIcon(state, onDark = true))
            row.addView(ui.text(name, 14.5f, if (state == Step.PENDING) ON_DARK_MUTED else ON_DARK, if (state == Step.ACTIVE) 600 else 400),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(ui.text(when (state) { Step.DONE -> "Done"; Step.ACTIVE -> "Now"; Step.PENDING -> "" }, 13f,
                if (state == Step.ACTIVE) ON_DARK_ACCENT else ON_DARK_MUTED))
            steps.addView(row)
            if (i < names.size - 1) steps.addView(View(this).apply { setBackgroundColor(Color.argb(20, 255, 255, 255)) }, ui.lp(height = 1))
        }
        col.addView(steps)
        col.addView(ui.button(getString(R.string.cancel), Ui.Kind.ON_DARK) { showHome(null) }, ui.lp(top = 16))
        noticeLine(col, onDark = true)
    }

    // ---------------------------------------------------------------- home

    private fun home(): View = tabPage { col ->
        if (wide) {
            val row = ui.horizontal().apply { gravity = Gravity.TOP }
            val left = ui.vertical()
            homeHeader(left)
            homeMain(left)
            row.addView(left, LinearLayout.LayoutParams(ui.dp(380), WRAP))
            row.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) }, LinearLayout.LayoutParams(1, MATCH).apply {
                marginStart = ui.dp(28)
                marginEnd = ui.dp(28)
            })
            val right = ui.vertical()
            right.addView(sectionHead("Your tags", "See all") { go(Screen.TAGS) }, ui.lp(top = 14))
            tagGrid(right)
            if (snapshot?.taps?.isNotEmpty() == true) {
                right.addView(sectionHead("Recent taps", "See all") { go(Screen.TAPS) }, ui.lp(top = 24))
                right.addView(tapCard(snapshot!!.taps.take(5)), ui.lp(top = 8))
            }
            row.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f))
            col.addView(row)
        } else {
            homeHeader(col)
            homeMain(col)
            if (link != Link.OFFLINE) {
                col.addView(sectionHead("Your tags", "See all") { go(Screen.TAGS) }, ui.lp(top = 22))
                tagStrip(col)
            }
        }
    }

    private fun homeHeader(col: LinearLayout) {
        val row = ui.horizontal(12)
        val tile = FrameLayout(this).apply { background = ui.glass(16f) }
        tile.addView(MarkView(this, MarkView.Mode.STILL, showTile = false), FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
        row.addView(tile, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        val words = ui.vertical(2)
        words.addView(ui.text(Words.greeting(LocalTime.now(zone).hour), 13.5f, ui.color(R.color.soft)))
        val name = prefs.getString("name", null)
        words.addView(ui.text(name ?: "Welcome back", 22f, ui.color(R.color.fg), 700).apply {
            letterSpacing = -0.02f
            contentDescription = (name ?: "Welcome back") + ". Double tap to change your name."
            setOnClickListener { editName() }
        })
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        val unread = notes.count { it.unread }
        val bell = ui.circleButton(R.drawable.ic_ui_bell, if (unread > 0) "Notifications, $unread new" else "Notifications", 44) { openNotes() }
        if (unread > 0) {
            bell.addView(View(this).apply { background = ui.oval(ui.color(R.color.accent), ui.color(R.color.bg)) },
                FrameLayout.LayoutParams(ui.dp(10), ui.dp(10), Gravity.TOP or Gravity.END).apply { topMargin = ui.dp(9); rightMargin = ui.dp(10) })
        }
        row.addView(bell)
        col.addView(row, ui.lp(height = ui.dp(52)).apply { height = WRAP })
        noticeLine(col, onDark = false)
    }

    /** The server card, then the actions, or the offline message when the server didn't answer. */
    private fun homeMain(col: LinearLayout) {
        col.addView(serverCard(), ui.lp(top = 18))
        if (link == Link.OFFLINE) {
            offline(col)
            return
        }
        if (snapshot?.tags?.isEmpty() == true) col.addView(firstTagCard(), ui.lp(top = 14))
        col.addView(sectionTitle("What do you want to do?"), ui.lp(top = 24))
        val grid = ui.vertical(10)
        val first = ui.horizontal(10)
        first.addView(tile(R.drawable.ic_ui_key, "Set up a tag", "Give it Knura keys") { start(Action.PROVISION) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        first.addView(tile(R.drawable.ic_ui_shield, "Check a tap", "Is this tag genuine?") { start(Action.TAP) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val second = ui.horizontal(10)
        second.addView(tile(R.drawable.ic_ui_tag, "Tag info", "UID, keys, link") { start(Action.INFO) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        second.addView(tile(R.drawable.ic_ui_card, "Claim a tag", "It came with a claim card") { start(Action.CLAIM) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        grid.addView(first)
        grid.addView(second)
        col.addView(grid, ui.lp(top = 12))
        col.addView(listRow(R.drawable.ic_ui_reset, "Reset a tag", "Back to factory keys") { start(Action.RESET) }, ui.lp(top = 10))
    }

    private fun serverCard(): View {
        val card = ui.vertical(10).apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)) }
        ui.pressable(card, ui.glass(22f)) { refresh() }
        card.contentDescription = null
        val row = ui.horizontal(12)
        row.addView(ui.iconBox(R.drawable.ic_ui_server, 44, 22, ui.color(R.color.accent_stroke), ui.color(R.color.accent_tint), 14f),
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val words = ui.vertical(3)
        // The name on its own line and the status beside the address, so neither has to wrap next to the chart.
        words.addView(ui.singleLine(ui.text(if (hosted) "Hosted account" else "Your server", 14.5f, ui.color(R.color.fg), 600)))
        val second = ui.horizontal(8)
        if (link != Link.UNKNOWN) second.addView(linkWord())
        second.addView(ui.singleLine(ui.text(vault.url?.removePrefix("https://") ?: "", 12.5f, ui.color(R.color.muted), mono = true), middle = true),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        words.addView(second)
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        val s = snapshot
        if (s != null && link != Link.OFFLINE) {
            row.addView(BarsView(this).apply { setValues(s.lastSevenDays(zone)) }, LinearLayout.LayoutParams(ui.dp(53), ui.dp(30)))
            val count = ui.vertical(1).apply { gravity = Gravity.END }
            count.addView(ui.text(s.tapsToday(zone).toString(), 20f, ui.color(R.color.fg), 700).apply { gravity = Gravity.END })
            count.addView(ui.text("today", 11.5f, ui.color(R.color.muted)).apply { gravity = Gravity.END })
            row.addView(count, ui.lp(WRAP, WRAP, start = 10))
        }
        card.addView(row)
        val updated = when {
            loading -> "Checking..."
            s != null -> "Updated ${Words.ago(s.at, zone).replaceFirstChar { it.lowercase() }} · tap to refresh"
            else -> "Tap to unlock and load your taps"
        }
        card.addView(ui.text(updated, 12f, ui.color(R.color.muted)))
        return card
    }

    /** "Online", "Offline" or nothing yet, next to the server's name. */
    private fun linkWord(): View = when (link) {
        Link.ONLINE -> dotWord("Online", ui.color(R.color.ok), ui.color(R.color.ok_text))
        Link.OFFLINE -> dotWord("Offline", ui.color(R.color.bad), ui.color(R.color.bad_text))
        Link.UNKNOWN -> View(this)
    }

    private fun dotWord(word: String, dot: Int, fg: Int): View = ui.horizontal(5).apply {
        addView(View(this@MainActivity).apply { background = ui.oval(dot) }, LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)))
        addView(ui.singleLine(ui.text(word, 12f, fg, 600)))
    }

    private fun offline(col: LinearLayout) {
        val box = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val art = FrameLayout(this)
        art.addView(MarkView(this, MarkView.Mode.DIM), FrameLayout.LayoutParams(ui.dp(180), ui.dp(180), Gravity.CENTER))
        art.addView(ui.iconBox(R.drawable.ic_ui_link_off, 40, 20, ui.color(R.color.bad_text), ui.color(R.color.bad_tint)).apply {
            background = ui.oval(ui.color(R.color.card), ui.color(R.color.bad))
        }, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER).apply {
            leftMargin = ui.dp(52)
            topMargin = ui.dp(52)
        })
        box.addView(art, ui.lp(ui.dp(180), ui.dp(180), top = 12))
        box.addView(ui.text("Can't reach your server", 22f, ui.color(R.color.fg), 700).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        box.addView(ui.text(linkError ?: "It didn't answer. Check that knurd is running and this phone is on the same network.",
            14.5f, ui.color(R.color.muted)).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, ui.lp(top = 8))
        snapshot?.let { s ->
            val seen = ui.horizontal().apply {
                background = ui.glass(18f)
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            }
            seen.addView(ui.label("Last seen"))
            seen.addView(ui.text(Words.ago(s.at, zone), 13.5f, ui.color(R.color.fg)).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(seen, ui.lp(top = 16))
        }
        val buttons = ui.horizontal(10)
        buttons.addView(ui.button("How to fix", Ui.Kind.OUTLINE, 52) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.knura.io")))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        buttons.addView(ui.button("Try again", Ui.Kind.LIGHT, 52) { refresh() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(buttons, ui.lp(top = 16))
        col.addView(box, ui.lp(top = 8))
    }

    private fun tile(icon: Int, title: String, hint: String, onClick: () -> Unit): View {
        val tile = ui.vertical().apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            minimumHeight = ui.dp(128)
        }
        ui.pressable(tile, ui.glass(22f), onClick)
        tile.addView(ui.iconBox(icon, 40, 20, ui.color(R.color.accent_stroke), ui.color(R.color.icon_bg)), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        tile.addView(ui.text(title, 15.5f, ui.color(R.color.fg), 600), ui.lp(top = 24))
        tile.addView(ui.text(hint, 12.5f, ui.color(R.color.muted)), ui.lp(top = 3))
        return tile
    }

    private fun listRow(icon: Int, title: String, hint: String, onClick: () -> Unit): View {
        val row = ui.horizontal(12).apply {
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            minimumHeight = ui.dp(60)
        }
        ui.pressable(row, ui.glass(18f), onClick)
        row.addView(ui.iconBox(icon, 36, 18, ui.color(R.color.accent_stroke), ui.color(R.color.icon_bg), 12f), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
        val words = ui.vertical(2)
        words.addView(ui.text(title, 14.5f, ui.color(R.color.fg), 600))
        words.addView(ui.text(hint, 12.5f, ui.color(R.color.muted)))
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(ui.icon(R.drawable.ic_ui_chevron, 16, ui.color(R.color.line_strong)))
        return row
    }

    /** Home's row of tag cards, newest tap first. */
    private fun tagStrip(col: LinearLayout) {
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = ui.horizontal(10)
        val s = snapshot
        when {
            s == null -> row.addView(tagCard(R.drawable.ic_ui_lock, "Unlock", "to see your tags", null) { refresh() })
            s.tags.isEmpty() -> row.addView(tagCard(R.drawable.ic_ui_plus, "No tags yet", "Set one up", null) { start(Action.PROVISION) })
            else -> for (t in sortedTags(s)) {
                val last = s.lastTap(t.uid)
                row.addView(tagCard(R.drawable.ic_ui_tag, tagTitle(t.uid), last?.let { Words.ago(it.time, zone) } ?: "No recent taps",
                    last?.ok) { showTag(t.uid) })
            }
        }
        scroller.addView(row)
        col.addView(scroller, ui.lp(top = 8))
    }

    private fun tagCard(icon: Int, title: String, line: String, ok: Boolean?, onClick: () -> Unit): View {
        val card = ui.vertical(8).apply { setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)) }
        ui.pressable(card, ui.glass(20f), onClick)
        card.addView(ui.iconBox(icon, 36, 19, ui.color(R.color.fg), ui.color(R.color.icon_bg), 12f), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
        card.addView(ui.singleLine(ui.text(title, 14f, ui.color(R.color.fg), 600)))
        val status = ui.horizontal(5)
        if (ok != null) {
            status.addView(ui.icon(if (ok) R.drawable.ic_ui_check else R.drawable.ic_ui_cross, 12,
                ui.color(if (ok) R.color.ok else R.color.bad)).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = if (ok) "Verified" else "Rejected"
            })
        }
        status.addView(ui.singleLine(ui.text(line, 12f, ui.color(R.color.muted))))
        card.addView(status)
        return card.also { it.layoutParams = LinearLayout.LayoutParams(ui.dp(128), WRAP) }
    }

    /** The wide home screen's tag cards, three to a row, with a slot for setting up another. */
    private fun tagGrid(col: LinearLayout) {
        val s = snapshot
        val cards = mutableListOf<View>()
        if (s == null) {
            cards += tagCard(R.drawable.ic_ui_lock, "Unlock", "to see your tags", null) { refresh() }
        } else {
            for (t in sortedTags(s).take(8)) {
                val last = s.lastTap(t.uid)
                cards += tagCard(R.drawable.ic_ui_tag, tagTitle(t.uid), last?.let { Words.ago(it.time, zone) } ?: "No recent taps", last?.ok) {
                    showTag(t.uid)
                }
            }
            cards += tagCard(R.drawable.ic_ui_plus, "Set up a tag", "Give it keys", null) { start(Action.PROVISION) }
        }
        val grid = ui.vertical(10)
        for (chunk in cards.chunked(3)) {
            val row = ui.horizontal(10)
            for (c in chunk) row.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
            repeat(3 - chunk.size) { row.addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row)
        }
        col.addView(grid, ui.lp(top = 12))
    }

    // ---------------------------------------------------------------- tabs

    private fun tags(): View = tabPage { outer ->
        val col = centered(outer)
        val s = snapshot
        val head = ui.horizontal(10)
        head.addView(pageTitle("Tags"))
        if (s != null) head.addView(ui.text(if (s.tags.size == 1) "1 tag" else "${s.tags.size} tags", 14f, ui.color(R.color.muted)))
        head.addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(ui.circleButton(R.drawable.ic_ui_refresh, "Refresh", 44) { refresh() })
        head.addView(ui.circleButton(R.drawable.ic_ui_plus, "Set up a new tag", 44) { start(Action.PROVISION) })
        col.addView(head, ui.lp(height = ui.dp(52)))
        noticeLine(col, onDark = false)
        when {
            s == null -> locked(col, "Unlock to see your tags")
            s.tags.isEmpty() -> emptyTags(col)
            else -> {
                val list = ui.vertical(10)
                for (t in sortedTags(s)) {
                    val last = s.lastTap(t.uid)
                    val row = ui.horizontal(12).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)) }
                    ui.pressable(row, ui.glass(20f)) { showTag(t.uid) }
                    row.addView(ui.iconBox(R.drawable.ic_ui_tag, 44, 22, ui.color(R.color.fg), ui.color(R.color.icon_bg), 14f),
                        LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
                    val words = ui.vertical(3)
                    val named = tagName(t.uid)
                    val top = ui.horizontal(8)
                    top.addView(ui.singleLine(ui.text(named ?: t.uid, if (named == null) 13.5f else 14.5f, ui.color(R.color.fg), 600, mono = named == null)))
                    if (named != null) top.addView(ui.text(Words.shortUid(t.uid), 11.5f, ui.color(R.color.muted), mono = true))
                    words.addView(top)
                    words.addView(ui.text("Tap counter ${t.lastCounter}", 12.5f, ui.color(R.color.muted)))
                    row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
                    val right = ui.vertical(4).apply { gravity = Gravity.END }
                    right.addView(ui.text(last?.let { Words.ago(it.time, zone) } ?: "No recent taps", 12f, ui.color(R.color.muted)))
                    right.addView(ui.icon(R.drawable.ic_ui_chevron, 16, ui.color(R.color.line_strong)), ui.lp(ui.dp(16), ui.dp(16), gravity = Gravity.END))
                    row.addView(right)
                    list.addView(row)
                }
                col.addView(list, ui.lp(top = 16))
                col.addView(ui.text("Tap a tag to see its taps or give it a name. Names stay on this phone.", 12.5f, ui.color(R.color.muted)), ui.lp(top = 14))
                updatedLine(col, s)
            }
        }
    }

    private fun taps(): View = tabPage { outer ->
        val col = centered(outer)
        val s = snapshot
        val head = ui.horizontal(10)
        head.addView(pageTitle("Taps"), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(ui.circleButton(R.drawable.ic_ui_refresh, "Refresh", 44) { refresh() })
        col.addView(head, ui.lp(height = ui.dp(52)))
        noticeLine(col, onDark = false)
        if (s == null) {
            locked(col, "Unlock to see your taps")
            return@tabPage
        }
        val chips = ui.horizontal(8)
        listOf("All", "Verified", "Rejected").forEachIndexed { i, word ->
            val on = i == tapFilter
            val chip = ui.text(word, 13f, if (on) ui.color(R.color.bg) else ui.color(R.color.soft), if (on) 600 else 500).apply {
                setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9))
                if (on) stateDescription = "Selected"
            }
            ui.pressable(chip, if (on) ui.rounded(ui.color(R.color.fg), 999f) else ui.glass(999f)) { tapFilter = i; render() }
            chips.addView(chip)
        }
        col.addView(chips, ui.lp(top = 12))
        val shownTaps = s.taps.filter { tapFilter == 0 || (tapFilter == 1) == it.ok }
        if (shownTaps.isEmpty()) {
            col.addView(ui.text(if (s.taps.isEmpty()) "No taps since your server started." else "No taps like that.", 14.5f,
                ui.color(R.color.muted)).apply { gravity = Gravity.CENTER }, ui.lp(top = 40))
        } else {
            val now = Instant.now()
            val groups = shownTaps.groupBy {
                when (Words.clock(it.time, zone, now)) {
                    "Yesterday" -> "Yesterday"
                    else -> if (it.time.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) "Today" else "Earlier"
                }
            }
            for (name in listOf("Today", "Yesterday", "Earlier")) {
                val list = groups[name] ?: continue
                col.addView(ui.label(name), ui.lp(top = 20))
                col.addView(tapCard(list), ui.lp(top = 10))
            }
        }
        updatedLine(col, s)
    }

    private fun tapCard(list: List<Snapshot.Tap>): View {
        val card = ui.vertical().apply {
            background = ui.glass(22f)
            setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(2))
        }
        list.forEachIndexed { i, tap ->
            val row = ui.horizontal(12).apply { minimumHeight = ui.dp(62) }
            val good = tap.ok
            row.addView(ui.iconBox(if (good) R.drawable.ic_ui_check else R.drawable.ic_ui_cross, 38, 18,
                ui.color(if (good) R.color.ok_text else R.color.bad_text), ui.color(if (good) R.color.ok_tint else R.color.bad_tint), 12f),
                LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
            val words = ui.vertical(3)
            val uid = tap.uid
            words.addView(ui.singleLine(ui.text(if (uid == null) "Unknown tag" else tagTitle(uid), 14.5f, ui.color(R.color.fg), 600,
                mono = uid != null && tagName(uid) == null)))
            words.addView(ui.singleLine(ui.text(Words.tapDetail(tap), 12.5f, ui.color(R.color.muted))))
            row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
            val right = ui.vertical(3).apply { gravity = Gravity.END }
            right.addView(ui.text(statusWord(tap.status), 12.5f, ui.color(if (good) R.color.ok_text else R.color.bad_text), 600))
            right.addView(ui.text(Words.clock(tap.time, zone), 12f, ui.color(R.color.muted)))
            row.addView(right)
            card.addView(row)
            if (i < list.size - 1) card.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) }, ui.lp(height = 1))
        }
        return card
    }

    private fun server(): View = tabPage { outer ->
        val col = centered(outer)
        val s = snapshot
        val head = ui.horizontal(10)
        head.addView(pageTitle("Server"), LinearLayout.LayoutParams(0, WRAP, 1f))
        when (link) {
            Link.ONLINE -> head.addView(ui.statusPill("Online", ui.color(R.color.ok), ui.color(R.color.ok_text), ui.color(R.color.ok_tint)))
            Link.OFFLINE -> head.addView(ui.statusPill("Offline", ui.color(R.color.bad), ui.color(R.color.bad_text), ui.color(R.color.bad_tint)))
            Link.UNKNOWN -> head.addView(ui.circleButton(R.drawable.ic_ui_refresh, "Refresh", 44) { refresh() })
        }
        col.addView(head, ui.lp(height = ui.dp(52)))
        noticeLine(col, onDark = false)

        val card = ui.vertical(14).apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        ui.pressable(card, ui.glass(22f)) { refresh() }
        val top = ui.horizontal(12)
        val tile = FrameLayout(this).apply { background = ui.rounded(Color.argb(178, 10, 13, 18), 15f, Color.argb(26, 255, 255, 255)) }
        tile.addView(MarkView(this, MarkView.Mode.STILL, showTile = false), FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER))
        top.addView(tile, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        val words = ui.vertical(3)
        words.addView(ui.text(if (hosted) "Hosted account" else "knurd", 14.5f, ui.color(R.color.fg), 500, mono = !hosted))
        words.addView(ui.singleLine(ui.text(vault.url?.removePrefix("https://") ?: "", 12.5f, ui.color(R.color.muted), mono = true), middle = true))
        top.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(top)
        if (s != null) {
            val stats = ui.horizontal(8)
            stats.addView(stat(s.tapsToday(zone).toString(), "taps today"), LinearLayout.LayoutParams(0, WRAP, 1f))
            stats.addView(stat(s.tags.size.toString(), "tags"), LinearLayout.LayoutParams(0, WRAP, 1f))
            stats.addView(stat(s.status?.let { Words.uptime(it.uptimeSeconds) } ?: "–", "uptime"), LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(stats)
        } else {
            card.addView(ui.text(if (loading) "Checking..." else "Tap to unlock and load the server's status.", 13f, ui.color(R.color.muted)))
        }
        col.addView(card, ui.lp(top = 14))

        col.addView(ui.label("Connection"), ui.lp(top = 20))
        val rows = mutableListOf<Triple<Int, String, String>>()
        val st = s?.status
        if (st != null) {
            rows += Triple(R.drawable.ic_ui_link, "Tap link", st.publicUrl?.removePrefix("https://") ?: "Not set")
            rows += Triple(R.drawable.ic_ui_key, "Keys", if (st.keys == "master") "Per tag, from one master key" else "Fixed keys")
        }
        rows += Triple(R.drawable.ic_ui_lock, "Certificate", if (hosted) "Public certificate" else "Pinned")
        rows += Triple(R.drawable.ic_ui_fingerprint, "Unlock", "Fingerprint or screen lock")
        if (st != null && !hosted) rows += Triple(R.drawable.ic_ui_link, "Resolver", st.resolverOwner?.let { "Owner $it" } ?: "Not registered")
        col.addView(infoCard(rows), ui.lp(top = 10))

        val tokens = s?.tokens
        if (tokens != null && tokens.isNotEmpty()) {
            col.addView(ui.label("Phones and tokens"), ui.lp(top = 20))
            val list = ui.vertical().apply {
                background = ui.glass(22f)
                setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(2))
            }
            tokens.forEachIndexed { i, name ->
                val you = name == s?.you
                val row = ui.horizontal(12).apply { minimumHeight = ui.dp(58) }
                val w = ui.vertical(2)
                w.addView(ui.text(name, 13.5f, ui.color(R.color.fg), mono = true))
                if (you) w.addView(ui.text("This phone", 12f, ui.color(R.color.muted)))
                row.addView(w, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(ui.button("Remove", Ui.Kind.DANGER, 38) { confirmRemove(name, you) }.apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                list.addView(row)
                if (i < tokens.size - 1) list.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) }, ui.lp(height = 1))
            }
            col.addView(list, ui.lp(top = 10))
        }
        col.addView(ui.text(if (hosted) "Forget this account on this phone" else "Forget this server on this phone", 13.5f,
            ui.color(R.color.muted), 500).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(48)
            setOnClickListener { confirmForget() }
        }, ui.lp(top = 14))
        if (s != null) updatedLine(col, s)
    }

    private fun stat(value: String, label: String): View = ui.vertical(2).apply {
        background = ui.rounded(ui.color(R.color.icon_bg), 14f)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        addView(ui.text(value, 19f, ui.color(R.color.fg), 700))
        addView(ui.text(label, 11.5f, ui.color(R.color.muted)))
    }

    private fun infoCard(rows: List<Triple<Int, String, String>>): View {
        val card = ui.vertical().apply {
            background = ui.glass(22f)
            setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(2))
        }
        rows.forEachIndexed { i, (icon, name, value) ->
            val row = ui.horizontal(12).apply { minimumHeight = ui.dp(54) }
            row.addView(ui.icon(icon, 18, ui.color(R.color.muted)))
            row.addView(ui.text(name, 14f, ui.color(R.color.fg)))
            row.addView(ui.singleLine(ui.text(value, 13f, ui.color(R.color.muted), mono = value.contains('.') && !value.contains(' ')), middle = true)
                .apply { gravity = Gravity.END; setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(row)
            if (i < rows.size - 1) card.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) }, ui.lp(height = 1))
        }
        return card
    }

    private fun emptyTags(col: LinearLayout) {
        val box = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(MarkView(this, MarkView.Mode.DASHED), ui.lp(ui.dp(180), ui.dp(180), top = 40))
        box.addView(ui.text("No tags yet", 22f, ui.color(R.color.fg), 700), ui.lp(WRAP, WRAP, top = 8))
        box.addView(ui.text("Set up a blank NTAG 424 DNA tag, or claim one that came with a claim card.", 14.5f, ui.color(R.color.muted))
            .apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, ui.lp(top = 8))
        box.addView(ui.button("Set up a tag") { start(Action.PROVISION) }, ui.lp(top = 20))
        box.addView(ui.button("Claim a tag", Ui.Kind.OUTLINE) { start(Action.CLAIM) }, ui.lp(top = 10))
        box.addView(ui.text("Walk me through it", 14f, ui.color(R.color.accent_text), 500).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(48)
            setOnClickListener { startWalk() }
        }, ui.lp(top = 6))
        col.addView(box)
    }

    private fun locked(col: LinearLayout, why: String) {
        val box = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ui.iconBox(R.drawable.ic_ui_lock, 72, 30, ui.color(R.color.accent_stroke), ui.color(R.color.accent_tint), 24f),
            ui.lp(ui.dp(72), ui.dp(72), top = 60))
        box.addView(ui.text(if (loading) "Loading..." else why, 18f, ui.color(R.color.fg), 600).apply { gravity = Gravity.CENTER }, ui.lp(top = 16))
        box.addView(ui.text("Your server sends this to the phone after you unlock with your fingerprint or screen lock.", 14f,
            ui.color(R.color.muted)).apply { gravity = Gravity.CENTER }, ui.lp(top = 6))
        if (!loading) box.addView(ui.button("Unlock") { refresh() }, ui.lp(top = 20))
        col.addView(box)
    }

    private fun updatedLine(col: LinearLayout, s: Snapshot) {
        col.addView(ui.text("Updated ${Words.ago(s.at, zone).replaceFirstChar { it.lowercase() }}", 12f, ui.color(R.color.muted))
            .apply { gravity = Gravity.CENTER }, ui.lp(top = 18))
    }

    // ---------------------------------------------------------------- scan screen and result

    private fun hold(): View = glowPage { col ->
        val a = running ?: action
        val top = ui.horizontal()
        top.addView(ui.circleButton(R.drawable.ic_ui_close, "Cancel", 44, fill = DARK_PILL, tint = ON_DARK, stroke = null) { cancelHold() })
        top.addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(darkPill().apply {
            addView(ui.text(a.label, 14f, ON_DARK, 600))
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        })
        top.addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ui.space(), LinearLayout.LayoutParams(ui.dp(44), 1))
        col.addView(top, ui.lp(height = ui.dp(48)))
        col.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(MarkView(this, MarkView.Mode.SCANNING), ui.lp(ui.dp(276), ui.dp(276), gravity = Gravity.CENTER_HORIZONTAL))
        col.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        val titleView = ui.text(holdTitle, 30f, ON_DARK, 800).apply { gravity = Gravity.CENTER; letterSpacing = -0.025f }
        holdTitleView = titleView
        col.addView(titleView, ui.lp())
        val hintView = ui.text(holdHint, 15f, if (holdHintBad) ON_DARK_BAD else ON_DARK_SOFT).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        holdHintView = hintView
        col.addView(hintView, ui.lp(top = 8))
        val steps = ui.horizontal(8).apply {
            background = darkGlass()
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        }
        holdStepsView = steps
        fillHoldSteps(steps, a)
        col.addView(steps, ui.lp(top = 22))
        if (a == Action.PROVISION || a == Action.RESET) {
            val row = ui.horizontal(12).apply {
                background = darkGlass()
                setPadding(ui.dp(16), ui.dp(6), ui.dp(12), ui.dp(6))
                minimumHeight = ui.dp(56)
            }
            val label = ui.text(getString(R.string.knura_keys), 14.5f, ON_DARK)
            row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            val switch = Switch(this).apply {
                isChecked = from == "knura"
                contentDescription = getString(R.string.knura_keys)
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#B7C0CB")))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.parseColor("#F28C3A"), Color.parseColor("#2C3846")))
                setOnCheckedChangeListener { _, on -> from = if (on) "knura" else "factory" }
            }
            row.addView(switch)
            col.addView(row, ui.lp(top = 10))
        }
        col.addView(ui.button(getString(R.string.cancel), Ui.Kind.ON_DARK) { cancelHold() }, ui.lp(top = 14))
    }

    private fun fillHoldSteps(row: LinearLayout, a: Action) {
        row.removeAllViews()
        val names = listOf(getString(R.string.step_unlocked), if (holdFound) "Tag found" else "Waiting", a.short)
        names.forEachIndexed { i, name ->
            if (i > 0) row.addView(View(this).apply { setBackgroundColor(Color.argb(36, 255, 255, 255)) },
                LinearLayout.LayoutParams(0, 1, 1f))
            val item = ui.horizontal(7)
            item.addView(stepIcon(holdSteps[i], onDark = true, small = true))
            item.addView(ui.text(name, 13f, if (holdSteps[i] == Step.PENDING) ON_DARK_MUTED else ON_DARK,
                if (holdSteps[i] == Step.ACTIVE) 600 else 400))
            row.addView(item)
        }
    }

    private fun result(): View {
        val r = shown ?: return home()
        val good = r.result.good
        return tabPage(if (good) R.color.glow_ok else R.color.glow_bad) { outer ->
            val col = centered(outer)
            val top = ui.horizontal()
            top.addView(ui.circleButton(R.drawable.ic_ui_close, getString(R.string.close), 44) { closeResult() })
            top.addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(ui.text(r.label, 15f, ui.color(R.color.fg), 600))
            top.addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(ui.space(), LinearLayout.LayoutParams(ui.dp(44), 1))
            col.addView(top, ui.lp(height = ui.dp(48)))
            col.addView(MarkView(this, if (good) MarkView.Mode.VERIFIED else MarkView.Mode.REJECTED),
                ui.lp(ui.dp(190), ui.dp(190), top = 8, gravity = Gravity.CENTER_HORIZONTAL))
            col.addView(ui.text(r.result.title, 30f, ui.color(R.color.fg), 800).apply {
                gravity = Gravity.CENTER
                letterSpacing = -0.025f
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, ui.lp())
            col.addView(ui.text(r.result.subtitle, 15f, ui.color(R.color.soft)).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
            }, ui.lp(top = 8))
            if (r.result.rows.isNotEmpty()) {
                val card = ui.vertical().apply {
                    background = ui.glass(22f)
                    setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4))
                }
                r.result.rows.forEachIndexed { i, (name, value) ->
                    val row = ui.horizontal(16).apply { minimumHeight = ui.dp(48) }
                    row.addView(ui.label(name))
                    row.addView(ui.text(value, if (name == "UID" || value.startsWith("http")) 13f else 13.5f, ui.color(R.color.fg),
                        mono = name == "UID" || value.startsWith("http")).apply {
                        gravity = Gravity.END
                        setTextIsSelectable(true)
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    card.addView(row)
                    if (i < r.result.rows.size - 1) card.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) }, ui.lp(height = 1))
                }
                col.addView(card, ui.lp(top = 24))
            }
            if (r.log.isNotBlank()) {
                val logCard = ui.vertical(10).apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)) }
                ui.pressable(logCard, ui.glass(22f)) { logOpen = !logOpen; render() }
                val head = ui.horizontal(10)
                head.addView(ui.text(getString(R.string.what_knurd_did), 14.5f, ui.color(R.color.fg), 600), LinearLayout.LayoutParams(0, WRAP, 1f))
                head.addView(ui.icon(R.drawable.ic_ui_chevron, 16, ui.color(R.color.muted)).apply { rotation = if (logOpen) 90f else 0f })
                logCard.addView(head)
                if (logOpen) {
                    logCard.addView(ui.text(r.log.trimEnd(), 12f, Color.parseColor("#D5DDE6"), mono = true).apply {
                        background = ui.rounded(Color.parseColor("#070A0E"), 12f)
                        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                        setTextIsSelectable(true)
                    })
                }
                col.addView(logCard, ui.lp(top = 16))
            }
            val buttons = ui.horizontal(10)
            buttons.addView(ui.button(getString(R.string.copy_details), Ui.Kind.OUTLINE) { copyDetails() }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (r.again != null) {
                buttons.addView(ui.button(r.again) {
                    val pack = packCard
                    if (action == Action.CLAIM && pack != null && running == null) begin(Action.CLAIM, pack) else start(action)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            col.addView(buttons, ui.lp(top = 24))
            if (!resultFelt) {
                resultFelt = true
                root.post { root.performHapticFeedback(if (good) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT) }
            }
        }
    }

    // ---------------------------------------------------------------- tag, notifications, walkthrough

    private fun showTag(uid: String) {
        openTag = uid
        tagFrom = if (screen in TABS) screen else Screen.TAGS
        go(Screen.TAG)
    }

    private fun tagDetail(): View = tabPage { outer ->
        val col = centered(outer)
        val uid = openTag ?: return@tabPage
        val s = snapshot
        val info = s?.tags?.firstOrNull { it.uid == uid }
        val taps = s?.taps?.filter { it.uid == uid }.orEmpty()
        val top = ui.horizontal()
        top.addView(ui.circleButton(R.drawable.ic_ui_back, "Back", 44) { go(tagFrom) })
        top.addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ui.text("Tag", 15f, ui.color(R.color.fg), 600))
        top.addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ui.space(), LinearLayout.LayoutParams(ui.dp(44), 1))
        col.addView(top, ui.lp(height = ui.dp(48)))
        noticeLine(col, onDark = false)

        val head = ui.horizontal(14)
        head.addView(ui.iconBox(R.drawable.ic_ui_tag, 64, 30, ui.color(R.color.accent_stroke), ui.color(R.color.accent_tint), 20f),
            LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)))
        val words = ui.vertical(4)
        val named = tagName(uid)
        words.addView(ui.text(named ?: "Unnamed tag", 24f, ui.color(R.color.fg), 700).apply {
            letterSpacing = -0.02f
            contentDescription = (named ?: "Unnamed tag") + ". Double tap to rename."
            setOnClickListener { renameTag(uid) }
        })
        words.addView(ui.text(uid, 12.5f, ui.color(R.color.muted), mono = true).apply { setTextIsSelectable(true) })
        head.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (info != null) head.addView(ui.statusPill("Signed", ui.color(R.color.ok), ui.color(R.color.ok_text), ui.color(R.color.ok_tint)))
        col.addView(head, ui.lp(top = 12))

        val weekStart = Instant.now().minus(java.time.Duration.ofDays(7))
        val stats = ui.horizontal(8)
        stats.addView(stat(taps.count { it.ok && it.time.isAfter(weekStart) }.toString(), "this week"), LinearLayout.LayoutParams(0, WRAP, 1f))
        stats.addView(stat(info?.lastCounter?.toString() ?: "–", "all time"), LinearLayout.LayoutParams(0, WRAP, 1f))
        stats.addView(stat(taps.count { !it.ok }.toString(), "rejected"), LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(stats, ui.lp(top = 18))

        col.addView(ui.label("When tapped"), ui.lp(top = 22))
        val action = ui.vertical(10).apply {
            background = ui.glass(22f)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        }
        val actionRow = ui.horizontal(12)
        actionRow.addView(ui.iconBox(R.drawable.ic_ui_pulse, 40, 20, ui.color(R.color.accent_stroke), ui.color(R.color.icon_bg), 13f),
            LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        val lastRan = taps.firstOrNull { it.ok }
        val aw = ui.vertical(2)
        aw.addView(ui.text(when {
            lastRan == null -> "No verified taps yet"
            lastRan.actions == 0 -> "Last tap ran no actions"
            lastRan.actions == 1 -> "Last tap ran 1 action"
            else -> "Last tap ran ${lastRan.actions} actions"
        }, 14.5f, ui.color(R.color.fg), 600))
        aw.addView(ui.text(if (hosted) "Pick what it does on your account's dashboard."
            else "Your server runs the knot for this tag from knurgN's settings.", 12.5f, ui.color(R.color.muted)))
        actionRow.addView(aw, LinearLayout.LayoutParams(0, WRAP, 1f))
        action.addView(actionRow)
        action.addView(ui.button("How to set actions", Ui.Kind.OUTLINE, 44) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.knura.io")))
        }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
        col.addView(action, ui.lp(top = 10))

        col.addView(sectionHead("Recent taps", "See all") { go(Screen.TAPS) }, ui.lp(top = 18))
        if (taps.isEmpty()) {
            col.addView(ui.text(if (s == null) "Unlock to see this tag's taps." else "Your server doesn't remember any taps from this tag.",
                14f, ui.color(R.color.muted)), ui.lp(top = 6))
        } else {
            col.addView(tapCard(taps.take(5)), ui.lp(top = 6))
        }

        val buttons = ui.horizontal(10)
        buttons.addView(ui.button("Rename", Ui.Kind.OUTLINE, 52) { renameTag(uid) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        buttons.addView(ui.button("Reset tag", Ui.Kind.DANGER, 52) { start(Action.RESET) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(buttons, ui.lp(top = 22))
        if (s != null) updatedLine(col, s)
    }

    private fun addNotes(found: List<Note>) {
        if (found.isEmpty()) return
        notes = Note.merge(notes, found)
        prefs.edit().putString("notes", Note.listToJson(notes)).apply()
    }

    /** Opens the notifications and marks them read; the screen still shows which were new. */
    private fun openNotes() {
        notesShown = notes
        notes = notes.map { it.copy(unread = false) }
        prefs.edit().putString("notes", Note.listToJson(notes)).apply()
        go(Screen.NOTES)
    }

    private fun notifications(): View = tabPage { outer ->
        val col = centered(outer)
        val top = ui.horizontal(10)
        top.addView(ui.circleButton(R.drawable.ic_ui_back, "Back", 44) { go(Screen.HOME) })
        top.addView(pageTitle("Notifications"), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (notesShown.isNotEmpty()) {
            top.addView(ui.text("Clear", 14f, ui.color(R.color.accent_text), 500).apply {
                setPadding(ui.dp(10), ui.dp(12), ui.dp(4), ui.dp(12))
                setOnClickListener {
                    notes = emptyList()
                    notesShown = emptyList()
                    prefs.edit().remove("notes").apply()
                    render()
                }
            })
        }
        col.addView(top, ui.lp(height = ui.dp(52)))
        col.addView(ui.text("Rejected taps, new tags and tokens, and the server going offline show up here when the app checks your server. " +
            "Alerts while the app is closed need a server update that is still to come.", 13f, ui.color(R.color.muted)).apply {
            background = ui.rounded(ui.color(R.color.icon_bg), 16f)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            setLineSpacing(0f, 1.15f)
        }, ui.lp(top = 12))
        if (notesShown.isEmpty()) {
            val box = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
            box.addView(ui.iconBox(R.drawable.ic_ui_bell, 72, 30, ui.color(R.color.muted), ui.color(R.color.icon_bg), 24f),
                ui.lp(ui.dp(72), ui.dp(72), top = 48))
            box.addView(ui.text("Nothing yet", 18f, ui.color(R.color.fg), 600), ui.lp(WRAP, WRAP, top = 14))
            box.addView(ui.text("You're all caught up.", 14f, ui.color(R.color.muted)), ui.lp(WRAP, WRAP, top = 4))
            col.addView(box)
            return@tabPage
        }
        val today = Instant.now().atZone(zone).toLocalDate()
        val groups = notesShown.groupBy { if (it.time.atZone(zone).toLocalDate() == today) "Today" else "Earlier" }
        for (name in listOf("Today", "Earlier")) {
            val list = groups[name] ?: continue
            col.addView(ui.label(name), ui.lp(top = 20))
            val card = ui.vertical().apply {
                background = ui.glass(22f)
                setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
            }
            list.forEachIndexed { i, n ->
                val row = ui.horizontal(12).apply {
                    gravity = Gravity.TOP
                    setPadding(0, ui.dp(12), 0, ui.dp(12))
                }
                val (icon, fg, tint) = when (n.kind) {
                    Note.Kind.BAD -> Triple(R.drawable.ic_ui_shield_x, ui.color(R.color.bad_text), ui.color(R.color.bad_tint))
                    Note.Kind.OK -> Triple(R.drawable.ic_ui_check, ui.color(R.color.ok_text), ui.color(R.color.ok_tint))
                    Note.Kind.INFO -> Triple(R.drawable.ic_ui_key, ui.color(R.color.accent_text), ui.color(R.color.accent_tint))
                    Note.Kind.WARN -> Triple(R.drawable.ic_ui_link_off, ui.color(R.color.soft), ui.color(R.color.icon_bg))
                }
                row.addView(ui.iconBox(icon, 38, 18, fg, tint, 12f), LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
                val w = ui.vertical(3)
                val titleRow = ui.horizontal(8)
                titleRow.addView(ui.text(n.title, 14.5f, ui.color(R.color.fg), 600), LinearLayout.LayoutParams(0, WRAP, 1f))
                titleRow.addView(ui.text(Words.clock(n.time, zone), 12f, ui.color(R.color.muted)))
                if (n.unread) {
                    titleRow.addView(View(this).apply {
                        background = ui.oval(ui.color(R.color.accent))
                        contentDescription = "New"
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    }, LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)))
                }
                w.addView(titleRow)
                w.addView(ui.text(n.body, 13f, ui.color(R.color.muted)).apply { setLineSpacing(0f, 1.15f) })
                row.addView(w, LinearLayout.LayoutParams(0, WRAP, 1f))
                card.addView(row)
                if (i < list.size - 1) card.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) }, ui.lp(height = 1))
            }
            col.addView(card, ui.lp(top = 10))
        }
    }

    /** Home's prompt to set up a first tag, while the server has none. */
    private fun firstTagCard(): View {
        val card = ui.horizontal(14).apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(14), ui.dp(14)) }
        ui.pressable(card, ui.rounded(ui.color(R.color.accent_tint), 22f, ui.color(R.color.accent_stroke) and 0x00FFFFFF or 0x66000000)) { startWalk() }
        card.addView(ui.iconBox(R.drawable.ic_ui_plus, 44, 22, ui.color(R.color.on_accent), ui.color(R.color.accent), 14f),
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val w = ui.vertical(2)
        w.addView(ui.text("Set up your first tag", 15f, ui.color(R.color.fg), 600))
        w.addView(ui.text("Five quick steps, start to first tap", 12.5f, ui.color(R.color.muted)))
        card.addView(w, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(ui.icon(R.drawable.ic_ui_chevron, 16, ui.color(R.color.accent_text)))
        return card
    }

    private fun startWalk() {
        walkStep = 0
        walking = false
        notice = null
        screen = Screen.WALK
        render()
    }

    /** Leaves the walkthrough for good; [show] goes home, otherwise the caller picks the screen. */
    private fun endWalk(show: Boolean = true) {
        walking = false
        prefs.edit().putBoolean("walked", true).apply()
        if (show) showHome(null)
    }

    private fun walk(): View = glowPage { col ->
        val steps = listOf(
            "Get a blank tag" to "Any NTAG 424 DNA tag works, including the round ones from a Knura pack.",
            "Hold it to the back of your phone" to "Keep it still for a moment. Your server writes the tag its own keys, and they never touch the phone.",
            "Choose what it does" to (if (hosted) "On your account's dashboard, pick an action for the tag, like turning on a light or sending a webhook. You can change it any time."
                else "In knurgN's settings on your server, give the tag a knot, like turning on a light in Home Assistant or sending a webhook. You can change it any time."),
            "Stick it where you will tap it" to "A door frame, desk or cabinet. Peel off the back and press firmly for a few seconds.",
            "Give it a try" to "Tap the tag with your phone. When the logo turns green, your server has checked it.",
        )
        val step = walkStep.coerceIn(0, 4)
        val top = ui.horizontal(6)
        for (i in 0..4) {
            top.addView(View(this).apply {
                background = ui.rounded(if (i <= step) Color.parseColor("#F28C3A") else Color.argb(46, 255, 255, 255), 2f)
            }, LinearLayout.LayoutParams(0, ui.dp(4), 1f))
        }
        top.addView(ui.text("Skip", 14f, ON_DARK_SOFT, 500).apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(4), ui.dp(12))
            setOnClickListener { endWalk() }
        })
        top.contentDescription = "Step ${step + 1} of 5"
        col.addView(top, ui.lp(height = ui.dp(48)))
        col.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(WalkView(this, step), ui.lp(ui.dp(280), ui.dp(280), gravity = Gravity.CENTER_HORIZONTAL))
        col.addView(ui.space(weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(kicker("Step ${step + 1} of 5"))
        col.addView(ui.text(steps[step].first, 28f, ON_DARK, 800).apply {
            letterSpacing = -0.025f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, ui.lp(top = 10))
        col.addView(ui.text(steps[step].second, 15.5f, ON_DARK_SOFT).apply { setLineSpacing(0f, 1.25f) }, ui.lp(top = 10))
        if (step == 2) {
            col.addView(ui.text("How to set actions", 14.5f, ON_DARK_ACCENT, 600).apply {
                minHeight = ui.dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.knura.io"))) }
            }, ui.lp(top = 4))
        }
        noticeLine(col, onDark = true)
        val buttons = ui.horizontal(10)
        if (step > 0) {
            buttons.addView(ui.button("Back", Ui.Kind.ON_DARK) { walkStep--; render() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val (label, next) = when (step) {
            1 -> "Hold the tag now" to { walking = true; start(Action.PROVISION) }
            4 -> "Tap it now" to { walking = true; start(Action.TAP) }
            else -> "Continue" to { walkStep++; render() }
        }
        buttons.addView(ui.button(label, Ui.Kind.ORANGE) { next() }, LinearLayout.LayoutParams(0, WRAP, if (step > 0) 1.6f else 1f))
        col.addView(buttons, ui.lp(top = 24))
        if (step == 1 || step == 4) {
            col.addView(ui.text(if (step == 1) "Already set it up? Skip this step" else "Done for now", 14f, ON_DARK_MUTED, 500).apply {
                gravity = Gravity.CENTER
                minHeight = ui.dp(44)
                setOnClickListener { if (step == 1) { walkStep = 2; render() } else endWalk() }
            }, ui.lp(top = 6))
        }
    }

    private fun cancelHold() {
        stop()
        if (walking) {
            walking = false
            notice = null
            screen = Screen.WALK
            render()
        } else {
            showHome("Cancelled.")
        }
    }

    private fun closeResult() {
        forgetPackCard()
        if (walking) {
            walking = false
            screen = Screen.WALK
            render()
        } else {
            showHome(null)
        }
    }

    // ---------------------------------------------------------------- small parts

    private fun pageTitle(text: String): TextView = ui.text(text, 28f, ui.color(R.color.fg), 800).apply { letterSpacing = -0.025f }

    private fun sectionTitle(text: String): TextView = ui.text(text, 17f, ui.color(R.color.fg), 600).apply { letterSpacing = -0.01f }

    private fun sectionHead(title: String, link: String, onClick: () -> Unit): View = ui.horizontal().apply {
        addView(sectionTitle(title), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(ui.text(link, 13.5f, ui.color(R.color.accent_text), 500).apply {
            setPadding(ui.dp(8), ui.dp(10), 0, ui.dp(10))
            setOnClickListener { onClick() }
        })
    }

    private fun kicker(text: String): TextView = ui.text(text.uppercase(), 12.5f, ON_DARK_ACCENT, 600).apply { letterSpacing = 0.14f }

    private fun darkPill(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ui.rounded(DARK_PILL, 999f)
    }

    private fun darkGlass(): GradientDrawable = ui.rounded(Color.argb(15, 255, 255, 255), 22f, Color.argb(26, 255, 255, 255))

    private fun stepNumber(n: Int): View = ui.text(n.toString(), 13f, ON_DARK_ACCENT, 600).apply {
        gravity = Gravity.CENTER
        background = ui.oval(Color.argb(41, 242, 140, 58))
        layoutParams = LinearLayout.LayoutParams(ui.dp(28), ui.dp(28))
    }

    private fun stepIcon(state: Step, onDark: Boolean, small: Boolean = false): View {
        val size = if (small) 22 else 26
        val ok = if (onDark) Color.parseColor("#4ADE9B") else ui.color(R.color.ok)
        val accent = if (onDark) ON_DARK_ACCENT else ui.color(R.color.accent_text)
        return when (state) {
            Step.DONE -> ui.iconBox(R.drawable.ic_ui_check, size, if (small) 13 else 15, ok, Color.argb(41, Color.red(ok), Color.green(ok), Color.blue(ok)))
            Step.ACTIVE -> FrameLayout(this).apply {
                background = ui.oval(Color.TRANSPARENT, accent).apply { setStroke(ui.dp(2), accent) }
                addView(View(this@MainActivity).apply { background = ui.oval(accent) },
                    FrameLayout.LayoutParams(ui.dp(if (small) 7 else 8), ui.dp(if (small) 7 else 8), Gravity.CENTER))
            }
            Step.PENDING -> View(this).apply {
                background = ui.oval(Color.TRANSPARENT).apply { setStroke(ui.dp(2), Color.argb(56, 255, 255, 255)) }
            }
        }.also { it.layoutParams = LinearLayout.LayoutParams(ui.dp(size), ui.dp(size)) }
    }

    private fun noticeLine(col: LinearLayout, onDark: Boolean) {
        val text = notice ?: if (nfc == null) "This phone has no NFC, so it can't talk to tags." else null
        if (text.isNullOrEmpty()) return
        val bad = noticeBad || nfc == null
        val color = if (onDark) (if (bad) ON_DARK_BAD else ON_DARK_SOFT) else ui.color(if (bad) R.color.bad_text else R.color.soft)
        col.addView(ui.text(text, 13.5f, color).apply {
            gravity = if (onDark) Gravity.CENTER else Gravity.START
            background = if (onDark) null else ui.rounded(ui.color(if (bad) R.color.bad_tint else R.color.icon_bg), 14f)
            if (!onDark) setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, ui.lp(top = 12))
    }

    private fun tinted(res: Int, color: Int) = getDrawable(res)?.mutate()?.apply {
        setTint(color)
        setBounds(0, 0, ui.dp(20), ui.dp(20))
    }

    private fun statusWord(status: String) = when (status) {
        "ok" -> "Verified"
        "replay" -> "Replayed"
        "rejected" -> "Rejected"
        "malformed" -> "Malformed"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    // ---------------------------------------------------------------- names kept on the phone

    private fun tagNames(): JSONObject = try {
        JSONObject(prefs.getString("tagNames", "{}") ?: "{}")
    } catch (e: org.json.JSONException) {
        JSONObject()
    }

    private fun tagName(uid: String): String? = tagNames().optString(uid, "").ifEmpty { null }

    /** A tag's name from this phone, or its UID shortened. */
    private fun tagTitle(uid: String): String = tagName(uid) ?: Words.shortUid(uid)

    private fun sortedTags(s: Snapshot): List<Snapshot.TagInfo> =
        s.tags.sortedByDescending { s.lastTap(it.uid)?.time ?: Instant.EPOCH }

    private fun renameTag(uid: String) {
        val input = textInput(tagName(uid) ?: "", "Front door")
        AlertDialog.Builder(this)
            .setTitle("Name this tag")
            .setMessage("$uid\n\nThe name stays on this phone.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                val names = tagNames()
                val name = input.text.toString().trim()
                if (name.isEmpty()) names.remove(uid) else names.put(uid, name.take(40))
                prefs.edit().putString("tagNames", names.toString()).apply()
                Widgets.refresh(this)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editName() {
        val input = textInput(prefs.getString("name", "") ?: "", "Your name")
        AlertDialog.Builder(this)
            .setTitle("What should Knura call you?")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().take(30)
                prefs.edit().apply { if (name.isEmpty()) remove("name") else putString("name", name) }.apply()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun textInput(value: String, hint: String) = EditText(this).apply {
        setText(value)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        isSingleLine = true
        setSelection(text.length)
    }

    private fun padded(view: View) = FrameLayout(this).apply {
        setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        addView(view)
    }

    private fun copyDetails() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Knura", details))
        // Android 13+ shows its own "Copied" message.
    }

    // ---------------------------------------------------------------- moving between screens

    /** The home screen when paired, the welcome screen when not, with an optional [text] notice. */
    private fun showHome(text: String?, bad: Boolean = false) {
        walking = false
        notice = text
        noticeBad = bad
        screen = if (vault.isPaired()) Screen.HOME else Screen.WELCOME
        render()
    }

    private fun showResult(label: String, result: TagResults.Result, logText: String, again: String?) {
        shown = Shown(label, result, logText, again)
        resultFelt = false
        logOpen = false
        details = buildString {
            append(result.title).append('\n').append(result.subtitle).append('\n')
            for ((name, value) in result.rows) append(name).append(": ").append(value).append('\n')
            if (logText.isNotBlank()) append('\n').append(logText.trimEnd())
        }
        screen = Screen.RESULT
        notice = null
        render()
    }

    // ---------------------------------------------------------------- pairing

    private fun scanPairingCode() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val scanned = try {
                    Pairing.parse(barcode.rawValue ?: "")
                } catch (e: IllegalArgumentException) {
                    showHome(e.message, bad = true)
                    return@addOnSuccessListener
                }
                pairHosted = scanned.hosted
                pairUrl = scanned.url
                notice = null
                if (!scanned.needsToken) {
                    pairStep = 2
                    screen = Screen.PAIRING
                    render()
                    savePairing(scanned)
                    return@addOnSuccessListener
                }
                // A one-time code: swap it for the token first, then save that.
                pairStep = 1
                screen = Screen.PAIRING
                render()
                worker.execute {
                    val pairing = try {
                        KnurdClient(scanned).pair()
                    } catch (e: IOException) {
                        ui { showHome("Couldn't pair: ${e.message}. Make a new pairing code and scan it.", bad = true) }
                        return@execute
                    } catch (e: IllegalArgumentException) {
                        ui { showHome("Couldn't pair: ${e.message}", bad = true) }
                        return@execute
                    } catch (e: org.json.JSONException) {
                        ui { showHome("Couldn't pair: the server's answer didn't make sense", bad = true) }
                        return@execute
                    }
                    ui {
                        pairStep = 2
                        if (screen == Screen.PAIRING) render()
                        savePairing(pairing)
                    }
                }
            }
            .addOnFailureListener { e -> showHome("Couldn't scan: ${e.message}", bad = true) }
    }

    /** Unlocks and keeps [pairing], whose token is ready, then loads the server's status. */
    private fun savePairing(pairing: Pairing) {
        unlock("Unlock to save the pairing") {
            try {
                vault.save(pairing)
            } catch (e: Exception) {
                showHome("Couldn't save the pairing: ${e.message}. Does this phone have a screen lock?", bad = true)
                return@unlock
            }
            snapshot = null
            prefs.edit().remove("snapshot").apply()
            link = Link.UNKNOWN
            val fresh = KnurdClient(pairing)
            session = fresh
            offerWalk = true
            showHome("Paired. Pick what to do with a tag.")
            load(fresh)
        }
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle(if (hosted) "Forget this account?" else "Forget this knurd?")
            .setMessage("The phone deletes its token. To use it again, pair with a new code from knurctl admin pair-phone or your account page.")
            .setPositiveButton("Forget") { _, _ -> forgetServer() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun forgetServer() {
        vault.forget()
        session = null
        snapshot = null
        notes = emptyList()
        prefs.edit().remove("notes").remove("link").apply()
        link = Link.UNKNOWN
        prefs.edit().remove("snapshot").apply()
        Widgets.refresh(this)
        showHome(null)
    }

    /** Asks for fingerprint or screen lock, then runs [then] on the main thread. */
    private fun unlock(title: String, then: () -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                then()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                showHome("Not unlocked: $errString", bad = true)
            }
        }
        try {
            BiometricPrompt.Builder(this)
                .setTitle(title)
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(CancellationSignal(), mainExecutor, callback)
        } catch (e: RuntimeException) {
            showHome("Couldn't ask for your fingerprint or screen lock: ${e.message}", bad = true)
        }
    }

    /** The saved pairing as a client, or null after saying what went wrong. */
    private fun knurd(): KnurdClient? = try {
        KnurdClient(vault.load())
    } catch (e: Exception) {
        showHome("Couldn't read the pairing (${e.message}). Forget this server and pair again.", bad = true)
        null
    }

    // ---------------------------------------------------------------- the server's status, tags and taps

    /** Loads the latest from the server, asking to unlock only if the app isn't unlocked already. */
    private fun refresh() {
        if (!vault.isPaired() || loading || running != null) return
        val open = session
        if (open != null) {
            load(open)
            return
        }
        unlock("Unlock to see your server") {
            val knurd = knurd() ?: return@unlock
            session = knurd
            load(knurd)
        }
    }

    /** Reads status, tags, taps and tokens in one go, and keeps them as the new [snapshot]. */
    private fun load(knurd: KnurdClient) {
        loading = true
        if (screen in TABS) render()
        worker.execute {
            var next: Snapshot? = null
            var error: String? = null
            var offline = false
            try {
                val status = knurd.get("/v1/status")
                if (status.code != 200) {
                    error = "The server said no: " + status.json.optString("detail", "status ${status.code}")
                } else {
                    val tags = knurd.get("/v1/tags")
                    val taps = knurd.get("/v1/taps?limit=200")
                    // A hosted account may not list tokens; the Server tab leaves that part out then.
                    val tokens = try {
                        knurd.get("/v1/tokens").takeIf { it.code == 200 }?.json
                    } catch (e: IOException) {
                        null
                    }
                    next = Snapshot.of(Instant.now(), status.json, tags.json.takeIf { tags.code == 200 },
                        taps.json.takeIf { taps.code == 200 }, tokens)
                }
            } catch (e: IOException) {
                offline = true
                error = explain(e, knurd.url)
            } catch (e: org.json.JSONException) {
                error = "The server's answer didn't make sense: ${e.message}"
            }
            ui {
                loading = false
                val fresh = next
                if (fresh != null) {
                    val found = Note.changes(snapshot, fresh) { tagName(it) }.toMutableList()
                    offlineSince?.let { since ->
                        val minutes = java.time.Duration.between(since, fresh.at).toMinutes()
                        found += Note(Note.Kind.OK, "Server is back online",
                            "It answered again after " + (if (minutes < 1) "less than a minute" else if (minutes == 1L) "1 minute" else "$minutes minutes") + " offline.", fresh.at)
                    }
                    offlineSince = null
                    addNotes(found)
                    snapshot = fresh
                    link = Link.ONLINE
                    linkError = null
                    prefs.edit().putString("snapshot", fresh.toJson().toString()).putString("link", link.name).apply()
                    Widgets.refresh(this)
                    if (offerWalk && fresh.tags.isEmpty() && !prefs.getBoolean("walked", false) && screen == Screen.HOME) {
                        offerWalk = false
                        startWalk()
                        return@ui
                    }
                    offerWalk = false
                } else if (offline) {
                    if (link != Link.OFFLINE) {
                        val now = Instant.now()
                        offlineSince = now
                        addNotes(listOf(Note(Note.Kind.WARN, "Server went offline",
                            "${knurd.url.removePrefix("https://")} stopped answering at ${Words.clock(now, zone)}.", now)))
                    }
                    link = Link.OFFLINE
                    linkError = error
                    prefs.edit().putString("link", link.name).apply()
                    Widgets.refresh(this)
                } else if (error != null && screen in TABS) {
                    notice = error
                    noticeBad = true
                }
                if (screen in TABS) render()
            }
        }
    }

    /** Removes a token after asking, like knurctl admin token remove. */
    private fun confirmRemove(name: String, isThisPhone: Boolean) {
        val warning = if (isThisPhone) "This phone's own token: the app won't reach the server again until you pair it again."
        else "Whatever uses it (a phone or a knurctl creds file) stops working right away."
        AlertDialog.Builder(this)
            .setTitle("Remove token \"$name\"?")
            .setMessage(warning)
            .setPositiveButton("Remove") { _, _ -> withSession { knurd -> remove(knurd, name, isThisPhone) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun remove(knurd: KnurdClient, name: String, isThisPhone: Boolean) {
        worker.execute {
            var removed = false
            val text = try {
                val reply = knurd.delete("/v1/tokens/$name")
                removed = reply.code == 200
                if (removed) "Removed token \"$name\". The server refuses it from now on."
                else "The server said no: " + reply.json.optString("detail", "status ${reply.code}")
            } catch (e: IOException) {
                explain(e, knurd.url)
            }
            ui {
                if (isThisPhone && removed) {
                    forgetServer()
                    return@ui
                }
                notice = text
                noticeBad = !removed
                if (removed) load(knurd) else render()
            }
        }
    }

    /** Runs [then] with the unlocked server, asking to unlock first if needed. */
    private fun withSession(then: (KnurdClient) -> Unit) {
        val open = session
        if (open != null) {
            then(open)
            return
        }
        unlock("Unlock to use your server") {
            val knurd = knurd() ?: return@unlock
            session = knurd
            then(knurd)
        }
    }

    // ---------------------------------------------------------------- tag sessions

    private fun start(a: Action) {
        val adapter = nfc ?: return
        if (running != null) return
        if (!vault.isPaired()) return
        if (!adapter.isEnabled) {
            showHome("NFC is off. Turn it on in Settings, then try again.", bad = true)
            return
        }
        forgetPackCard()
        action = a
        if (a == Action.CLAIM) {
            askForClaim { card -> begin(a, card) }
        } else {
            begin(a, null)
        }
    }

    private fun begin(a: Action, card: ClaimCodes.Claim?) {
        unlock("Unlock to use your server") {
            val knurd = knurd() ?: return@unlock
            session = knurd
            claim = card
            // A new tag has factory keys; a tag being reset was most likely set up by Knura.
            from = when (a) {
                Action.PROVISION -> "factory"
                Action.RESET -> "knura"
                Action.CLAIM -> "claim"
                else -> null
            }
            // Last: a tag arriving on the worker thread starts relaying once running is set.
            client = knurd
            running = a
            action = a
            holdTitle = getString(R.string.hold_title)
            holdHint = getString(R.string.hold_hint)
            holdHintBad = false
            holdFound = false
            holdSteps[0] = Step.DONE
            holdSteps[1] = Step.ACTIVE
            holdSteps[2] = Step.PENDING
            notice = null
            screen = Screen.HOLD
            render()
            // NFC may have been turned on since the app came to the front.
            listenForTags()
        }
    }

    private fun hint(text: String, bad: Boolean) {
        holdHint = text
        holdHintBad = bad
        holdHintView?.apply {
            this.text = text
            setTextColor(if (bad) ON_DARK_BAD else ON_DARK_SOFT)
        }
    }

    /** Gets the code from a claim card, by its QR code or typed in, then runs [then] with it. */
    private fun askForClaim(then: (ClaimCodes.Claim) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.claim_title)
            .setMessage(R.string.claim_how)
            .setPositiveButton(R.string.claim_scan) { _, _ -> scanClaim(then) }
            .setNeutralButton(R.string.claim_type) { _, _ -> typeClaim(then) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun scanClaim(then: (ClaimCodes.Claim) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> readClaim(barcode.rawValue ?: "", then) }
            .addOnFailureListener { e -> showHome("Couldn't scan: ${e.message}", bad = true) }
    }

    private fun typeClaim(then: (ClaimCodes.Claim) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.claim_code_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = ui.font(400, mono = true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.claim_title)
            .setView(padded(input))
            .setPositiveButton(R.string.claim_continue) { _, _ -> readClaim(input.text.toString(), then) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun readClaim(text: String, then: (ClaimCodes.Claim) -> Unit) {
        val card = try {
            ClaimCodes.parse(text)
        } catch (e: IllegalArgumentException) {
            showHome(e.message, bad = true)
            return
        }
        then(card)
    }

    /**
     * Reader mode for as long as the app is in front: every tag comes to the app, and Android
     * doesn't open the tag's link in the browser, even when the tag is already at the phone
     * before a session or while the unlock prompt is up. Tags only do something during a session.
     */
    private fun listenForTags() {
        val adapter = nfc ?: return
        if (!adapter.isEnabled) return
        adapter.enableReaderMode(this, { tag -> worker.execute { onTag(tag) } },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    /** On the worker thread. */
    private fun onTag(tag: Tag) {
        if (running != null) {
            relay(tag)
        } else {
            ui {
                if (screen == Screen.HOME) {
                    notice = "Choose what to do first, then hold the tag."
                    noticeBad = false
                    render()
                }
            }
        }
    }

    /** Ends the session; tags are ignored again until the next one. The caller picks the next screen. */
    private fun stop() {
        client = null
        running = null
        claim = null
    }

    /** Runs one session with knurd for this tag. On the worker thread. */
    private fun relay(tag: Tag) {
        val knurd = client ?: return
        val a = running ?: return
        val keys = from
        val card = claim
        val uid = tag.id.toHex()
        if (a == Action.CLAIM && card?.uid != null && card.uid != uid) {
            ui { hint("This isn't the tag from the claim card: the card is for ${card.uid}, this tag is $uid.", bad = true) }
            return
        }
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            ui { hint("This tag isn't an NTAG 424 DNA (it has no ISO-DEP). Try another tag.", bad = true) }
            return
        }
        ui {
            holdTitle = "Keep holding it"
            holdTitleView?.text = holdTitle
            hint(getString(R.string.hold_hint), bad = false)
            holdFound = true
            holdSteps[1] = Step.DONE
            holdSteps[2] = Step.ACTIVE
            holdStepsView?.let { fillHoldSteps(it, a) }
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        val lines = StringBuilder()
        try {
            isoDep.connect()
            isoDep.timeout = 5000
            val start = JSONObject().put("action", a.api).put("uid", uid)
            if (keys != null) {
                start.put("from", keys)
            }
            if (card != null) {
                start.put("claim", card.code)
            }
            var reply = knurd.post("/v1/nfc/sessions", start)
            while (true) {
                collect(lines, reply.json.optJSONArray("log"))
                if (reply.code != 200) {
                    fail(a, reply.json.optString("detail", "knurd answered ${reply.code}"), lines)
                    return
                }
                when (reply.json.optString("status")) {
                    "command" -> {
                        val command = reply.json.getString("command").hexToBytes()
                        val answer = try {
                            JSONObject().put("response", isoDep.transceive(command).toHex())
                        } catch (e: IOException) {
                            JSONObject().put("error", e.message ?: "tag lost")
                        }
                        reply = knurd.post("/v1/nfc/sessions/" + reply.json.getString("session"), answer)
                    }
                    "done" -> {
                        val result = TagResults.describe(if (a == Action.CLAIM) "claim" else a.api,
                            reply.json.getJSONObject("result"))
                        ui {
                            stop()
                            if (walking && a == Action.PROVISION && result.good) {
                                // The walkthrough's tag has its keys: on to choosing what it does.
                                walkStep = 2
                                screen = Screen.WALK
                                render()
                                root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                session?.let { load(it) }
                                return@ui
                            }
                            if (walking && a == Action.TAP) endWalk(show = false)
                            if (a == Action.CLAIM && card != null && card.pack) {
                                // A pack card: keep it, so the next tag of the pack needs no new scan.
                                packCard = card
                                packClaimed++
                                showResult(packLabel(), result, lines.toString(), getString(R.string.claim_next))
                            } else {
                                showResult(a.label, result, lines.toString(), a.again)
                            }
                            // The tag's tap or new keys show up in the tabs without another unlock.
                            session?.let { load(it) }
                        }
                        return
                    }
                    else -> {
                        fail(a, reply.json.optString("detail", "unknown error"), lines)
                        return
                    }
                }
            }
        } catch (e: IOException) {
            fail(a, explain(e, knurd.url), lines)
        } finally {
            try {
                isoDep.close()
            } catch (e: IOException) {
                // Already gone.
            }
        }
    }

    private fun fail(a: Action, why: String, lines: StringBuilder) = ui {
        val card = claim
        stop()
        if (a == Action.CLAIM && card != null && card.pack) {
            // Keep a pack card through a failed tag too: "Try again" goes on with the same card.
            packCard = card
        }
        showResult(if (packCard != null) packLabel() else a.label,
            TagResults.Result(false, "Didn't work", why, emptyList()), lines.toString(), getString(R.string.try_again))
    }

    /** The result screen's heading while claiming the tags of a pack card. */
    private fun packLabel() = resources.getQuantityString(R.plurals.claim_pack_count, packClaimed, packClaimed)

    private fun forgetPackCard() {
        packCard = null
        packClaimed = 0
    }

    private fun collect(into: StringBuilder, lines: JSONArray?) {
        if (lines == null) return
        for (i in 0 until lines.length()) {
            into.append(lines.getString(i)).append('\n')
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private companion object {
        val TABS = setOf(Screen.HOME, Screen.TAGS, Screen.TAPS, Screen.SERVER)

        // The welcome, pairing and scan screens are dark in both themes.
        val ON_DARK = Color.parseColor("#E8EDF3")
        val ON_DARK_SOFT = Color.parseColor("#B7C0CB")
        val ON_DARK_MUTED = Color.parseColor("#8B97A6")
        val ON_DARK_ACCENT = Color.parseColor("#FFB86B")
        val ON_DARK_BAD = Color.parseColor("#F87171")
        val DARK_PILL = Color.argb(140, 10, 13, 18)
    }
}
