package ai.openclaw.zodiaccontrol.core.model

/**
 * Three-state load result for the bundled BRC map. Lets the UI show a
 * placeholder while loading, swap in the parsed [PlayaMap] when ready, and
 * surface a human-readable error if the assets are missing/corrupt instead
 * of silently displaying a blank map.
 */
sealed interface MapLoadResult {
    data object Loading : MapLoadResult

    data class Loaded(
        val map: PlayaMap,
    ) : MapLoadResult

    data class Failed(
        val message: String,
    ) : MapLoadResult
}
