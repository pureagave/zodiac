package org.pureagave.zodiac.control.data.transport

import org.pureagave.zodiac.control.core.connection.TransportType

class TransportRegistry(
    private val adapters: List<TransportAdapter>,
) {
    fun adapterFor(type: TransportType): TransportAdapter =
        adapters.firstOrNull { it.type == type }
            ?: error("No transport adapter registered for $type")
}
