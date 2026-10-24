package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.BackgroundShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the background output panel shows (`background-output-panel.tsx`): a command's `.output`
 * file, read when the panel opens on it and again whenever its status changes, then every 1.5 s
 * for as long as it runs. One for the whole window, as the web mounts one panel: a failed read is
 * still reported when the panel opens next, until a read succeeds. Main thread only.
 */
class BackgroundOutput {
    data class Shown(
        /** The file's text, its end only past [MAX_CHARS]. */
        val content: String = "",
        /** Why the last read failed, until one succeeds. */
        val error: String? = null,
        /** From the panel opening, or the status changing, to the first read's answer. */
        val loading: Boolean = false,
    ) {
        /** What the panel's box holds. */
        val text: String
            get() = when {
                error != null -> "Failed to read output: $error"
                content.isNotEmpty() -> content
                loading -> "Loading…"
                else -> "(no output yet)"
            }
    }

    private val _shown = MutableStateFlow(Shown())
    val shown: StateFlow<Shown> = _shown.asStateFlow()

    /**
     * Reads [path] once and, while [running], again every [POLL_MILLIS] until cancelled. Each call
     * starts over from an empty box, as the web's effect does on a new path or status.
     */
    suspend fun follow(path: String, running: Boolean, read: suspend (String) -> String) {
        _shown.update { it.copy(content = "", loading = true) }
        fetch(path, read)
        if (!running) return
        while (true) {
            delay(POLL_MILLIS)
            fetch(path, read)
        }
    }

    private suspend fun fetch(path: String, read: suspend (String) -> String) {
        try {
            val text = read(path)
            _shown.update { it.copy(content = trimmed(text), error = null, loading = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _shown.update { it.copy(error = error.message ?: error.toString(), loading = false) }
        }
    }

    companion object {
        const val POLL_MILLIS = 1_500L

        /** Only the end is kept past this many UTF-16 units (~500 KB), so a chatty command cannot take all the memory there is. */
        const val MAX_CHARS = 500_000

        /**
         * The last [MAX_CHARS] UTF-16 units, as `text.slice(-MAX_CHARS)` keeps them. A cut through a
         * surrogate pair leaves half a character, which JavaScript draws as a replacement mark.
         */
        fun trimmed(text: String): String {
            if (text.length <= MAX_CHARS) return text
            val kept = text.substring(text.length - MAX_CHARS)
            return if (kept[0].isLowSurrogate()) "�" + kept.substring(1) else kept
        }

        /**
         * `findByOutput`: the tracked command a `.output` file name, or its stem, belongs to — what a
         * file link in an answer opens the panel on instead of the file.
         */
        fun findByOutput(name: String, shells: Sequence<BackgroundShell>): BackgroundShell? {
            if (name.isEmpty()) return null
            val stem = name.split('/', '\\').last().removeSuffix(".output")
            return shells.firstOrNull { it.shellId == stem || it.outputPath.endsWith("$stem.output") || it.outputPath.endsWith(name) }
        }
    }
}
