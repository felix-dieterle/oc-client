package com.felix.occlient.viewmodel

import com.felix.occlient.util.AnsiUtils

internal data class ProtocolParseResult(
    val assistantText: String,
    val sawShellPrompt: Boolean,
    val consideredLineCount: Int,
    val droppedEchoLines: List<String>,
    val droppedPromptLines: List<String>,
    val extractedScreenLength: Int
)

/**
 * Pure protocol/parser logic for opencode-cli terminal output.
 *
 * Kept outside ChatViewModel to enable deterministic JVM tests for
 * input/output handling and shell-echo filtering.
 */
internal object OpencodeProtocol {
    // Windows cmd/PowerShell: optional "PS " prefix, optional "user@host " prefix, then drive:\path>
    private val WINDOWS_SHELL_PROMPT_REGEX = Regex(
        """^(?:PS )?(?:[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+ )?[A-Za-z]:\\[^\n]*>\s*$"""
    )

    // Linux/macOS bash/zsh: user@host:/path$ or user@host:/path#
    private val UNIX_SHELL_PROMPT_REGEX = Regex(
        """^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:[^\n]*[#${'$'}]\s*$"""
    )

    /**
     * Parses a debounced raw SSH snapshot into visible assistant text while mutating
     * [pendingEchoCommands] to consume matched command echoes.
     */
    fun parseAssistantOutput(
        raw: String,
        pendingEchoCommands: MutableMap<String, Int>
    ): ProtocolParseResult {
        val extracted = extractLatestScreen(raw)
        val withoutAnsi = AnsiUtils.strip(extracted)

        var sawShellPrompt = false
        val droppedEchoLines = mutableListOf<String>()
        val droppedPromptLines = mutableListOf<String>()
        val lines = withoutAnsi.split("\n")
        val filteredLines = lines.filter { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@filter false

            val echoedCmd: String? = pendingEchoCommands[line]
                ?.let { line }
                ?: pendingEchoCommands.keys.asSequence().firstOrNull { cmd ->
                    matchesShellPromptEcho(line, cmd)
                }

            if (echoedCmd != null) {
                droppedEchoLines += line
                val next = (pendingEchoCommands[echoedCmd] ?: 0) - 1
                if (next <= 0) pendingEchoCommands.remove(echoedCmd) else pendingEchoCommands[echoedCmd] = next
                return@filter false
            }

            if (isShellPromptLine(line)) {
                sawShellPrompt = true
                droppedPromptLines += line
                return@filter false
            }

            true
        }

        return ProtocolParseResult(
            assistantText = filteredLines.joinToString("\n").trim(),
            sawShellPrompt = sawShellPrompt,
            consideredLineCount = lines.size,
            droppedEchoLines = droppedEchoLines,
            droppedPromptLines = droppedPromptLines,
            extractedScreenLength = extracted.length
        )
    }

    /**
     * Extracts the latest full-screen TUI render by scanning for the last frame start marker.
     * Falls back to previous non-empty frames to avoid dropping valid content.
     */
    fun extractLatestScreen(raw: String): String {
        val hideCursor = "\u001B[?25l"
        var searchPos = raw.length
        while (searchPos > 0) {
            val pos = raw.lastIndexOf(hideCursor, searchPos - 1)
            if (pos < 0) break
            val candidate = raw.substring(pos)
            if (AnsiUtils.strip(candidate).isNotBlank()) return candidate
            searchPos = pos
        }
        return raw
    }

    fun isShellPromptLine(line: String): Boolean =
        WINDOWS_SHELL_PROMPT_REGEX.matches(line) || UNIX_SHELL_PROMPT_REGEX.matches(line)

    /**
     * True when [line] is a prompt-prefixed echo of [cmd].
     */
    fun matchesShellPromptEcho(line: String, cmd: String): Boolean =
        line.endsWith(">$cmd") || line.endsWith("> $cmd") ||
            line.endsWith("\$$cmd") || line.endsWith("\$ $cmd") ||
            line.endsWith("#$cmd") || line.endsWith("# $cmd")
}