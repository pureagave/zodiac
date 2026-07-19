package org.pureagave.zodiac.control.core.vision

import kotlin.math.abs

/**
 * Compact wire format for the thermal-threat channel. One UDP datagram = one
 * frame: the literal `ZTHREAT` header, then a `;`-separated contact per entry
 * `id:relAzDeg:size:collision` (collision 0/1). e.g.
 *
 *   `ZTHREAT;1:-12.0:0.30:0;2:4.5:0.90:1`
 *
 * Pure and self-describing so it's trivially testable: the edge box (Jetson)
 * emits these, the tablets parse them. A frame with no contacts is just
 * `ZTHREAT` (→ empty list), which is how "all clear" is signalled.
 */
object ThreatProtocol {
    const val HEADER = "ZTHREAT"
    private const val FRAME_SEP = ';'
    private const val FIELD_SEP = ':'
    private const val FIELDS_PER_CONTACT = 4

    // Reject contacts past the forward arc; clamp size to its 0..1 range; cap the
    // count so one hostile/buggy frame can't flood the HUD. Mirrors zvision's
    // threat_protocol.py — this is the untrusted network boundary.
    private const val MAX_ABS_AZ_DEG = 90f
    private const val MAX_CONTACTS = 32

    fun format(threats: List<DriverThreat>): String =
        buildString {
            append(HEADER)
            threats.forEach { t ->
                append(FRAME_SEP)
                append(t.id).append(FIELD_SEP)
                append(t.relAzDeg).append(FIELD_SEP)
                append(t.size).append(FIELD_SEP)
                append(if (t.collision) 1 else 0)
            }
        }

    /** Parse a frame into contacts, or null if the line isn't a ZTHREAT frame. */
    fun parse(line: String): List<DriverThreat>? {
        val parts = line.trim().split(FRAME_SEP)
        if (parts.firstOrNull() != HEADER) return null
        return parts.drop(1).mapNotNull { entry ->
            val f = entry.split(FIELD_SEP)
            if (f.size < FIELDS_PER_CONTACT) return@mapNotNull null
            val id = f[0].toIntOrNull() ?: return@mapNotNull null
            // NaN/Infinity parse fine as floats but poison the HUD's Canvas
            // math — reject non-finite and out-of-arc az, clamp size to 0..1.
            val az = f[1].toFloatOrNull()?.takeIf { it.isFinite() && abs(it) <= MAX_ABS_AZ_DEG } ?: return@mapNotNull null
            val size = f[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            DriverThreat(relAzDeg = az, size = size.coerceIn(0f, 1f), collision = f[3].trim() == "1", id = id)
        }.take(MAX_CONTACTS)
    }
}
