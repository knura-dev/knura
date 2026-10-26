package io.knura.android

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.CancellationSignal
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.IOException
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pair with knurd, pick what to do, unlock, hold a tag to the phone. The phone only relays: knurd
 * sends each command for the tag and checks every reply, and the tag's keys never reach the phone.
 * The screens all live in activity_main.xml and this activity shows one at a time.
 */
class MainActivity : Activity() {
    /** What the app can do, with knurd's name for each and the words the screens use for it. */
    private enum class Action(val api: String, val label: String, val doing: String, val again: String) {
        PROVISION("provision", "Set up a tag", "knurd writes the keys", "Set up another"),
        INFO("info", "Tag info", "knurd reads the tag", "Read another"),
        TAP("tap", "Check a tap", "knurd checks the signature", "Check another"),
        RESET("reset", "Reset tag", "knurd resets the keys", "Reset another"),
        /** A provision from the claim keys on a ready-to-use tag's claim card. */
        CLAIM("provision", "Claim a tag", "knurd swaps in your keys", "Claim another"),
    }

    private enum class Step { PENDING, ACTIVE, DONE }

    private lateinit var vault: TokenVault
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var screens: List<View>
    private lateinit var screenUnpaired: View
    private lateinit var screenHome: View
    private lateinit var screenWorking: View
    private lateinit var screenResult: View
    private lateinit var screenKnurd: View
    private lateinit var tiles: Map<Action, View>
    private lateinit var knuraKeys: Switch
    private lateinit var startButton: Button
    private lateinit var log: TextView

    private val worker = Executors.newSingleThreadExecutor()
    private var nfc: NfcAdapter? = null
    private var action = Action.PROVISION
    /** Set while waiting for a tag or relaying to one. */
    @Volatile private var client: KnurdClient? = null
    @Volatile private var running: Action? = null
    /** The keys the tag has now, as knurd names them, or null when the action doesn't need it. */
    private var from: String? = null
    /** The claim card's code while claiming a tag. */
    @Volatile private var claim: ClaimCodes.Claim? = null
    /**
     * A pack card (a claim card with no UID, for several tags) kept after a claim so "Claim next
     * tag" needs no new scan, and how many tags it has claimed. UI thread only; cleared on leaving
     * the result screen.
     */
    private var packCard: ClaimCodes.Claim? = null
    private var packClaimed = 0
    private var pulse: ValueAnimator? = null
    /** The last result, as text for Copy details. */
    private var details = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContentView(R.layout.activity_main)
        vault = TokenVault(this)
        nfc = NfcAdapter.getDefaultAdapter(this)
        scroll = findViewById<ScrollView>(R.id.scroll)
        content = findViewById<LinearLayout>(R.id.content)
        screenUnpaired = findViewById<View>(R.id.screenUnpaired)
        screenHome = findViewById<View>(R.id.screenHome)
        screenWorking = findViewById<View>(R.id.screenWorking)
        screenResult = findViewById<View>(R.id.screenResult)
        screenKnurd = findViewById<View>(R.id.screenKnurd)
        screens = listOf(screenUnpaired, screenHome, screenWorking, screenResult, screenKnurd)
        tiles = mapOf(
            Action.PROVISION to findViewById<View>(R.id.tileProvision),
            Action.INFO to findViewById<View>(R.id.tileInfo),
            Action.TAP to findViewById<View>(R.id.tileTap),
            Action.RESET to findViewById<View>(R.id.tileReset),
            Action.CLAIM to findViewById<View>(R.id.tileClaim),
        )
        knuraKeys = findViewById<Switch>(R.id.knuraKeys)
        startButton = findViewById<Button>(R.id.start)
        log = findViewById<TextView>(R.id.log)

        withIcon(findViewById<Button>(R.id.pair), R.drawable.ic_scan)
        withIcon(startButton, R.drawable.ic_nfc_on_accent)
        findViewById<View>(R.id.knurdMenu).clipToOutline = true
        findViewById<View>(R.id.knurdRows).clipToOutline = true

