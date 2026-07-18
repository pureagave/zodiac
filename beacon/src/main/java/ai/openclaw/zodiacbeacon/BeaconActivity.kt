package ai.openclaw.zodiacbeacon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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
                setTextColor(Color.parseColor("#C77DFF"))
                gravity = Gravity.CENTER
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

    private fun onToggle() {
        val svc = Intent(this, TelemetryService::class.java)
        if (TelemetryBroadcaster.isRunning.value) {
            stopService(svc)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val ask = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (ask.isNotEmpty()) ActivityCompat.requestPermissions(this, ask.toTypedArray(), PERM_REQUEST)
    }

    private companion object {
        const val PAD_DP = 24f
        const val TITLE_SP = 30f
        const val STATUS_SP = 20f
        const val PERM_REQUEST = 1
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
