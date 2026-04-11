package com.felix.occlient.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpencodeProtocolTest {

    @Test
    fun parseAssistantOutput_keepsLatestNonEmptyFrame_whenTrailingFrameIsAnsiOnly() {
        val frameWithContent = "\u001B[?25lAntwort auf die erste Eingabe\u001B[?25h"
        val trailingAnsiOnlyFrame = "\u001B[?25l\u001B[2K\u001B[?25h"
        val raw = frameWithContent + trailingAnsiOnlyFrame

        val result = OpencodeProtocol.parseAssistantOutput(raw, mutableMapOf())

        assertEquals("Antwort auf die erste Eingabe", result.assistantText)
        assertFalse(result.sawShellPrompt)
        assertTrue(result.droppedEchoLines.isEmpty())
        assertTrue(result.droppedPromptLines.isEmpty())
        assertTrue(result.extractedScreenLength > 0)
    }

    @Test
    fun parseAssistantOutput_fallsBackToPreviousFrame_whenLatestFrameOnlyContainsPrompt() {
        val frameWithAnswer = "\u001B[?25lDie Antwort ist 4.\u001B[?25h"
        val latestPromptOnlyFrame = "\u001B[?25l@felix-user ➜ /workspace/app $\u001B[?25h"
        val raw = frameWithAnswer + latestPromptOnlyFrame

        val result = OpencodeProtocol.parseAssistantOutput(raw, mutableMapOf())

        assertEquals("Die Antwort ist 4.", result.assistantText)
    }

    @Test
    fun parseAssistantOutput_filtersExactEcho_andConsumesPendingEntry() {
        val pending = mutableMapOf("erste frage" to 1)
        val raw = "erste frage\nDie Antwort ist da.\n"

        val result = OpencodeProtocol.parseAssistantOutput(raw, pending)

        assertEquals("Die Antwort ist da.", result.assistantText)
        assertTrue("echo entry should be consumed", pending.isEmpty())
        assertEquals(listOf("erste frage"), result.droppedEchoLines)
    }

    @Test
    fun parseAssistantOutput_filtersPromptPrefixedEcho_unixStyle() {
        val pending = mutableMapOf("erste frage" to 1)
        val raw = "user@host:~$ erste frage\nEndgueltige Antwort\n"

        val result = OpencodeProtocol.parseAssistantOutput(raw, pending)

        assertEquals("Endgueltige Antwort", result.assistantText)
        assertTrue(pending.isEmpty())
        assertEquals(listOf("user@host:~$ erste frage"), result.droppedEchoLines)
    }

    @Test
    fun parseAssistantOutput_detectsShellPromptAndRemovesPromptLine_windowsStyle() {
        val raw = "PS C:\\Users\\me>\n"

        val result = OpencodeProtocol.parseAssistantOutput(raw, mutableMapOf())

        assertTrue(result.sawShellPrompt)
        assertEquals("", result.assistantText)
        assertEquals(listOf("PS C:\\Users\\me>"), result.droppedPromptLines)
    }

    @Test
    fun parseAssistantOutput_detectsShellPromptAndRemovesPromptLine_unixStyle() {
        val raw = "user@host:/srv/app$\n"

        val result = OpencodeProtocol.parseAssistantOutput(raw, mutableMapOf())

        assertTrue(result.sawShellPrompt)
        assertEquals("", result.assistantText)
        assertEquals(listOf("user@host:/srv/app$"), result.droppedPromptLines)
    }

    @Test
    fun parseAssistantOutput_detectsShellPromptAndRemovesPromptLine_codespaceStyle() {
        val raw = "@felix-user ➜ /workspaces/oc-client (feature/x) $\n"

        val result = OpencodeProtocol.parseAssistantOutput(raw, mutableMapOf())

        assertTrue(result.sawShellPrompt)
        assertEquals("", result.assistantText)
        assertEquals(listOf("@felix-user ➜ /workspaces/oc-client (feature/x) $"), result.droppedPromptLines)
    }
}
