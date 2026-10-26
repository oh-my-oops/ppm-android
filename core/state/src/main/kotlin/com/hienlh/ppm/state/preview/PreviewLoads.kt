package com.hienlh.ppm.state.preview

import com.hienlh.ppm.protocol.CanvasCheckReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The HTML previews on screen, by file, so the AI's `open_preview` can find the tab showing a
 * page and ask it how the page rendered (src/web/lib/html-preview-loads.ts).
 *
 * Each document a preview loads gets a sequence number from one counter for the whole app, so
 * "a load newer than the one I saw" is a plain comparison even across a preview that was
 * disposed and composed again. A caller reads [latest] before it asks for a reload and then
 * waits for a load past it, so it never checks the document that was on screen before the file
 * changed. Not thread-safe: the main thread drives it.
 */
class PreviewLoads {
    /** Measures the page this load shows; [frame] is the device frame it is seen in ("Phone", "Desktop"). */
    fun interface Check {
        suspend fun run(screenshot: Boolean, frame: String): CanvasCheckReport
    }

    class Load internal constructor(val seq: Int, val check: Check) {
        /** The frame fired `load` for this document: its scripts, styles and images are in. */
        var loaded = false
            internal set
    }

    private class Waiter(val key: String, val after: Int, val done: CompletableDeferred<Load>)

    private val loads = HashMap<String, Load>()
    private val waiters = ArrayList<Waiter>()
    private var counter = 0

    /** A preview started loading a document; returns that load's sequence number. */
    fun begin(key: String, check: Check): Int {
        val seq = ++counter
        loads[key] = Load(seq, check)
        return seq
    }

    /** The frame's `load` fired for load [seq]. A load that was replaced meanwhile is ignored. */
    fun finish(key: String, seq: Int) {
        val load = loads[key]?.takeIf { it.seq == seq } ?: return
        load.loaded = true
        for (waiter in waiters.toList()) {
            if (waiter.key == key && seq > waiter.after) waiter.done.complete(load)
        }
    }

    /** The preview went away; its last load is no longer there to check. */
    fun end(key: String, seq: Int) {
        if (loads[key]?.seq == seq) loads.remove(key)
    }

    /** The newest load of this file's preview, or 0 when none is on screen. */
    fun latest(key: String): Int = loads[key]?.seq ?: 0

    /**
     * The first load of this file's preview past [after] that has finished, or — when [timeoutMs]
     * runs out first — the newest one past [after] that only started (a page still waiting on a
     * slow CDN is better checked late than not at all), or null when none began.
     */
    suspend fun waitFor(key: String, after: Int, timeoutMs: Long): Load? {
        loads[key]?.let { if (it.seq > after && it.loaded) return it }
        val waiter = Waiter(key, after, CompletableDeferred())
        waiters += waiter
        try {
            return withTimeoutOrNull(timeoutMs) { waiter.done.await() } ?: loads[key]?.takeIf { it.seq > after }
        } finally {
            waiters -= waiter
        }
    }

    companion object {
        /** The key a file's preview is known by (`previewKey`). */
        fun key(projectName: String?, filePath: String): String = "${projectName ?: ""}\u0000$filePath"
    }
}
