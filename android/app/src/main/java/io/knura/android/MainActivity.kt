package io.knura.android

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.IOException
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * One screen: pair with knurd, pick what to do, unlock, hold a tag to the phone. The phone only
 * relays: knurd sends each command for the tag and checks every reply, and the tag's keys never
 * reach the phone.
 */
class MainActivity : Activity() {
    /** What the app can do, in the order of the action list, with knurd's name for each. */
    private enum class Action(val api: String) { PROVISION("provision"), INFO("info"), TAP("tap"), RESET("reset") }

    private lateinit var vault: TokenVault
    private lateinit var paired: TextView
    private lateinit var pairButton: Button
    private lateinit var forgetButton: Button
    private lateinit var actionSpinner: Spinner
    private lateinit var knuraKeys: CheckBox
    private lateinit var startButton: Button
    private lateinit var status: TextView
    private lateinit var log: TextView

    private val worker = Executors.newSingleThreadExecutor()
    private var nfc: NfcAdapter? = null
    /** Set while waiting for a tag or relaying to one. */
    private var client: KnurdClient? = null
    private var running: Action? = null
    /** The keys the tag has now, as knurd names them, or null when the action doesn't need it. */
    private var from: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        vault = TokenVault(this)
        nfc = NfcAdapter.getDefaultAdapter(this)
        paired = findViewById(R.id.paired)
        pairButton = findViewById(R.id.pair)
        forgetButton = findViewById(R.id.forget)
        actionSpinner = findViewById(R.id.action)
        knuraKeys = findViewById(R.id.knuraKeys)
        startButton = findViewById(R.id.start)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        pairButton.setOnClickListener { scanPairingCode() }
        forgetButton.setOnClickListener {
            vault.forget()
            showPairing()
        }
        actionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // A new tag has factory keys; a tag being reset was most likely set up by Knura.
                val action = Action.entries[position]
                knuraKeys.visibility = if (action == Action.PROVISION || action == Action.RESET) View.VISIBLE else View.GONE
                knuraKeys.isChecked = action == Action.RESET
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        startButton.setOnClickListener { if (running == null) start() else stop("Cancelled.") }
        showPairing()
    }

    override fun onPause() {
        super.onPause()
        if (running != null) {
            stop("Stopped: the app went to the background.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun showPairing() {
        val isPaired = vault.isPaired()
        paired.text = when {
            nfc == null -> "This phone has no NFC."
            isPaired -> "Paired with knurd at ${vault.url}"
            else -> "Not paired yet. On knurd's computer run: knurctl admin pair-phone <name>, then tap Pair and scan the code."
        }
        pairButton.visibility = if (isPaired) View.GONE else View.VISIBLE
        forgetButton.visibility = if (isPaired) View.VISIBLE else View.GONE
        startButton.isEnabled = isPaired && nfc != null
    }

    private fun scanPairingCode() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val pairing = try {
                    Pairing.parse(barcode.rawValue ?: "")
                } catch (e: IllegalArgumentException) {
                    status.text = e.message
                    return@addOnSuccessListener
                }
                unlock("Unlock to save the pairing") {
                    try {
                        vault.save(pairing)
                        status.text = "Paired. Pick what to do, then tap Start."
                    } catch (e: Exception) {
                        status.text = "Couldn't save the pairing: ${e.message}. Does this phone have a screen lock?"
                    }
                    showPairing()
                }
            }
            .addOnFailureListener { e -> status.text = "Couldn't scan: ${e.message}" }
    }

    /** Asks for fingerprint or screen lock, then runs [then] on the main thread. */
    private fun unlock(title: String, then: () -> Unit) {
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(title)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                then()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                status.text = "Not unlocked: $errString"
            }
        })
    }

    private fun start() {
        val adapter = nfc ?: return
        if (!adapter.isEnabled) {
            status.text = "NFC is off. Turn it on in Settings, then tap Start again."
            return
        }
        val action = Action.entries[actionSpinner.selectedItemPosition]
        unlock("Unlock to use knurd") {
            val pairing = try {
                vault.load()
            } catch (e: Exception) {
                status.text = "Couldn't read the pairing (${e.message}). Forget this knurd and pair again."
                return@unlock
            }
            client = KnurdClient(pairing)
            running = action
            from = if (action == Action.PROVISION || action == Action.RESET) {
                if (knuraKeys.isChecked) "knura" else "factory"
            } else null
            log.text = ""
            status.text = "Hold a tag to the back of the phone and keep it there."
            startButton.text = getString(R.string.cancel)
            actionSpinner.isEnabled = false
            knuraKeys.isEnabled = false
            // Reader mode: the app gets the tag directly, and Android doesn't open its link.
            adapter.enableReaderMode(this, { tag -> worker.execute { relay(tag) } },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        }
    }

    private fun stop(message: String?) {
        nfc?.disableReaderMode(this)
        client = null
        running = null
        startButton.text = getString(R.string.start)
        actionSpinner.isEnabled = true
        knuraKeys.isEnabled = true
        if (message != null) {
            status.text = message
        }
    }

    /** Runs one session with knurd for this tag. On the worker thread. */
    private fun relay(tag: Tag) {
        val knurd = client ?: return
        val action = running ?: return
        val keys = from
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            ui { status.text = "This tag isn't an NTAG 424 DNA (it has no ISO-DEP). Try another tag." }
            return
        }
        ui { status.text = "Talking to the tag. Keep holding it..." }
        try {
            isoDep.connect()
            isoDep.timeout = 5000
            val start = JSONObject().put("action", action.api).put("uid", tag.id.toHex())
            if (keys != null) {
                start.put("from", keys)
            }
            var reply = knurd.post("/v1/nfc/sessions", start)
            while (true) {
                showLog(reply.json.optJSONArray("log"))
                if (reply.code != 200) {
                    finish(reply.json.optString("detail", "knurd answered ${reply.code}"))
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
                        finish(describe(action, reply.json.getJSONObject("result")))
                        return
                    }
                    else -> {
                        finish("Didn't work: " + reply.json.optString("detail", "unknown error"))
                        return
                    }
                }
            }
        } catch (e: IOException) {
            finish(explain(e, knurd.url))
        } finally {
            try {
                isoDep.close()
            } catch (e: IOException) {
                // Already gone.
            }
        }
    }

    private fun finish(message: String) = ui { stop(message) }

    private fun showLog(lines: JSONArray?) {
        if (lines == null || lines.length() == 0) return
        val text = StringBuilder()
        for (i in 0 until lines.length()) {
            text.append(lines.getString(i)).append('\n')
        }
        ui { log.append(text) }
    }

    private fun describe(action: Action, r: JSONObject): String {
        val uid = r.optString("uid")
        val genuine = if (r.has("genuine")) {
            if (r.getBoolean("genuine")) "Genuine, tap counter ${r.optInt("counter")}." else "Does NOT verify: ${r.optString("why")}"
        } else ""
        return when (action) {
            Action.PROVISION -> "Tag $uid is set up.\nIt now opens: ${r.optString("tapUrl")}"
            Action.RESET -> "Tag $uid is back to factory state: all keys zero, no link."
            Action.TAP -> "Tag $uid. $genuine\n${r.optString("url")}"
            Action.INFO -> {
                val keys = when (r.optString("keys")) {
                    "factory" -> "factory keys (ready to set up)"
                    "knura" -> "Knura keys from your knurd"
                    else -> "keys from other software or another master key"
                }
                val url = if (r.isNull("url")) "no link" else r.optString("url")
                "Tag $uid has $keys.\nLink: $url\n$genuine".trim()
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
