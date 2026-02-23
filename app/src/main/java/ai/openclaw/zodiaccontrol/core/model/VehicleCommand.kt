package ai.openclaw.zodiaccontrol.core.model

sealed interface VehicleCommand {
    data class SetHeading(val headingDeg: Int) : VehicleCommand

    data class SetSpeed(val speedKph: Int) : VehicleCommand

    object EmergencyStop : VehicleCommand
}
