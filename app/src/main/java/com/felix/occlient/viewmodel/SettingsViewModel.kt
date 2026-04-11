package com.felix.occlient.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.felix.occlient.data.preferences.SettingsDataStore
import com.felix.occlient.data.preferences.SshSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val dataStore: SettingsDataStore) : ViewModel() {
    val settings: StateFlow<SshSettings> = dataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SshSettings())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun saveSettings(
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKey: String,
        protocolDebugLogs: Boolean
    ) {
        viewModelScope.launch {
            dataStore.saveSettings(
                SshSettings(
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    privateKey = privateKey,
                    protocolDebugLogs = protocolDebugLogs
                )
            )
            _saved.value = true
        }
    }

    fun clearSaved() { _saved.value = false }
}
