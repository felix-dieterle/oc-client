package com.felix.occlient.util

/**
 * Utilities for stripping ANSI/VT100 terminal escape sequences from SSH output.
 *
 * Three categories are handled:
 *  - CSI sequences (ESC [ …): covers both standard and DEC private modes (e.g. \x1b[?1049h).
 *  - OSC sequences (ESC ] … BEL): terminal title/palette commands.
 *  - All other 2-char escape sequences (e.g. ESC=, ESC>, ESC(, ESC7).
 */
object AnsiUtils {
    /** CSI: ESC [ <param bytes 0x20-0x3F>* <final byte 0x40-0x7E> */
    private val ANSI_CSI = Regex("\u001B\\[[\u0020-\u003F]*[\u0040-\u007E]")
    /** OSC: ESC ] … BEL */
    private val ANSI_OSC = Regex("\u001B\\][^\u0007]*\u0007")
    /** Remaining 2-char escape sequences not starting with [ or ].
     *  The character class [^\\[\\]] matches exactly one character, so a lone ESC at the end
     *  of a buffer is intentionally left untouched (it cannot be a complete sequence). */
    private val ANSI_OTHER = Regex("\u001B[^\\[\\]]")

    /** Returns [text] with all ANSI/VT100 escape sequences and bare carriage-returns removed. */
    fun strip(text: String): String = text
        .replace(ANSI_CSI, "")
        .replace(ANSI_OSC, "")
        .replace(ANSI_OTHER, "")
        .replace("\r", "")
}
