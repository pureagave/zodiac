package org.pureagave.zodiac.control.core.sensor

/**
 * Why a [org.pureagave.zodiac.control.data.sensor.LocationSource] failed,
 * categorised by **what the operator would do about it**. That's the whole
 * point of the split: out on the playa "GPS: Error" costs you an hour of
 * guessing, while "NO DEVICE" versus "PERMISSION" versus "ADAPTER OFF" each
 * point at one specific action.
 *
 * The free-text detail stays on [LocationSourceState.Error] and goes to the
 * rolling log; this is the part that reaches the screen.
 */
enum class LocationSourceError {
    /** The runtime permission isn't held — grant it in Settings. */
    PERMISSION_DENIED,

    /** The radio/bus exists but is switched off — turn Bluetooth back on. */
    ADAPTER_UNAVAILABLE,

    /** Nothing to talk to: no dongle plugged in, no beacon on the air. */
    NO_DEVICE_FOUND,

    /** We reached it and the conversation broke — cable, socket, or bind. */
    IO_ERROR,

    /** Uncategorised. Read the detail in the log. */
    UNKNOWN,
}

/** A control-strip status line: the text to draw, and whether it reads as a fault. */
data class GpsStatusLabel(val text: String, val fault: Boolean)

/**
 * The one-line GPS status for the control strip. Kept pure and separate from
 * the composable so the wording is pinned by tests — this is a line someone
 * reads in the dark, in dust, while the car is moving, and it needs to say the
 * same thing every time.
 */
fun gpsStatusLabel(state: LocationSourceState): GpsStatusLabel =
    when (state) {
        is LocationSourceState.Disconnected -> GpsStatusLabel("OFF", fault = false)
        is LocationSourceState.Searching -> GpsStatusLabel("SEARCHING", fault = false)
        is LocationSourceState.Active -> GpsStatusLabel("FIX", fault = false)
        is LocationSourceState.Error ->
            GpsStatusLabel(
                text =
                    when (state.kind) {
                        LocationSourceError.PERMISSION_DENIED -> "⊘ PERMISSION"
                        LocationSourceError.ADAPTER_UNAVAILABLE -> "⊘ ADAPTER OFF"
                        LocationSourceError.NO_DEVICE_FOUND -> "? NO DEVICE"
                        LocationSourceError.IO_ERROR -> "✕ I/O"
                        LocationSourceError.UNKNOWN -> "✕ ERROR"
                    },
                fault = true,
            )
    }
