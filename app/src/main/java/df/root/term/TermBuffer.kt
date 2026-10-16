package df.root.term

import androidx.compose.ui.text.AnnotatedString

/** One entry in the terminal scrollback. */
sealed interface Block {
    /** A command the user (or a preset) ran, echoed with a prompt. */
    data class Command(val text: String) : Block

    /** Output lines from a command. */
    data class Output(val lines: MutableList<AnnotatedString>) : Block

    /** A failure or warning shown in its own colour. */
    data class Notice(val text: String, val isError: Boolean) : Block
}

/**
 * The terminal's scrollback plus its input history.
 *
 * Kept as plain mutable state outside composition so that appending output
 * from a background coroutine never touches Compose state from the wrong
 * thread; the screen observes a version counter instead.
 */
class TermBuffer(private val maxBlocks: Int = 500) {

    val blocks = mutableListOf<Block>()

    /** Bumped on every mutation; the UI reads this to know when to redraw. */
    var version: Int = 0
        private set

    private val history = mutableListOf<String>()
    private var histIndex = -1

    fun prompt(text: String) {
        blocks += Block.Command(text)
        if (text.isNotBlank()) history += text
        histIndex = history.size
        trim()
        version++
    }

    /** Appends a chunk of output, splitting it into lines. */
    fun output(text: String) {
        if (text.isEmpty()) return
        val rendered = text.split('\n')
        val last = blocks.lastOrNull()
        if (last is Block.Output) {
            rendered.forEach { last.lines += Ansi.render(it) }
        } else {
            blocks += Block.Output(rendered.map { Ansi.render(it) }.toMutableList())
        }
        trim()
        version++
    }

    fun notice(text: String, isError: Boolean = false) {
        blocks += Block.Notice(text, isError)
        trim()
        version++
    }

    fun clear() {
        blocks.clear()
        version++
    }

    private fun trim() {
        while (blocks.size > maxBlocks) blocks.removeAt(0)
    }

    /** The whole scrollback as plain text, for "copy all". */
    fun asPlainText(): String = buildString {
        for (b in blocks) when (b) {
            is Block.Command -> append("$ ").append(b.text).append('\n')
            is Block.Output -> b.lines.forEach { append(Ansi.strip(it.text)).append('\n') }
            is Block.Notice -> append(b.text).append('\n')
        }
    }

    /** Previous entry in history, or null at the start. */
    fun historyPrev(): String? {
        if (history.isEmpty()) return null
        histIndex = (histIndex - 1).coerceAtLeast(0)
        return history.getOrNull(histIndex)
    }

    /** Next entry, or an empty line past the end. */
    fun historyNext(): String {
        if (history.isEmpty()) return ""
        histIndex = (histIndex + 1).coerceAtMost(history.size)
        return history.getOrNull(histIndex) ?: ""
    }
}
