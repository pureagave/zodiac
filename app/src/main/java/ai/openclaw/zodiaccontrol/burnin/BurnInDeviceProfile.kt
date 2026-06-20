package ai.openclaw.zodiaccontrol.burnin

import android.os.Build

/**
 * Device-class gate for the *visual* burn-in layers. Burn-in is an OLED
 * problem; the cockpit fleet's LCD tablets (Amazon Fire — no panel burn-in,
 * and the slowest GPU) skip the per-frame brightness compositing entirely.
 * Pixel-shift and backlight stepping stay universal; only the content-alpha
 * breathe/dim layer is gated here.
 */
object BurnInDeviceProfile {
    /**
     * True when the brightness modulation layer should run. Defaults to the
     * live device manufacturer; [manufacturer] is injectable for tests.
     */
    fun visualModulationSupported(manufacturer: String = Build.MANUFACTURER): Boolean = !manufacturer.equals("Amazon", ignoreCase = true)
}
