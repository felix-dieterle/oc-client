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
    // Simplified: matches any line with drive letter and > (Windows 10/11 style)
    private val WINDOWS_SHELL_PROMPT_REGEX = Regex(
        """^(?:PS )?(?:[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+ )?[A-Za-z]:.*>?\s*$"""
    )

    // Linux/macOS bash/zsh standard format: user@host:/path$ or user@host:/path#
    private val UNIX_SHELL_PROMPT_REGEX = Regex(
        """^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:[^\n]*[#${'$'}]\s*$"""
    )

    // Non-standard prompts: GitHub Codespaces (@user ➜ path (branch) $),
    // oh-my-zsh with arrow, Starship, fish, etc.
    // Identifies: @user prefix + prompt arrow (➜ → ❯) + path + ending $ or #
    private val CODESPACE_PROMPT_REGEX = Regex(
        """^@[A-Za-z0-9_.-]+\s+[➜→❯][^\n]*[#${'$'}]\s*$"""
    )

    /**
     * Parses a debounced raw SSH snapshot into visible assistant text while mutating
     * [pendingEchoCommands] to consume matched command echoes.
     */
    fun parseAssistantOutput(
        raw: String,
        pendingEchoCommands: MutableMap<String, Int>
    ): ProtocolParseResult {
        val candidates = extractScreenCandidates(raw)
        val selected = candidates.firstOrNull { candidate ->
            val probe = parseCandidate(candidate, pendingEchoCommands.toMutableMap())
            probe.assistantText.isNotBlank()
        } ?: candidates.firstOrNull() ?: raw

        return parseCandidate(selected, pendingEchoCommands)
    }

    private fun parseCandidate(
        snapshot: String,
        pendingEchoCommands: MutableMap<String, Int>
    ): ProtocolParseResult {
        val withoutAnsi = AnsiUtils.strip(snapshot)

        var sawShellPrompt = false
        val droppedEchoLines = mutableListOf<String>()
        val droppedPromptLines = mutableListOf<String>()
        val lines = withoutAnsi.split("\n")
        val filteredLines = lines.filter { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@filter false

            // Skip Windows boot banner lines
            if (line.startsWith("Microsoft Windows") || line.startsWith("(c) Microsoft Corporation")) {
                return@filter false
            }

            val echoedCmd: String? = pendingEchoCommands[line]
                ?.let { line }
                ?: pendingEchoCommands.entries.asSequence().firstOrNull { (cmd, _) ->
                    matchesShellPromptEcho(line, cmd) || line.equals(cmd, ignoreCase = true) ||
                        (line.contains(">") && line.substringAfter(">").trim().equals(cmd, ignoreCase = true))
                }?.key

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

            // Also filter lines containing Windows prompt markers (e.g., "4\nfelix d@DESKTOP>")
            if (line.contains(">") && containsHostPrompt(line)) {
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
            extractedScreenLength = snapshot.length
        )
    }

    /**
     * Extracts the latest full-screen TUI render by scanning for the last frame start marker.
     * Falls back to previous non-empty frames to avoid dropping valid content.
     */
    fun extractLatestScreen(raw: String): String {
        return extractScreenCandidates(raw).firstOrNull() ?: raw
    }

    private fun extractScreenCandidates(raw: String): List<String> {
        val hideCursor = "\u001B[?25l"
        val candidates = mutableListOf<String>()
        var searchPos = raw.length
        while (searchPos > 0) {
            val pos = raw.lastIndexOf(hideCursor, searchPos - 1)
            if (pos < 0) break
            val candidate = raw.substring(pos)
            if (AnsiUtils.strip(candidate).isNotBlank()) {
                candidates += candidate
            }
            searchPos = pos
        }
        if (candidates.isEmpty()) {
            candidates += raw
        }
        return candidates
    }

    fun isShellPromptLine(line: String): Boolean =
        WINDOWS_SHELL_PROMPT_REGEX.matches(line) ||
            UNIX_SHELL_PROMPT_REGEX.matches(line) ||
            CODESPACE_PROMPT_REGEX.matches(line)

    /**
     * Checks if line contains a host-based prompt pattern (e.g., "user@host>" on Windows)
     */
    private fun containsHostPrompt(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("@deskt") || lower.contains("@mach") ||
            lower.contains("@local") || lower.contains("c:\\") || lower.contains("/home/")
    }

    /**
     * True when [line] is a prompt-prefixed echo of [cmd].
     */
    fun matchesShellPromptEcho(line: String, cmd: String): Boolean =
        line.endsWith(">$cmd") || line.endsWith("> $cmd") ||
            line.endsWith("\$$cmd") || line.endsWith("\$ $cmd") ||
            line.endsWith("#$cmd") || line.endsWith("# $cmd")
}