        findViewById<View>(R.id.pair).setOnClickListener { scanPairingCode() }
        findViewById<View>(R.id.forget).setOnClickListener { confirmForget() }
        for ((a, tile) in tiles) {
            tile.setOnClickListener { pick(a) }
        }
        pick(Action.PROVISION)
        startButton.setOnClickListener { start() }
        findViewById<View>(R.id.cancel).setOnClickListener { stop(); showHome("Cancelled.") }
        findViewById<View>(R.id.resultClose).setOnClickListener { forgetPackCard(); showHome(null) }
        findViewById<View>(R.id.again).setOnClickListener {
            val pack = packCard
            if (action == Action.CLAIM && pack != null && running == null) begin(Action.CLAIM, pack) else start()
        }
        findViewById<View>(R.id.copy).setOnClickListener { copyDetails() }
        findViewById<View>(R.id.logToggle).setOnClickListener {
            log.visibility = if (log.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.knurdBack).setOnClickListener { showHome(null) }
        findViewById<View>(R.id.knurdStatus).setOnClickListener {
            ask("/v1/status", getString(R.string.knurd_status), pairs = true, KnurdViews::status)
        }
        findViewById<View>(R.id.knurdTags).setOnClickListener {
            ask("/v1/tags", getString(R.string.knurd_tags), pairs = false, KnurdViews::tags)
        }
        findViewById<View>(R.id.knurdTaps).setOnClickListener {
            ask("/v1/taps?limit=20", getString(R.string.knurd_taps), pairs = false) { KnurdViews.taps(it) }
        }
        findViewById<View>(R.id.knurdTokens).setOnClickListener { pickTokenToRemove() }
        fitWidth()
        showHome(null)
        CrashLog.takeLast(this)?.let {
            showResult("Knura", TagResults.Result(false, "The app crashed last time",
                "Please send this to the Knura developers. Copy details copies it.", emptyList()), it, againLabel = null)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding or unfolding the phone: the manifest keeps the activity, so only the layout changes.
        fitWidth()
    }

    override fun onResume() {
        super.onResume()
        listenForTags()
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
        if (running != null) {
            stop()
            showHome("Stopped: the app went to the background.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    @Deprecated("Still the simplest way to handle Back on Android 11 and 12.")
    override fun onBackPressed() {
        when {
            running != null -> {
                stop()
                showHome("Cancelled.")
            }
            screenResult.visibility == View.VISIBLE || screenKnurd.visibility == View.VISIBLE -> showHome(null)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /**
     * Narrow screens (the Fold's cover screen) get one column. Wide ones (unfolded) put the tag
     * actions and knurd side by side on the home screen, and keep the other screens a readable width.
     */
    private fun fitWidth() {
        val widthDp = resources.configuration.screenWidthDp
        val wide = widthDp >= 600
        val columns = findViewById<LinearLayout>(R.id.homeColumns)
        val tagColumn = findViewById<View>(R.id.homeTagColumn)
        val knurdColumn = findViewById<View>(R.id.homeKnurdColumn)
        columns.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        tagColumn.layoutParams = LinearLayout.LayoutParams(
            if (wide) 0 else LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, if (wide) 1f else 0f)
        knurdColumn.layoutParams = LinearLayout.LayoutParams(
            if (wide) 0 else LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, if (wide) 1f else 0f
        ).apply {
            if (wide) marginStart = dp(24) else topMargin = dp(22)
        }
        val side = if (wide) dp(24) else dp(20)
        content.setPadding(side, content.paddingTop, side, content.paddingBottom)
        // Single-column screens stay at most 560dp wide, centered.
        val inner = if (wide) maxOf(0, (dp(widthDp) - 2 * side - dp(560)) / 2) else 0
        for (screen in listOf(screenUnpaired, screenWorking, screenResult, screenKnurd)) {
            screen.setPadding(inner, 0, inner, 0)
        }
    }

    // ---------------------------------------------------------------- screens

    private fun show(screen: View) {
        for (s in screens) {
            s.visibility = if (s === screen) View.VISIBLE else View.GONE
        }
        scroll.scrollTo(0, 0)
    }

    /** The home screen when paired, the pairing screen when not, with an optional [notice] under Start. */
    private fun showHome(notice: String?, bad: Boolean = false) {
        val isPaired = vault.isPaired()
        val noNfc = if (nfc == null) "This phone has no NFC, so it can't talk to tags." else null
        if (isPaired) {
            findViewById<TextView>(R.id.knurdUrl).text = vault.url?.removePrefix("https://") ?: ""
            // A Knura Cloud pairing points at an account address, https://<host>/o/<owner>.
            val cloud = vault.url?.contains("/o/") == true
            findViewById<TextView>(R.id.serverLabel).setText(if (cloud) R.string.your_cloud else R.string.your_knurd)
            findViewById<TextView>(R.id.serverSecurity).setText(if (cloud) R.string.cloud_security else R.string.knurd_security)
            startButton.isEnabled = nfc != null
            say(R.id.homeNotice, noNfc ?: notice, bad || noNfc != null)
            show(screenHome)
        } else {
            say(R.id.unpairedNotice, noNfc ?: notice, bad || noNfc != null)
            show(screenUnpaired)
        }
    }

    private fun say(id: Int, text: String?, bad: Boolean) {
        val view = findViewById<TextView>(id)
        view.text = text ?: ""
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        view.setTextColor(getColor(if (bad) R.color.bad_text else R.color.muted))
    }

    private fun pick(picked: Action) {
        forgetPackCard()
        action = picked
        for ((a, tile) in tiles) {
            tile.isSelected = a == picked
        }
        // A new tag has factory keys; a tag being reset was most likely set up by Knura.
        knuraKeys.visibility = if (picked == Action.PROVISION || picked == Action.RESET) View.VISIBLE else View.GONE
        knuraKeys.isChecked = picked == Action.RESET
    }

    private fun showWorking(a: Action) {
        findViewById<TextView>(R.id.workAction).text = a.label
        findViewById<TextView>(R.id.workTitle).setText(R.string.hold_title)
        hint(getString(R.string.hold_hint), bad = false)
        step(1, Step.DONE)
        findViewById<TextView>(R.id.step2Text).setText(R.string.step_waiting)
        step(2, Step.ACTIVE)
        findViewById<TextView>(R.id.step3Text).text = a.doing
        step(3, Step.PENDING)
        show(screenWorking)
        val target = findViewById<FrameLayout>(R.id.target)
        pulse = ObjectAnimator.ofPropertyValuesHolder(target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f)).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun hint(text: String, bad: Boolean) {
        val view = findViewById<TextView>(R.id.workHint)
        view.text = text
        view.setTextColor(getColor(if (bad) R.color.bad_text else R.color.muted))
    }

    private fun step(n: Int, state: Step) {
        val icon = findViewById<ImageView>(listOf(R.id.step1Icon, R.id.step2Icon, R.id.step3Icon)[n - 1])
        val spin = findViewById<ProgressBar>(listOf(R.id.step1Spin, R.id.step2Spin, R.id.step3Spin)[n - 1])
        val text = findViewById<TextView>(listOf(R.id.step1Text, R.id.step2Text, R.id.step3Text)[n - 1])
        spin.visibility = if (state == Step.ACTIVE) View.VISIBLE else View.GONE
        icon.visibility = if (state == Step.ACTIVE) View.GONE else View.VISIBLE
        icon.setImageResource(if (state == Step.DONE) R.drawable.ic_check_ok else R.drawable.step_pending)
        text.setTextColor(getColor(if (state == Step.PENDING) R.color.muted else R.color.fg))
        text.typeface = if (state == Step.ACTIVE) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
    }

    /** The result screen. [againLabel] null hides the button that runs the action again. */
    private fun showResult(label: String, result: TagResults.Result, logText: String, againLabel: String?) {
        findViewById<TextView>(R.id.resultAction).text = label
        findViewById<View>(R.id.resultIconBox).setBackgroundResource(
            if (result.good) R.drawable.bg_result_good else R.drawable.bg_result_bad)
        findViewById<ImageView>(R.id.resultIcon).setImageResource(
            if (result.good) R.drawable.ic_check_ok else R.drawable.ic_cross_bad)
        findViewById<TextView>(R.id.resultTitle).text = result.title
        findViewById<TextView>(R.id.resultSubtitle).text = result.subtitle
        val rows = findViewById<LinearLayout>(R.id.resultRows)
        rows.removeAllViews()
        for ((name, value) in result.rows) {
            rows.addView(pairRow(name, value, mono = name == "UID" || value.startsWith("http")))
        }
        rows.visibility = if (result.rows.isEmpty()) View.GONE else View.VISIBLE
        log.text = logText.trimEnd()
        log.visibility = View.VISIBLE
        findViewById<View>(R.id.logBox).visibility = if (logText.isBlank()) View.GONE else View.VISIBLE
        val again = findViewById<Button>(R.id.again)
        again.visibility = if (againLabel == null) View.GONE else View.VISIBLE
        again.text = againLabel ?: ""
        details = buildString {
            append(result.title).append('\n').append(result.subtitle).append('\n')
            for ((name, value) in result.rows) append(name).append(": ").append(value).append('\n')
            if (logText.isNotBlank()) append('\n').append(logText.trimEnd())
        }
        show(screenResult)
    }

    /** The screen for one of knurd's answers, first with a spinner while it loads. */
    private fun showKnurd(title: String) {
        findViewById<TextView>(R.id.knurdTitle).text = title
        findViewById<View>(R.id.knurdNote).visibility = View.GONE
        findViewById<View>(R.id.knurdRows).visibility = View.GONE
        findViewById<View>(R.id.knurdMessage).visibility = View.GONE
        findViewById<View>(R.id.knurdLoading).visibility = View.VISIBLE
        show(screenKnurd)
    }

    private fun showAnswer(answer: KnurdViews.Answer, pairs: Boolean) {
        findViewById<View>(R.id.knurdLoading).visibility = View.GONE
        val note = findViewById<TextView>(R.id.knurdNote)
        note.text = answer.note ?: ""
        note.visibility = if (answer.note == null || answer.rows.isEmpty()) View.GONE else View.VISIBLE
        val rows = findViewById<LinearLayout>(R.id.knurdRows)
        rows.removeAllViews()
        for (row in answer.rows) {
            rows.addView(if (pairs) pairRow(row.title, row.detail, row.mono) else listRow(row))
        }
        rows.visibility = if (answer.rows.isEmpty()) View.GONE else View.VISIBLE
        if (answer.rows.isEmpty()) knurdMessage(answer.empty ?: "Nothing here yet.", bad = false)
    }

    private fun knurdMessage(text: String, bad: Boolean) {
        findViewById<View>(R.id.knurdLoading).visibility = View.GONE
        val view = findViewById<TextView>(R.id.knurdMessage)
        view.text = text
        view.setTextColor(getColor(if (bad) R.color.bad_text else R.color.muted))
        view.visibility = View.VISIBLE
    }

    /** A label on the left and its value on the right, like "UID   04406B02936980". */
    private fun pairRow(name: String, value: String, mono: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(48)
        row.setPadding(dp(16), dp(10), dp(16), dp(10))
        row.addView(text(name, 14f, R.color.muted).apply { setPadding(0, 0, dp(16), 0) })
        row.addView(text(value, if (mono) 13f else 14f, R.color.fg, mono).apply {
            gravity = Gravity.END
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** A title, a detail line under it and an optional badge, like one tap in Recent taps. */
    private fun listRow(r: KnurdViews.Row): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(56)
        row.setPadding(dp(16), dp(10), dp(16), dp(10))
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(text(r.title, if (r.mono) 14f else 15f, R.color.fg, r.mono))
        if (r.detail.isNotEmpty()) {
            column.addView(text(r.detail, 12f, R.color.muted).apply { setPadding(0, dp(3), 0, 0) })
        }
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (r.badge != null) {
            val good = r.badgeKind == KnurdViews.Badge.GOOD
            row.addView(text(r.badge, 12f, if (good) R.color.ok_text else R.color.bad_text).apply {
                setBackgroundResource(if (good) R.drawable.bg_pill_good else R.drawable.bg_pill_bad)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
        return row
    }

    private fun text(value: String, sp: Float, color: Int, mono: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(getColor(color))
        if (mono) typeface = Typeface.MONOSPACE
    }

    /** Puts [icon] just before the button's centered text. */
    private fun withIcon(button: Button, icon: Int) {
        val drawable = getDrawable(icon) ?: return
        val size = dp(22)
        drawable.setBounds(0, 0, size, size)
        val text = SpannableString("  " + button.text)
        text.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        button.text = text
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun copyDetails() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Knura", details))
        // Android 13+ shows its own "Copied" message.
    }

    // ---------------------------------------------------------------- pairing

    private fun scanPairingCode() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val pairing = try {
                    Pairing.parse(barcode.rawValue ?: "")
                } catch (e: IllegalArgumentException) {
                    showHome(e.message, bad = true)
                    return@addOnSuccessListener
                }
                unlock("Unlock to save the pairing") {
                    try {
                        vault.save(pairing)
                        showHome("Paired. Pick what to do, then tap Start.")
                    } catch (e: Exception) {
                        showHome("Couldn't save the pairing: ${e.message}. Does this phone have a screen lock?", bad = true)
                    }
                }
            }
            .addOnFailureListener { e -> showHome("Couldn't scan: ${e.message}", bad = true) }
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle("Forget this knurd?")
            .setMessage("The phone deletes its token. To use knurd again, pair with a new code from knurctl admin pair-phone.")
            .setPositiveButton("Forget") { _, _ ->
                vault.forget()
                showHome(null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        showHome("Couldn't read the pairing (${e.message}). Forget this knurd and pair again.", bad = true)
        null
    }

    // ---------------------------------------------------------------- knurd's admin answers

    /** Unlocks, reads one of knurd's read-only admin answers and shows it, like knurctl admin status. */
    private fun ask(path: String, title: String, pairs: Boolean, view: (JSONObject) -> KnurdViews.Answer) {
        if (!vault.isPaired() || running != null) return
        unlock("Unlock to use knurd") {
            val knurd = knurd() ?: return@unlock
            showKnurd(title)
            worker.execute {
                try {
                    val reply = knurd.get(path)
                    if (reply.code == 200) {
                        val answer = view(reply.json)
                        ui { showAnswer(answer, pairs) }
                    } else {
                        ui { knurdMessage("knurd said no: " + reply.json.optString("detail", "status ${reply.code}"), bad = true) }
                    }
                } catch (e: IOException) {
                    ui { knurdMessage(explain(e, knurd.url), bad = true) }
                } catch (e: org.json.JSONException) {
                    ui { knurdMessage("knurd's answer didn't make sense: ${e.message}", bad = true) }
                }
            }
        }
    }

    /** Unlocks, lists knurd's admin tokens, and removes the one picked, like knurctl admin token remove. */
    private fun pickTokenToRemove() {
        if (!vault.isPaired() || running != null) return
        unlock("Unlock to use knurd") {
            val knurd = knurd() ?: return@unlock
            showHome("Asking knurd...")
            worker.execute {
                val reply = try {
                    knurd.get("/v1/tokens")
                } catch (e: IOException) {
                    ui { showHome(explain(e, knurd.url), bad = true) }
                    return@execute
                }
                ui {
                    if (reply.code != 200) {
                        showHome("knurd said no: " + reply.json.optString("detail", "status ${reply.code}"), bad = true)
                        return@ui
                    }
                    showHome(null)
                    val you = reply.json.optString("you")
                    val names = KnurdViews.tokenNames(reply.json)
                    val labels = names.map { if (it == you) "$it (this phone)" else it }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Remove which token?")
                        .setItems(labels) { _, which -> confirmRemove(knurd, names[which], names[which] == you) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun confirmRemove(knurd: KnurdClient, name: String, isThisPhone: Boolean) {
        val warning = if (isThisPhone) "This phone's own token: the app won't reach knurd again until you pair it again."
        else "Whatever uses it (a phone or a knurctl creds file) stops working right away."
        AlertDialog.Builder(this)
            .setTitle("Remove token \"$name\"?")
            .setMessage(warning)
            .setPositiveButton("Remove") { _, _ ->
                showHome("Removing $name...")
                worker.execute {
                    var removed = false
                    val text = try {
                        val reply = knurd.delete("/v1/tokens/$name")
                        removed = reply.code == 200
                        if (removed) "Removed token \"$name\". knurd refuses it from now on."
                        else "knurd said no: " + reply.json.optString("detail", "status ${reply.code}")
                    } catch (e: IOException) {
                        explain(e, knurd.url)
                    }
                    ui {
                        if (isThisPhone && removed) {
                            vault.forget()
                        }
                        showHome(text, bad = !removed)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- tag sessions

    private fun start() {
        val adapter = nfc ?: return
        if (running != null) return
        if (!adapter.isEnabled) {
            showHome("NFC is off. Turn it on in Settings, then tap Start again.", bad = true)
            return
        }
        val a = action
        if (a == Action.CLAIM) {
            forgetPackCard()
            askForClaim { card -> begin(a, card) }
        } else {
            begin(a, null)
        }
    }

    private fun begin(a: Action, card: ClaimCodes.Claim?) {
        unlock("Unlock to use knurd") {
            val knurd = knurd() ?: return@unlock
            claim = card
            from = when (a) {
                Action.PROVISION, Action.RESET -> if (knuraKeys.isChecked) "knura" else "factory"
                Action.CLAIM -> "claim"
                else -> null
            }
            // Last: a tag arriving on the worker thread starts relaying once running is set.
            client = knurd
            running = a
            log.text = ""
            showWorking(a)
            // NFC may have been turned on since the app came to the front.
            listenForTags()
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
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.claim_title)
            .setView(box)
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
     * before Start or while the unlock prompt is up. Tags only do something during a session.
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
                if (screenHome.visibility == View.VISIBLE) {
                    say(R.id.homeNotice, "Pick what to do and tap Start, then hold the tag.", bad = false)
                }
            }
        }
    }

    /** Ends the session; tags are ignored again until the next Start. The caller picks the next screen. */
    private fun stop() {
        pulse?.cancel()
        pulse = null
        findViewById<View>(R.id.target).apply { scaleX = 1f; scaleY = 1f }
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
            findViewById<TextView>(R.id.workTitle).text = "Keep holding it"
            hint(getString(R.string.hold_hint), bad = false)
            findViewById<TextView>(R.id.step2Text).setText(R.string.step_found)
            step(2, Step.DONE)
            step(3, Step.ACTIVE)
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
                            if (a == Action.CLAIM && card != null && card.pack) {
                                // A pack card: keep it, so the next tag of the pack needs no new scan.
                                packCard = card
                                packClaimed++
                                showResult(packLabel(), result, lines.toString(), getString(R.string.claim_next))
                            } else {
                                showResult(a.label, result, lines.toString(), a.again)
                            }
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
}
