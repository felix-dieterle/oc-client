package com.felix.occlient.network

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Logger as JSchLogger
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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

        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }

    private val _connectionState = MutableStateFlow(SshConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SshConnectionState> = _connectionState

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null

    var onOutputReceived: ((String) -> Unit)? = null

    fun appendLog(message: String) {
        val timestamp = LocalTime.now().format(timeFormatter)
        _logs.value = _logs.value + "[$timestamp] $message"
    }

    suspend fun connect(config: SshConfig): Result<Unit> = withContext(Dispatchers.IO) {
        _connectionState.value = SshConnectionState.CONNECTING
        appendLog("Connecting to ${config.host}:${config.port} as ${config.username}...")
        try {
            val jsch = JSch()
            // Capture JSch-level warnings and errors to aid troubleshooting
            JSch.setLogger(object : JSchLogger {
                override fun isEnabled(level: Int): Boolean = level >= JSchLogger.WARN
                override fun log(level: Int, message: String) {
                    val prefix = when (level) {
                        JSchLogger.WARN -> "[JSch WARN]"
                        JSchLogger.ERROR -> "[JSch ERROR]"
                        JSchLogger.FATAL -> "[JSch FATAL]"
                        else -> "[JSch INFO]"
                    }
                    appendLog("$prefix $message")
                }
            })
            if (config.privateKey.isNotBlank()) {
                // Normalize line endings to LF to avoid PEM parsing failures with keys from Windows/macOS
                val normalizedKey = config.privateKey.trimIndent()
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                val keyBytes = normalizedKey.toByteArray(Charsets.UTF_8)
                jsch.addIdentity("key", keyBytes, null, null)
                val keyType = when {
                    normalizedKey.contains("BEGIN OPENSSH PRIVATE KEY") -> "OpenSSH"
                    normalizedKey.contains("BEGIN RSA PRIVATE KEY") -> "RSA (PKCS#1)"
                    normalizedKey.contains("BEGIN EC PRIVATE KEY") -> "EC (PKCS#1)"
                    normalizedKey.contains("BEGIN DSA PRIVATE KEY") -> "DSA (PKCS#1)"
                    normalizedKey.contains("BEGIN PRIVATE KEY") -> "PKCS#8"
                    else -> "unknown format"
                }
                appendLog("Using private key authentication ($keyType, ${keyBytes.size} bytes)")
            }
            val session = jsch.getSession(config.username, config.host, config.port)
            // Note: host key verification is disabled for simplicity since users connect to their own servers.
            // For production use, implement known-hosts verification to prevent MITM attacks.
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", if (config.privateKey.isNotBlank()) "publickey" else "password")
            if (config.password.isNotBlank()) {
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
            val causeChain = generateSequence(e as Throwable) { t -> t.cause?.takeIf { it != t } }
                .drop(1)
                .joinToString(" → ") { it.message ?: it.javaClass.simpleName }
            val msg = buildString {
                append("SSH connection failed: ${e.message}")
                if (causeChain.isNotEmpty()) append("\nCause: $causeChain")
            }
            appendLog(msg)
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
        _connectionState.value = SshConnectionState.DISCONNECTED
        appendLog("Disconnected")
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
