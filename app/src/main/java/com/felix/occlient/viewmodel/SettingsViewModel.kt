package com.felix.occlient.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.felix.occlient.data.preferences.SettingsDataStore
import com.felix.occlient.data.preferences.SshSettings
import com.felix.occlient.network.SshConfig
import com.felix.occlient.network.SshManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Outcome of an SSH connection test. */
sealed class ConnectionTestResult {
    object Idle : ConnectionTestResult()
    object Testing : ConnectionTestResult()
    data class Success(val message: String) : ConnectionTestResult()
    data class Failure(val message: String) : ConnectionTestResult()
}

class SettingsViewModel(private val dataStore: SettingsDataStore) : ViewModel() {
    val settings: StateFlow<SshSettings> = dataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SshSettings())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _connectionTestResult = MutableStateFlow<ConnectionTestResult>(ConnectionTestResult.Idle)
    val connectionTestResult: StateFlow<ConnectionTestResult> = _connectionTestResult.asStateFlow()

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

    /**
     * Opens an SSH connection with the given settings, immediately disconnects, and reports
     * success or failure so the user can verify their configuration without starting a session.
     */
    fun testConnection(
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKey: String
    ) {
        if (_connectionTestResult.value == ConnectionTestResult.Testing) return
        _connectionTestResult.value = ConnectionTestResult.Testing
        viewModelScope.launch {
            val manager = SshManager()
            val config = SshConfig(
                host = host.trim(),
                port = port,
                username = username.trim(),
                password = password,
                privateKey = privateKey.trim()
            )
            val result = manager.connect(config)
            manager.disconnect()
            _connectionTestResult.value = if (result.isSuccess) {
                ConnectionTestResult.Success("SSH connection to ${host.trim()}:${port} succeeded.")
            } else {
                ConnectionTestResult.Failure(
                    result.exceptionOrNull()?.message ?: "Unknown error"
                )
            }
        }
    }

    fun clearConnectionTestResult() {
        _connectionTestResult.value = ConnectionTestResult.Idle
    }
}
