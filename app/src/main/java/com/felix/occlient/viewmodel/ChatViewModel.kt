package com.felix.occlient.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    companion object {
        /** Delay before sending the opencode-cli command, allowing the SSH shell to fully initialise. */
        private const val OPENCODE_STARTUP_DELAY_MS = 1000L

        /**
         * Debounce window for raw SSH output.  opencode-cli is a full-screen TUI that redraws
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
     * Set to true just before sending `opencode-cli` so that the first batch of raw SSH output
     * received afterwards triggers a SYSTEM acknowledgement message.  Cleared atomically on
     * first use.  The ack fires unconditionally (even when the batch also contains non-empty
     * content such as a shell error) so the user always sees a startup indicator.
     */
    private val awaitingOpenCodeAck = AtomicBoolean(false)

    /**
     * Accumulates raw SSH text received between debounce windows.  Written from the IO thread
     * via atomic append; read and cleared from the main thread after the debounce delay.
     *
     * opencode-cli can send its AI response as many small incremental chunks (cursor moves,
     * partial text writes, etc.) rather than a single full-screen repaint.  Replacing the
     * buffer on each chunk would silently discard all but the last one.  Accumulating ensures
     * that [flushOutput] always receives the complete sequence of bytes emitted since the last
     * flush, so ANSI stripping can recover all visible text.
     */
    private val pendingOutput = AtomicReference<String?>(null)

    /** Debounce job for [processOutput]. Cancelled and restarted on each incoming batch. */
    private var outputDebounceJob: Job? = null
    private var protocolDebugLogsEnabled: Boolean = false

    /**
     * ID of the ASSISTANT message for the current conversation turn.  Rather than appending a
     * new bubble for every TUI screen redraw we UPDATE this message in-place.  Reset to null
     * when the user sends the next prompt so the following response gets a fresh bubble.
     *
     * Accessed exclusively from the main thread (all writes/reads happen inside
     * [viewModelScope] coroutines which dispatch to [kotlinx.coroutines.Dispatchers.Main]).
     */
    private var currentAssistantMsgId: String? = null

    init {
        viewModelScope.launch {
            sessionRepository.getSessionById(sessionId)?.let {
                _sessionName.value = it.name
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
                addMessage("Connected! Starting opencode-cli...", MessageType.SYSTEM)
                if (protocolDebugLogsEnabled) {
                    sshManager.appendLog("[PROTO] debug trace enabled")
                }
                if (isFirstConnect) {
                    isFirstConnect = false
                    kotlinx.coroutines.delay(OPENCODE_STARTUP_DELAY_MS)
                    // Track the startup command so its terminal echo is suppressed.
                    pendingEchoCommands.merge("opencode-cli", 1, Int::plus)
                    // Arm the acknowledgement flag before writing to the stream so that the
                    // very first incoming bytes (opencode's TUI drawing sequences) are noticed.
                    awaitingOpenCodeAck.set(true)
                    sshManager.sendCommand("opencode-cli")
                    addMessage("opencode-cli launched. Waiting for response…", MessageType.SYSTEM)
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
            // Each new user turn gets a fresh ASSISTANT bubble.
            currentAssistantMsgId = null
            addMessage(text, MessageType.USER)
            // Track the prompt so its terminal echo is suppressed in processOutput.
            pendingEchoCommands.merge(text.trim(), 1, Int::plus)
            val result = sshManager.sendCommand(text)
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
     * opencode-cli streams its AI response as many small incremental chunks (cursor moves,
     * colour resets, partial text writes, …) rather than a single full-screen repaint.
     * Processing each chunk individually would flood the chat with dozens of ASSISTANT bubbles.
     * Instead we:
     *
     * 1. Fire the one-time "opencode is active" ack immediately on the very first batch.
     * 2. Atomically APPEND each chunk to [pendingOutput] so no bytes are discarded between
     *    debounce windows.  (Replacing with only the latest chunk lost all earlier content,
     *    causing the response to appear blank after ANSI stripping.)
     * 3. Debounce: cancel any pending flush job and schedule a new one [OUTPUT_DEBOUNCE_MS] ms
     *    in the future.  The flush only runs after the TUI goes quiet (response complete / idle).
     * 4. The flush extracts the latest screen render, strips ANSI, filters echoes and shell
     *    prompts, then UPDATES (or creates) a single ASSISTANT bubble for this conversation
     *    turn instead of appending new ones.
     */
    private fun processOutput(text: String) {
        val isFirstAck = awaitingOpenCodeAck.getAndSet(false)

        // Accumulate all chunks so the debounce handler always sees the full response text.
        pendingOutput.updateAndGet { prev -> (prev ?: "") + text }

        viewModelScope.launch {
            // isFirstAck is read atomically above and used only inside this launch block,
            // which runs on the main thread – no additional synchronisation is needed.
            if (isFirstAck) {
                addMessage("opencode is active and sending output.", MessageType.SYSTEM)
            }

            // Cancel any pending flush and start a new one.  Both the cancel and the new launch
            // happen on the main thread (viewModelScope → Dispatchers.Main) so flushOutput can
            // never be called concurrently, and currentAssistantMsgId / _messages are safe.
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
     *
     * opencode-cli can take a long time to respond (AI API latency) during which the input
     * field is disabled and the app appears frozen.  This lets the user regain control without
     * needing to disconnect and reconnect.  Any response that eventually arrives will still be
     * displayed – it will simply create a new ASSISTANT bubble rather than updating the old one.
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
