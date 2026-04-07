package com.felix.occlient.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class SshSettings(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKey: String = ""
)

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val SSH_HOST = stringPreferencesKey("ssh_host")
        val SSH_PORT = intPreferencesKey("ssh_port")
        val SSH_USER = stringPreferencesKey("ssh_user")
        val SSH_PASSWORD = stringPreferencesKey("ssh_password")
        val SSH_PRIVATE_KEY = stringPreferencesKey("ssh_private_key")
    }

    val settingsFlow: Flow<SshSettings> = context.dataStore.data.map { prefs ->
        SshSettings(
            host = prefs[Keys.SSH_HOST] ?: "",
            port = prefs[Keys.SSH_PORT] ?: 22,
            username = prefs[Keys.SSH_USER] ?: "",
            password = prefs[Keys.SSH_PASSWORD] ?: "",
            privateKey = prefs[Keys.SSH_PRIVATE_KEY] ?: ""
        )
    }

    suspend fun saveSettings(settings: SshSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SSH_HOST] = settings.host
            prefs[Keys.SSH_PORT] = settings.port
            prefs[Keys.SSH_USER] = settings.username
            prefs[Keys.SSH_PASSWORD] = settings.password
            prefs[Keys.SSH_PRIVATE_KEY] = settings.privateKey
        }
    }
}
