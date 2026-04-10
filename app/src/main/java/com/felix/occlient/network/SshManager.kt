package com.felix.occlient.network

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

enum class SshConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val privateKey: String = "",
    val password: String = ""
)

class SshManager {
    companion object {
        /** Interval between polls when no SSH data is available, in milliseconds. */
        private const val SSH_READ_POLL_INTERVAL_MS = 50L
    }

    private val _connectionState = MutableStateFlow(SshConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SshConnectionState> = _connectionState

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    /** Username of the active connection, kept to mask it in log entries. */
    private var currentUsername: String = ""
    /** Pre-compiled regex for efficient word-boundary replacement of [currentUsername]. Null when no user is set. */
    private var usernameRegex: Regex? = null

    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null

    var onOutputReceived: ((String) -> Unit)? = null

    fun appendLog(message: String) {
        val regex = usernameRegex
        val masked = if (regex != null)
            message.replace(regex, maskUsername(currentUsername))
        else
            message
        _logs.value = _logs.value + masked
    }

    /** Returns a masked representation that hides username length, e.g. "john" → "j***", "a" → "***". */
    private fun maskUsername(username: String): String = when {
        username.isEmpty() -> ""
        username.length == 1 -> "***"
        else -> "${username.first()}***"
    }

    suspend fun connect(config: SshConfig): Result<Unit> = withContext(Dispatchers.IO) {
        currentUsername = config.username
        usernameRegex = if (config.username.isNotBlank())
            Regex("\\b${Regex.escape(config.username)}\\b")
        else
            null
        _connectionState.value = SshConnectionState.CONNECTING
        appendLog("Connecting to ${config.host}:${config.port} as ${config.username}...")
        val usingKey = config.privateKey.isNotBlank()
        try {
            val jsch = JSch()
            JSch.setLogger(object : com.jcraft.jsch.Logger {
                override fun isEnabled(level: Int) = level >= com.jcraft.jsch.Logger.INFO
                override fun log(level: Int, message: String) {
                    appendLog("[SSH] $message")
                }
            })
            if (usingKey) {
                val keyHeader = config.privateKey.trim().lines().firstOrNull() ?: ""
                val keyType = Regex("-----BEGIN (.+?) PRIVATE KEY-----").find(keyHeader)
                    ?.groupValues?.getOrNull(1) // groupValues[0] is full match, [1] is the captured key type
                    ?: "UNKNOWN"
                val passphrase = if (config.password.isNotBlank()) config.password.toByteArray(Charsets.UTF_8) else null
                appendLog("Using private key authentication")
                appendLog("Key type: $keyType, passphrase provided: ${passphrase != null}")
                val keyBytes = config.privateKey.trimIndent().toByteArray(Charsets.UTF_8)
                jsch.addIdentity("key", keyBytes, null, passphrase)
            }
            val session = jsch.getSession(config.username, config.host, config.port)
            // Note: host key verification is disabled for simplicity since users connect to their own servers.
            // For production use, implement known-hosts verification to prevent MITM attacks.
            session.setConfig("StrictHostKeyChecking", "no")
            val preferredAuths = when {
                usingKey -> "publickey,keyboard-interactive,password"
                else -> "keyboard-interactive,password"
            }
            session.setConfig("PreferredAuthentications", preferredAuths)
            appendLog("Preferred auth methods: $preferredAuths")
            if (config.password.isNotBlank() && !usingKey) {
                session.setPassword(config.password)
            }
            session.connect(15000)
            jschSession = session
            appendLog("SSH session established")

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("vt100")
            channel.setPtySize(220, 50, 1320, 300)
            outputStream = channel.outputStream
            inputStream = channel.inputStream
            channel.connect(10000)
            shellChannel = channel
            _connectionState.value = SshConnectionState.CONNECTED
            appendLog("Shell channel opened")
            startReading()
            Result.success(Unit)
        } catch (e: JSchException) {
            val msg = "SSH connection failed: ${e.message}"
            appendLog(msg)
            if (usingKey && e.message?.contains("Auth fail", ignoreCase = true) == true) {
                appendLog("Auth failure detail: private key authentication was rejected by the server")
                appendLog("Hint: ensure the public key is in ~/.ssh/authorized_keys on the server and the key format is supported (RSA/ED25519/ECDSA)")
            }
            _connectionState.value = SshConnectionState.ERROR
            Result.failure(Exception(msg, e))
        } catch (e: Exception) {
            val msg = "Connection error: ${e.message}"
            appendLog(msg)
            _connectionState.value = SshConnectionState.ERROR
            Result.failure(e)
        }
    }

    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(4096)
            try {
                while (isActive && shellChannel?.isClosed == false) {
                    val available = inputStream?.available() ?: 0
                    if (available > 0) {
                        val n = inputStream?.read(buffer, 0, minOf(available, buffer.size)) ?: -1
                        if (n > 0) {
                            val text = String(buffer, 0, n, Charsets.UTF_8)
                            appendLog(text)
                            onOutputReceived?.invoke(text)
                        }
                    } else {
                        if (shellChannel?.isClosed == true) break
                        delay(SSH_READ_POLL_INTERVAL_MS)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    appendLog("Read error: ${e.message}")
                    _connectionState.value = SshConnectionState.ERROR
                }
            }
            if (_connectionState.value == SshConnectionState.CONNECTED) {
                _connectionState.value = SshConnectionState.DISCONNECTED
                appendLog("Connection closed by remote")
            }
        }
    }

    suspend fun sendCommand(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val out = outputStream ?: return@withContext Result.failure(Exception("Not connected"))
            val ps = PrintStream(out, true, Charsets.UTF_8)
            ps.println(command)
            out.flush()
            appendLog("> $command")
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = "Send error: ${e.message}"
            appendLog(msg)
            Result.failure(e)
        }
    }

    fun disconnect() {
        readJob?.cancel()
        try { shellChannel?.disconnect() } catch (_: Exception) {}
        try { jschSession?.disconnect() } catch (_: Exception) {}
        shellChannel = null
        jschSession = null
        outputStream = null
        inputStream = null
        currentUsername = ""
        usernameRegex = null
        _connectionState.value = SshConnectionState.DISCONNECTED
        appendLog("Disconnected")
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
