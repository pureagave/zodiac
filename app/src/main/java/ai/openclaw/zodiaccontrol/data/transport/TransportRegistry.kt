package ai.openclaw.zodiaccontrol.data.transport

import ai.openclaw.zodiaccontrol.core.connection.TransportType

class TransportRegistry(
    private val adapters: List<TransportAdapter>,
) {
    fun adapterFor(type: TransportType): TransportAdapter =
        adapters.firstOrNull { it.type == type }
            ?: error("No transport adapter registered for $type")
}
