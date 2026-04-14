package com.felix.occlient.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.felix.occlient.data.model.SessionType
import com.felix.occlient.data.preferences.SettingsDataStore
import com.felix.occlient.data.repository.SessionRepository
import com.felix.occlient.network.SshConfig
import com.felix.occlient.network.SshConnectionState
import com.felix.occlient.network.SshManagerHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    /** True while opencode is expected to be computing a response. */
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /** Session type resolved from the repository. Defaults to RUN until the session is loaded. */
    private var sessionType: SessionType = SessionType.RUN

    companion object {
        /** Delay before sending the opencode command, allowing the SSH shell to fully initialise. */
        private const val OPENCODE_STARTUP_DELAY_MS = 1000L

        /**
         * Debounce window for raw SSH output.  opencode is a full-screen TUI that redraws
         * the entire terminal on every update (streaming tokens, cursor blinks, …).  Rather than
         * adding a new ASSISTANT message for each redraw we wait until no new bytes have arrived
         * for this many milliseconds and then process the latest snapshot.
         */
        private const val OUTPUT_DEBOUNCE_MS = 500L
    }

    /**
     * Thread-safe map tracking commands sent to the SSH stream whose terminal echoes should be
     * suppressed.  Values are counts to correctly handle the same command sent multiple times
     * in quick succession.  O(1) exact-match lookup; only falls back to O(n) iteration when
     * checking for shell-prompt-prefixed echo lines.
     */
    private val pendingEchoCommands = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private var isFirstConnect = true

    /**
     * Set to true just before sending the opencode command so that the first batch of raw SSH
     * output received afterwards triggers a SYSTEM acknowledgement message.  Cleared atomically
     * on first use.
     */
    private val awaitingOpenCodeAck = AtomicBoolean(false)

    /**
     * Accumulates raw SSH text received between debounce windows.  Written from the IO thread
     * via atomic append; read and cleared from the main thread after the debounce delay.
     */
    private val pendingOutput = AtomicReference<String?>(null)

    /** Debounce job for [processOutput]. Cancelled and restarted on each incoming batch. */
    private var outputDebounceJob: Job? = null
    private var protocolDebugLogsEnabled: Boolean = false

    /**
     * ID of the ASSISTANT message for the current conversation turn.  Rather than appending a
     * new bubble for every TUI screen redraw we UPDATE this message in-place.  Reset to null
     * when the user sends the next prompt so the following response gets a fresh bubble.
     */
    private var currentAssistantMsgId: String? = null

    init {
        viewModelScope.launch {
            sessionRepository.getSessionById(sessionId)?.let {
                _sessionName.value = it.name
                sessionType = it.sessionType
            }
        }
        // Clear the processing indicator if the connection is lost so the spinner never gets stuck.
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state != SshConnectionState.CONNECTED) {
                    _isProcessing.value = false
                    currentAssistantMsgId = null
                }
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
                protocolDebugLogsEnabled = settings.protocolDebugLogs
                addMessage("Connected! Starting ${opencodeCommand}...", MessageType.SYSTEM)
                if (protocolDebugLogsEnabled) {
                    sshManager.appendLog("[PROTO] debug trace enabled, session type=${sessionType.name}")
                }
                if (isFirstConnect) {
                    isFirstConnect = false
                    kotlinx.coroutines.delay(OPENCODE_STARTUP_DELAY_MS)
                    launchOpencode()
                }
            } else {
                addMessage("Connection failed: ${result.exceptionOrNull()?.message}", MessageType.ERROR)
            }
        }
    }

    /** SSH command to launch opencode based on the session type. */
    private val opencodeCommand: String
        get() = when (sessionType) {
            SessionType.RUN -> "opencode run"
            SessionType.SERVE -> "opencode serve"
        }

    /**
     * Sends the appropriate opencode launch command based on the session type.
     *
     * RUN  – `opencode run` starts an interactive TUI session.
     * SERVE – `opencode serve` starts (or attaches to) the HTTP API server; output is shown
     *         in the log and assistant bubbles so the user can see server events.
     */
    private suspend fun launchOpencode() {
        val command = opencodeCommand
        pendingEchoCommands.merge(command, 1, Int::plus)
        awaitingOpenCodeAck.set(true)
        sshManager.appendLog("[SESSION] launching '${command}' (type=${sessionType.name})")
        sshManager.sendCommand(command)
        addMessage("$command launched. Waiting for response…", MessageType.SYSTEM)
    }

    fun disconnect() {
        sshManager.disconnect()
        addMessage("Disconnected", MessageType.SYSTEM)
    }

    fun sendPrompt(text: String) {
        viewModelScope.launch {
            // Each new user turn gets a fresh ASSISTANT bubble.
            currentAssistantMsgId = null
            addMessage(text, MessageType.USER)
            val command = opencodeCommand
            // Track the prompt so its terminal echo is suppressed in processOutput.
            pendingEchoCommands.merge(command, 1, Int::plus)
            val result = sshManager.sendCommand("$command \"$text\"")
            if (result.isFailure) {
                addMessage("Failed to send: ${result.exceptionOrNull()?.message}", MessageType.ERROR)
            } else {
                _isProcessing.value = true
                sessionRepository.incrementMessageCount(sessionId)
            }
        }
    }

    /**
     * Receives raw SSH output from the IO thread.
     *
     * opencode streams its AI response as many small incremental chunks (cursor moves,
     * colour resets, partial text writes, …) rather than a single full-screen repaint.
     * Processing each chunk individually would flood the chat with dozens of ASSISTANT bubbles.
     * Instead we:
     *
     * 1. Fire the one-time "opencode is active" ack immediately on the very first batch.
     * 2. Atomically APPEND each chunk to [pendingOutput] so no bytes are discarded between
     *    debounce windows.
     * 3. Debounce: cancel any pending flush job and schedule a new one [OUTPUT_DEBOUNCE_MS] ms
     *    in the future.
     * 4. The flush extracts the latest screen render, strips ANSI, filters echoes and shell
     *    prompts, then UPDATES (or creates) a single ASSISTANT bubble for this conversation
     *    turn instead of appending new ones.
     */
    private fun processOutput(text: String) {
        val isFirstAck = awaitingOpenCodeAck.getAndSet(false)

        // Accumulate all chunks so the debounce handler always sees the full response text.
        pendingOutput.updateAndGet { prev -> (prev ?: "") + text }

        viewModelScope.launch {
            if (isFirstAck) {
                addMessage("opencode is active and sending output.", MessageType.SYSTEM)
            }

            outputDebounceJob?.cancel()
            outputDebounceJob = launch {
                delay(OUTPUT_DEBOUNCE_MS)
                val raw = pendingOutput.getAndSet(null) ?: return@launch
                flushOutput(raw)
            }
        }
    }

    /**
     * Processes a raw SSH snapshot after the debounce window has elapsed:
     * 1. Extracts the latest full-screen render to avoid processing intermediate streaming states.
     * 2. Strips ANSI/VT100 escape sequences and carriage returns.
     * 3. Filters terminal echoes of commands we sent.
     * 4. Detects shell prompt lines – clears the spinner if opencode exited back to the shell.
     * 5. Filters lines that are solely a shell prompt.
     * 6. Updates (or creates) the current ASSISTANT message with the remaining content.
     *
     * Must be called on the main thread (via viewModelScope).
     */
    private fun flushOutput(text: String) {
        val parseResult = OpencodeProtocol.parseAssistantOutput(text, pendingEchoCommands)
        if (protocolDebugLogsEnabled) {
            logProtocolTrace(text, parseResult)
        }

        if (parseResult.sawShellPrompt && _isProcessing.value) {
            _isProcessing.value = false
        }

        val result = parseResult.assistantText

        if (result.isNotBlank()) {
            _isProcessing.value = false
            val existingId = currentAssistantMsgId
            if (existingId != null) {
                // Update the existing bubble for this conversation turn in-place.
                val msgs = _messages.value.toMutableList()
                val idx = msgs.indexOfFirst { it.id == existingId }
                if (idx >= 0) {
                    msgs[idx] = msgs[idx].copy(content = result)
                    _messages.value = msgs
                } else {
                    // Message was removed externally – create a fresh bubble rather than
                    // silently discarding the update.
                    currentAssistantMsgId = null
                    val newMsg = ChatMessage(content = result, type = MessageType.ASSISTANT)
                    currentAssistantMsgId = newMsg.id
                    _messages.value = _messages.value + newMsg
                }
            } else {
                val newMsg = ChatMessage(content = result, type = MessageType.ASSISTANT)
                currentAssistantMsgId = newMsg.id
                _messages.value = _messages.value + newMsg
            }
        }
    }

    private fun logProtocolTrace(raw: String, parseResult: ProtocolParseResult) {
        fun short(s: String): String {
            val singleLine = s.replace("\n", "\\n")
            return if (singleLine.length <= 120) singleLine else singleLine.take(120) + "..."
        }

        val assistantPreview = if (parseResult.assistantText.isBlank()) "<empty>" else short(parseResult.assistantText)
        val echoPreview = if (parseResult.droppedEchoLines.isEmpty()) "[]"
        else parseResult.droppedEchoLines.take(3).joinToString(prefix = "[", postfix = "]") { "\"${short(it)}\"" }
        val promptPreview = if (parseResult.droppedPromptLines.isEmpty()) "[]"
        else parseResult.droppedPromptLines.take(3).joinToString(prefix = "[", postfix = "]") { "\"${short(it)}\"" }

        sshManager.appendLog(
            "[PROTO] rawLen=${raw.length} extractedLen=${parseResult.extractedScreenLength} " +
                "lines=${parseResult.consideredLineCount} echoDropped=${parseResult.droppedEchoLines.size} " +
                "promptDropped=${parseResult.droppedPromptLines.size} sawPrompt=${parseResult.sawShellPrompt} " +
                "assistant='${assistantPreview}'"
        )

        if (parseResult.droppedEchoLines.isNotEmpty()) {
            sshManager.appendLog("[PROTO] droppedEchoSample=$echoPreview")
        }
        if (parseResult.droppedPromptLines.isNotEmpty()) {
            sshManager.appendLog("[PROTO] droppedPromptSample=$promptPreview")
        }
    }

    private fun addMessage(content: String, type: MessageType) {
        _messages.value = _messages.value + ChatMessage(content = content, type = type)
    }

    /**
     * Clears the processing indicator when the user wants to recover from a frozen / stuck state.
     */
    fun cancelProcessing() {
        _isProcessing.value = false
        currentAssistantMsgId = null
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        outputDebounceJob?.cancel()
        sshManager.onOutputReceived = null
    }
}
