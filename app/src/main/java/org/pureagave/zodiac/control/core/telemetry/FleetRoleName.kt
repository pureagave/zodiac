package org.pureagave.zodiac.control.core.telemetry

import java.util.Locale

/**
 * Maps a node's wire [FleetVersion.name] (its `Build.MODEL` on a tablet/beacon, or
 * the Jetson's hostname) to the friendly fleet role an operator recognises — so
 * the roster reads **HERO / DRIVER / BEACON** at a glance instead of `SM-X810` /
 * `SM-A546V`, and it's obvious which device is stale.
 *
 * A **display concern only**: the wire still carries `Build.MODEL` — the stable,
 * unambiguous key that the `$ZVER` golden corpus pins — so nothing here touches
 * the protocol or needs a reflash. This object is simply where the fleet's known
 * hardware is enumerated. An unrecognised device falls back to its raw name, so a
 * new tablet added to the fleet still shows something meaningful (its model)
 * rather than a blank or a wrong guess.
 *
 * The two Fires share the passenger role but are different generations
 * (`KFTUWI` = Fire HD 10 11th gen, `KFMAWI` = 9th), so they keep the generation
 * suffix — otherwise two identical "PASSENGER" rows would defeat the whole point.
 */
object FleetRoleName {
    fun of(name: String): String =
        when (name.uppercase(Locale.US)) {
            "SM-X810" -> "HERO"
            "SM-A546V" -> "DRIVER"
            "SM-G715U" -> "BEACON"
            "KFTUWI" -> "PASSENGER 11"
            "KFMAWI" -> "PASSENGER 9"
            "ZVISION" -> "JETSON"
            else -> name
        }
}
