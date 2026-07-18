package org.pureagave.zodiac.control.core.vision

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
            val az = f[1].toFloatOrNull() ?: return@mapNotNull null
            val size = f[2].toFloatOrNull() ?: return@mapNotNull null
            DriverThreat(relAzDeg = az, size = size, collision = f[3].trim() == "1", id = id)
        }
    }
}
