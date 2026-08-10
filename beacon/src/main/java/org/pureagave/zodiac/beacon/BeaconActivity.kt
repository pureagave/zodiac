package org.pureagave.zodiac.beacon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Single-screen control for the Zodiac Beacon: a big status readout and a
 * START/STOP toggle that starts/stops the foreground [TelemetryService].
 * Deliberately plain Views — this is a utility, not a cockpit.
 */
class BeaconActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        requestBatteryOptimizationExemptionOnce()
        setContentView(buildUi())

        lifecycleScope.launch {
            TelemetryBroadcaster.isRunning
                .combine(TelemetryBroadcaster.status) { running, status -> running to status }
                .collect { (running, status) ->
                    toggle.text = if (running) "STOP" else "START"
                    statusView.text = status
                }
        }
    }

    // SetTextI18n: the beacon ships no string resources and is never localised —
    // it's a single-screen provisioning UI for one operator on one bolted-in
    // phone, sideloaded, never on Play. Extracting three literals to strings.xml
    // would add a resource pipeline for zero reachable benefit. Scoped to this
    // function so any *other* untranslated UI still gets reported.
    @Suppress("SetTextI18n")
    private fun buildUi(): LinearLayout {
        val pad = (resources.displayMetrics.density * PAD_DP).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            }
        val title =
            TextView(this).apply {
                text = "ZODIAC BEACON"
                textSize = TITLE_SP
                setTextColor(Color.parseColor("#00FF66"))
                gravity = Gravity.CENTER
            }
        statusView =
            TextView(this).apply {
                text = "Idle"
                textSize = STATUS_SP
                typeface = Typeface.MONOSPACE // monospace so the per-channel columns line up
                setTextColor(Color.parseColor("#C77DFF"))
                gravity = Gravity.START
                setPadding(0, pad, 0, pad)
            }
        toggle =
            Button(this).apply {
                text = "START"
                setOnClickListener { onToggle() }
            }
        root.addView(title)
        root.addView(statusView)
        root.addView(toggle)
        return root
    }

    /**
     * Persists the operator's choice on **both** branches — this is the flag
     * [BootReceiver] reads after a reboot, so boot always restores whatever was
     * last explicitly chosen. A `false` here means the STOP button stays a real
     * stop even across a power cycle, not just an auto-restart cancel.
     */
    private fun onToggle() {
        val svc = Intent(this, TelemetryService::class.java)
        val prefs = getSharedPreferences(TelemetryBroadcaster.PREFS_NAME, Context.MODE_PRIVATE)
        if (TelemetryBroadcaster.isRunning.value) {
            prefs.edit().putBoolean(BootReceiver.PREF_AUTO_START, false).apply()
            stopService(svc)
        } else {
            prefs.edit().putBoolean(BootReceiver.PREF_AUTO_START, true).apply()
            // minSdk is 29, so the pre-O plain startService() branch was dead code
            // (Lint ObsoleteSdkInt). Routed through ContextCompat so the manual
            // start and BootReceiver's boot start take the identical path.
            ContextCompat.startForegroundService(this, svc)
        }
    }

    private fun requestNeededPermissions() {
        // RECORD_AUDIO is optional — deny it and everything else still broadcasts;
        // only the $ZAUD sound level goes silent (see TelemetryBroadcaster).
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        // A boot start is a background start, which needs ACCESS_BACKGROUND_LOCATION
        // for GPS (see BootReceiver/B3). API 29 (this app's floor) can request it
        // bundled with fine location; API 30+ silently drops it if bundled and
        // requires a separate follow-up request once fine location is granted —
        // handled in onRequestPermissionsResult.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            needed += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        val ask = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (ask.isNotEmpty()) ActivityCompat.requestPermissions(this, ask.toTypedArray(), PERM_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERM_REQUEST || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val fineGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val backgroundGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (fineGranted && !backgroundGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                PERM_REQUEST_BACKGROUND,
            )
        }
    }

    /**
     * A wake lock alone isn't enough: in deep Doze (stationary, screen off,
     * **unplugged** — exactly the flaky-charger scenario) the platform ignores
     * app wake locks outright (AUDIT-2026-08-09 B4). Prompt for the battery
     * optimization exemption once at provisioning, not on every launch — the
     * flag survives a decline too, so a "no" isn't nagged at forever.
     *
     * BatteryLife: Lint's objection is a *Play Store content policy* one — the
     * exemption is only acceptable for a narrow set of use cases. This app is
     * never published; it is sideloaded onto one dedicated vehicle sensor phone
     * that has no other job. WorkManager, Lint's suggested alternative, cannot
     * do this: the beacon is a continuous 250 ms tick loop feeding 8-10 tablets,
     * not deferrable work. Without the exemption, deep Doze silently stalls the
     * whole fleet's GPS. Scoped to this one function.
     */
    @Suppress("BatteryLife")
    private fun requestBatteryOptimizationExemptionOnce() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences(TelemetryBroadcaster.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BATTERY_OPT_PROMPTED, false)) return
        prefs.edit().putBoolean(PREF_BATTERY_OPT_PROMPTED, true).apply()
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private companion object {
        const val PAD_DP = 24f
        const val TITLE_SP = 30f
        const val STATUS_SP = 14f // smaller: the readout is now a 7-line per-channel dump
        const val PERM_REQUEST = 1
        const val PERM_REQUEST_BACKGROUND = 2
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val PREF_BATTERY_OPT_PROMPTED = "battery_opt_prompted"
    }
}
