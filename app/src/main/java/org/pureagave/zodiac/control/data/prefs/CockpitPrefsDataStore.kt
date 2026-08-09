package org.pureagave.zodiac.control.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import java.io.File

/**
 * Factory for the cockpit prefs [DataStore], extracted out of
 * `ZodiacApplication` so it is unit-testable without an `Application`
 * instance (a real file on disk, via `TemporaryFolder`, is enough).
 *
 * A file torn by a mid-write power cut throws `CorruptionException` from
 * `dataStore.data` with no handler installed — on a kiosked tablet (no
 * Settings access to clear app data) that is a crash loop, every launch,
 * until someone reaches it with adb or a factory reset. The
 * [ReplaceFileCorruptionHandler] instead replaces the unreadable file with
 * empty preferences, so every reader (cockpit prefs, [DataStoreCockpitPreferences],
 * `DisplayRoleStore`, burn-in config, all of which share this one DataStore)
 * recovers to documented defaults instead of dying.
 */
fun cockpitPrefsDataStore(
    scope: CoroutineScope,
    produceFile: () -> File,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler =
            ReplaceFileCorruptionHandler {
                Timber.e(it, "prefs: corrupt DataStore file replaced with defaults")
                emptyPreferences()
            },
        scope = scope,
        produceFile = produceFile,
    )
