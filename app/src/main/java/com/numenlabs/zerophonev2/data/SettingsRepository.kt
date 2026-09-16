package com.numenlabs.zerophonev2.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SettingsRepository {
    val state: Flow<AppState>

    suspend fun snapshot(): AppState

    suspend fun update(transform: (AppState) -> AppState)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zerophonev2_prefs")

/**
 * Preferences-DataStore-backed repository: the whole [AppState] lives as one
 * JSON string under a single key, so related mutations (grant + suspension
 * bookkeeping) are atomic and readers always see a consistent snapshot
 * (V1 DataStorePolicyRepository pattern, collapsed to one key).
 */
class DataStoreSettingsRepository(
    private val context: Context,
) : SettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override val state: Flow<AppState> =
        context.dataStore.data.map { prefs -> decode(prefs[KEY_STATE]) }

    override suspend fun snapshot(): AppState = decode(context.dataStore.data.first()[KEY_STATE])

    override suspend fun update(transform: (AppState) -> AppState) {
        context.dataStore.edit { prefs ->
            val raw = prefs[KEY_STATE]
            // Quarantine an undecodable blob instead of silently overwriting it
            // (schema change / corruption): keep a copy before starting fresh.
            if (raw != null) {
                try {
                    json.decodeFromString<AppState>(raw)
                } catch (_: Exception) {
                    prefs[KEY_STATE_BROKEN] = raw
                }
            }
            prefs[KEY_STATE] = json.encodeToString(transform(decode(raw)))
        }
    }

    private fun decode(raw: String?): AppState =
        raw?.let {
            try {
                json.decodeFromString<AppState>(it)
            } catch (_: Exception) {
                null
            }
        } ?: AppState()

    private companion object {
        val KEY_STATE: Preferences.Key<String> = stringPreferencesKey("app_state_json")
        val KEY_STATE_BROKEN: Preferences.Key<String> = stringPreferencesKey("app_state_json_broken")
    }
}
