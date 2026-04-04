package com.felix.occlient.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.felix.occlient.data.model.Session
import com.felix.occlient.data.repository.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: SessionRepository) : ViewModel() {
    val sessions: StateFlow<List<Session>> = repository.getAllSessions()
        .let {
            val state = MutableStateFlow<List<Session>>(emptyList())
            viewModelScope.launch { it.collect { list -> state.value = list } }
            state.asStateFlow()
        }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun createSession(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val session = repository.createSession(name)
                onCreated(session.id)
            } catch (e: Exception) {
                _error.value = "Failed to create session: ${e.message}"
            }
        }
    }

    fun deleteSession(session: Session) {
        viewModelScope.launch {
            try {
                repository.deleteSession(session)
            } catch (e: Exception) {
                _error.value = "Failed to delete session: ${e.message}"
            }
        }
    }

    fun clearError() { _error.value = null }
}
