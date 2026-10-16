package df.root.term

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * Converts a stream of text containing ANSI SGR escape sequences into styled
 * spans.
 *
 * Scope is deliberately SGR only (colours and a few attributes). Cursor
 * addressing, the alternate screen and scroll regions are NOT implemented:
 * this terminal renders a scrollback of completed command output, and every
 * command here writes plain lines to stdout. Anything outside SGR is stripped
 * so a stray escape cannot corrupt the display.
 *
 * The parser is stateless across calls by design - each line is converted
 * independently - but [AnsiState] can be threaded through when a program
 * colours output across multiple lines.
 */
object Ansi {

    /** A mutable style carried across lines, since SGR state persists. */
    data class State(
        var fg: Color? = null,
        var bg: Color? = null,
        var bold: Boolean = false,
        var underline: Boolean = false,
        var inverse: Boolean = false,
    )

    /**
     * Terminators of genuine two-character escape sequences: ESC 7/8 (save and
     * restore cursor), ESC =/> (keypad modes), ESC c (reset), and the C1
     * single-letter controls. Anything else after an ESC is not a sequence.
     */
    private val ESC_TWO_CHAR = setOf('7', '8', '=', '>', 'c', 'D', 'E', 'H', 'M', 'N', 'O', 'Z')

    /** The 16 basic colours, tuned for a dark terminal background. */
    private val BASIC = listOf(
        Color(0xFF000000), Color(0xFFCD3131), Color(0xFF0DBC79), Color(0xFFE5E510),
        Color(0xFF2472C8), Color(0xFFBC3FBC), Color(0xFF11A8CD), Color(0xFFE5E5E5),
        Color(0xFF666666), Color(0xFFF14C4C), Color(0xFF23D18B), Color(0xFFF5F543),
        Color(0xFF3B8EEA), Color(0xFFD670D6), Color(0xFF29B8DB), Color(0xFFFFFFFF),
    )

    /**
     * Renders [text] with [state] applied, returning the annotated result and
     * leaving [state] updated for the next line.
     */
    fun render(text: String, state: State = State()): AnnotatedString {
        val out = buildAnnotatedString {
            var i = 0
            var plainStart = 0
            while (i < text.length) {
                val esc = text.indexOf('\u001B', i)
                if (esc < 0) {
                    append(text.substring(plainStart))
                    break
                }
                // Flush the run before this escape using the current style.
                if (esc > plainStart) withStyle(state) { append(text.substring(plainStart, esc)) }

                val consumed = applyEscape(text, esc, state)
                if (consumed <= 0) {
                    // Not a sequence we understand; drop just the ESC so the
                    // rest of the line still shows.
                    i = esc + 1
                } else {
                    i = esc + consumed
                }
                plainStart = i
            }
        }
        return out
    }

    /** Convenience for the common case of no carried-over state. */
    fun render(text: String): AnnotatedString = render(text, State())

    private fun androidx.compose.ui.text.AnnotatedString.Builder.withStyle(
        s: State,
        block: () -> Unit,
    ) {
        val fg = if (s.inverse) (s.bg ?: Color(0xFF1E1E1E)) else (s.fg ?: Color(0xFFE6E6E6))
        val bg = if (s.inverse) (s.fg ?: Color(0xFFE6E6E6)) else s.bg
        pushStyle(
            SpanStyle(
                color = fg,
                background = bg ?: Color.Unspecified,
                fontWeight = if (s.bold) FontWeight.Bold else null,
                textDecoration = if (s.underline)
                    androidx.compose.ui.text.style.TextDecoration.Underline else null,
            )
        )
        block()
        pop()
    }

    /**
     * Applies the escape sequence starting at [at]. Returns how many characters
     * it spans, or 0 if it is not a sequence we handle.
     */
    private fun applyEscape(text: String, at: Int, state: State): Int {
        if (at + 1 >= text.length) return 0
        when (text[at + 1]) {
            '[' -> {
                // CSI: ESC [ params final
                var j = at + 2
                while (j < text.length && text[j] !in '@'..'~') j++
                if (j >= text.length) return 0
                if (text[j] == 'm') applySgr(text.substring(at + 2, j), state)
                return j - at + 1
            }
            ']' -> {
                // OSC: ESC ] ... BEL or ESC \  - used for window titles.
                var j = at + 2
                while (j < text.length) {
                    if (text[j] == '\u0007') return j - at + 1
                    if (text[j] == '\u001B' && j + 1 < text.length && text[j + 1] == '\\')
                        return j - at + 2
                    j++
                }
                return text.length - at
            }
            // Only real two-character sequences are consumed. Treating any
            // letter as a terminator would eat a plain character after a stray
            // ESC, turning "abc" into "ac".
            in ESC_TWO_CHAR -> return 2
            else -> return 0
        }
    }

    private fun applySgr(params: String, state: State) {
        val parts = params.split(';')
        // "ESC[m" is equivalent to "ESC[0m".
        if (params.isEmpty()) { reset(state); return }

        var i = 0
        while (i < parts.size) {
            val code = parts[i].toIntOrNull() ?: 0
            when {
                code == 0 -> reset(state)
                code == 1 -> state.bold = true
                code == 4 -> state.underline = true
                code == 7 -> state.inverse = true
                code == 22 -> state.bold = false
                code == 24 -> state.underline = false
                code == 27 -> state.inverse = false
                code in 30..37 -> state.fg = BASIC[code - 30]
                code == 39 -> state.fg = null
                code in 40..47 -> state.bg = BASIC[code - 40]
                code == 49 -> state.bg = null
                code in 90..97 -> state.fg = BASIC[code - 90 + 8]
                code in 100..107 -> state.bg = BASIC[code - 100 + 8]
                // 256-colour and truecolour: 38;5;n / 38;2;r;g;b (and the 48 forms)
                (code == 38 || code == 48) && i + 1 < parts.size -> {
                    val mode = parts[i + 1].toIntOrNull() ?: 0
                    val isFg = code == 38
                    if (mode == 5 && i + 2 < parts.size) {
                        val c = color256(parts[i + 2].toIntOrNull() ?: 0)
                        if (isFg) state.fg = c else state.bg = c
                        i += 2
                    } else if (mode == 2 && i + 4 < parts.size) {
                        val c = Color(
                            (parts[i + 2].toIntOrNull() ?: 0).coerceIn(0, 255),
                            (parts[i + 3].toIntOrNull() ?: 0).coerceIn(0, 255),
                            (parts[i + 4].toIntOrNull() ?: 0).coerceIn(0, 255),
                        )
                        if (isFg) state.fg = c else state.bg = c
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun reset(s: State) {
        s.fg = null; s.bg = null
        s.bold = false; s.underline = false; s.inverse = false
    }

    /** The standard xterm 256-colour cube. */
    private fun color256(n: Int): Color {
        if (n < 16) return BASIC[n.coerceIn(0, 15)]
        if (n < 232) {
            val i = n - 16
            val r = i / 36
            val g = (i % 36) / 6
            val b = i % 6
            fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
            return Color(level(r), level(g), level(b))
        }
        val v = 8 + (n - 232) * 10
        return Color(v, v, v)
    }

    /** Strips escapes without styling, for logs or copying as plain text. */
    fun strip(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\u001B') {
                i += applyEscape(text, i, State()).coerceAtLeast(1)
            } else {
                sb.append(text[i]); i++
            }
        }
        return sb.toString()
    }
}
