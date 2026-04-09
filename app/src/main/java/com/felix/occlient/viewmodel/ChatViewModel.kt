package com.felix.occlient.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.felix.occlient.data.preferences.SettingsDataStore
import com.felix.occlient.data.repository.SessionRepository
import com.felix.occlient.network.SshConfig
import com.felix.occlient.network.SshConnectionState
import com.felix.occlient.network.SshManagerHolder
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * USER      – message sent by the user (right-aligned green bubble).
 * ASSISTANT – response from opencode AI (left-aligned, distinct color).
 * SYSTEM    – connection status / informational messages (left-aligned, muted).
 * ERROR     – error messages (left-aligned, error color).
 */
enum class MessageType { USER, ASSISTANT, SYSTEM, ERROR }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val type: MessageType
)

class ChatViewModel(
    private val sessionId: String,
    private val sessionRepository: SessionRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val sshManager = SshManagerHolder.sshManager

    val connectionState: StateFlow<SshConnectionState> = sshManager.connectionState

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sessionName = MutableStateFlow("Session")
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    companion object {
        /** Delay before sending the opencode-cli command, allowing the SSH shell to fully initialise. */
        private const val OPENCODE_STARTUP_DELAY_MS = 1000L

        // Windows cmd/PowerShell: optional "PS " prefix, optional "user@host " prefix, then drive:\path>
        private val WINDOWS_SHELL_PROMPT_REGEX = Regex(
            """^(?:PS )?(?:[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+ )?[A-Za-z]:\\[^\n]*>\s*$"""
        )
        // Linux/macOS bash/zsh: user@host:/path$ or user@host:/path#
        // Note: ${'$'} is used instead of a bare $ to prevent Kotlin string interpolation.
        private val UNIX_SHELL_PROMPT_REGEX = Regex(
            """^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:[^\n]*[#${'$'}]\s*$"""
        )
    }

    /**
     * Thread-safe map tracking commands sent to the SSH stream whose terminal echoes should be
     * suppressed.  Values are counts to correctly handle the same command sent multiple times
     * in quick succession.  O(1) exact-match lookup; only falls back to O(n) iteration when
     * checking for shell-prompt-prefixed echo lines.
     */
    private val pendingEchoCommands = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private var isFirstConnect = true

    init {
        viewModelScope.launch {
            sessionRepository.getSessionById(sessionId)?.let {
                _sessionName.value = it.name
            }
        }
        sshManager.onOutputReceived = { text ->
            processOutput(text)
        }
        addMessage("Session: ${sessionName.value}\nTap connect to start.", MessageType.SYSTEM)
    }

    fun connect() {
        viewModelScope.launch {
            val settings = settingsDataStore.settingsFlow.first()
            if (settings.host.isBlank()) {
                _error.value = "SSH host not configured. Go to Settings first."
                return@launch
            }
            addMessage("Connecting to ${settings.host}:${settings.port}...", MessageType.SYSTEM)
            val config = SshConfig(
                host = settings.host,
                port = settings.port,
                username = settings.username,
                password = settings.password,
                privateKey = settings.privateKey
            )
            val result = sshManager.connect(config)
            if (result.isSuccess) {
                addMessage("Connected! Starting opencode-cli...", MessageType.SYSTEM)
                if (isFirstConnect) {
                    isFirstConnect = false
                    kotlinx.coroutines.delay(OPENCODE_STARTUP_DELAY_MS)
                    // Track the startup command so its terminal echo is suppressed.
                    pendingEchoCommands.merge("opencode-cli", 1, Int::plus)
                    sshManager.sendCommand("opencode-cli")
                }
            } else {
                addMessage("Connection failed: ${result.exceptionOrNull()?.message}", MessageType.ERROR)
            }
        }
    }

    fun disconnect() {
        sshManager.disconnect()
        addMessage("Disconnected", MessageType.SYSTEM)
    }

    fun sendPrompt(text: String) {
        viewModelScope.launch {
            addMessage(text, MessageType.USER)
            // Track the prompt so its terminal echo is suppressed in processOutput.
            pendingEchoCommands.merge(text.trim(), 1, Int::plus)
            val result = sshManager.sendCommand(text)
            if (result.isFailure) {
                addMessage("Failed to send: ${result.exceptionOrNull()?.message}", MessageType.ERROR)
            } else {
                sessionRepository.incrementMessageCount(sessionId)
            }
        }
    }

    /**
     * Processes raw SSH output:
     * 1. Strips ANSI escape sequences and carriage returns.
     * 2. Filters terminal echoes of commands we sent (the PTY echoes typed input).
     * 3. Filters lines that are solely a shell prompt (noise with no AI content).
     * 4. Classifies remaining lines as ASSISTANT responses.
     */
    private fun processOutput(text: String) {
        val withoutAnsi = text
            .replace(Regex("\u001B\\[[;\\d]*[A-Za-z]"), "")
            .replace(Regex("\u001B\\][^\u0007]*\u0007"), "")
            .replace(Regex("\r"), "")

        val filteredLines = withoutAnsi.split("\n").filter { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@filter false

            // Check whether this line is the terminal echo of a sent command.
            // The echo may appear as the plain command (O(1) exact-match lookup) or as
            // shell-prompt + command (O(n) where n = distinct pending commands, typically ≤3).
            val echoedCmd: String? = pendingEchoCommands[line]
                ?.let { line }
                ?: pendingEchoCommands.keys.asSequence().firstOrNull { cmd ->
                    matchesShellPromptEcho(line, cmd)
                }
            if (echoedCmd != null) {
                pendingEchoCommands.compute(echoedCmd) { _, count ->
                    if (count == null || count <= 1) null else count - 1
                }
                return@filter false
            }

            // Suppress lines that are purely a shell prompt (no AI content).
            if (isShellPromptLine(line)) return@filter false

            true
        }

        val result = filteredLines.joinToString("\n").trim()
        if (result.isNotBlank()) {
            viewModelScope.launch {
                addMessage(result, MessageType.ASSISTANT)
            }
        }
    }

    private fun isShellPromptLine(line: String): Boolean =
        WINDOWS_SHELL_PROMPT_REGEX.matches(line) || UNIX_SHELL_PROMPT_REGEX.matches(line)

    /**
     * Returns true when [line] is a shell-prompt-prefixed echo of [cmd]
     * (e.g. `user@host C:\path>cmd` on Windows or `user@host:~/path$ cmd` on Unix).
     */
    private fun matchesShellPromptEcho(line: String, cmd: String): Boolean =
        // Windows cmd/PowerShell: prompt ends with ">"
        line.endsWith(">$cmd") || line.endsWith("> $cmd") ||
        // Unix bash/zsh: prompt ends with "$" or "#"
        line.endsWith("\$$cmd") || line.endsWith("\$ $cmd") ||
        line.endsWith("#$cmd") || line.endsWith("# $cmd")

    private fun addMessage(content: String, type: MessageType) {
        _messages.value = _messages.value + ChatMessage(content = content, type = type)
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        sshManager.onOutputReceived = null
    }
}
