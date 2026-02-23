package ai.openclaw.zodiaccontrol.core.connection

enum class TransportType {
    BLE,
    USB,
    WIFI,
}

enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class ConnectionState(
    val transport: TransportType,
    val phase: ConnectionPhase,
    val detail: String? = null,
)
