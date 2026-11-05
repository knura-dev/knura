package io.knura.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter

/**
 * Asks Android which app it would open for a tag holding [link] while Knura is closed. When a
 * browser also claims tag links, or was once picked "Always", the tap skips the app and goes
 * unsigned. The answer is shown after an in-app tap, so the owner can see and fix it.
 */
object NfcHandoff {
    /** [knuraMatches]: our filter matches the link. [others]: other apps that do. [winner]: the one Android picks, or null when it would ask. */
    data class Report(val knura: String, val knuraMatches: Boolean, val others: List<Pair<String, String>>, val winner: String?, val winnerLabel: String?) {
        /** True when a tap with the app closed would reach Knura. */
        val reachesKnura: Boolean get() = knuraMatches && (winner == null && others.isEmpty() || winner == knura)

        fun describe(): String = buildString {
            append("When Knura is closed, Android sends this tag to: ")
            append(
                when {
                    !knuraMatches && others.isEmpty() -> "the browser (no app claims it)"
                    winner == knura || (winner == null && others.isEmpty()) -> "Knura"
                    winner == null -> "a chooser between Knura and ${others.joinToString { it.second }}"
                    else -> winnerLabel ?: winner
                }
            )
            append('\n')
            append("Knura's tag filter matches this link: ").append(if (knuraMatches) "yes" else "no").append('\n')
            if (others.isNotEmpty()) append("Other apps that claim tag links: ").append(others.joinToString { "${it.second} (${it.first})" }).append('\n')
        }
    }

    fun check(context: Context, link: String): Report {
        val knuraPkg = context.packageName
        val pm = context.packageManager
        val intent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED).setData(Uri.parse(link)).addCategory(Intent.CATEGORY_DEFAULT)
        val matches = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val knura = matches.any { it.activityInfo.packageName == knuraPkg }
        val others = matches.filter { it.activityInfo.packageName != knuraPkg }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
        val best = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        // With no preferred app, Android answers with its own chooser (package "android").
        val winner = best?.packageName?.takeIf { it != "android" }
        val label = winner?.let { pkg -> others.firstOrNull { it.first == pkg }?.second ?: if (pkg == knuraPkg) "Knura" else pkg }
        return Report(knuraPkg, knura, others, winner, label)
    }

    /** Opens the system page where the app's "Open by default" choices can be cleared. */
    fun openAppSettings(context: Context, pkg: String) {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
