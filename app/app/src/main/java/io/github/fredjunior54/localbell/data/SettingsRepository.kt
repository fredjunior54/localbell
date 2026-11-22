package io.github.fredjunior54.localbell.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val KNOWN_APPS = stringSetPreferencesKey("known_apps")
        private val BLACKLISTED_APPS = stringSetPreferencesKey("blacklisted_apps")
    }

    val knownApps: Flow<Set<String>> =
        context.dataStore.data.map { prefs -> prefs[KNOWN_APPS] ?: emptySet() }

    val blacklistedApps: Flow<Set<String>> =
        context.dataStore.data.map { prefs -> prefs[BLACKLISTED_APPS] ?: emptySet() }

    suspend fun addKnownApp(packageName: String) {
        val current = context.dataStore.data.first()[KNOWN_APPS] ?: emptySet()
        if (packageName in current) return
        context.dataStore.edit { prefs ->
            val stored = prefs[KNOWN_APPS] ?: emptySet()
            if (packageName !in stored) {
                prefs[KNOWN_APPS] = stored + packageName
            }
        }
    }

    suspend fun removeKnownApp(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KNOWN_APPS] ?: return@edit
            prefs[KNOWN_APPS] = current - packageName
            val blacklisted = prefs[BLACKLISTED_APPS] ?: return@edit
            prefs[BLACKLISTED_APPS] = blacklisted - packageName
        }
    }

    suspend fun setAppBlacklisted(packageName: String, blacklisted: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[BLACKLISTED_APPS] ?: emptySet()
            prefs[BLACKLISTED_APPS] =
                if (blacklisted) current + packageName else current - packageName
        }
    }
}
