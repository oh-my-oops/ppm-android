package com.hienlh.ppm.state

/** How one `/api/health` request came back. */
enum class HealthCheck {
    /** A 2xx: the server runs. */
    Up,

    /** An answer, but an error: something is there, so it does not count as down. */
    Error,

    /** No answer at all. */
    NoAnswer,
}

/**
 * The web's health poll and connection store (use-server-reload.ts, connection-store.ts): a
 * check every 10 s, every 2 s while the server is down. Only a request that gets no answer
 * counts as down; the connection-lost overlay shows once the server has been down for 15 s,
 * judged at each unanswered check. An answer after being down means the server came back, and
 * the web reloads the page then; [record] says when.
 */
class HealthWatch {
    /** When the first unanswered check of this outage ran. */
    var downSince: Long? = null
        private set

    /** The connection-lost overlay is up. */
    var overlay = false
        private set

    private var wasDown = false

    /** The wait before the next check. */
    val nextDelayMs: Long get() = if (wasDown) DOWN_POLL_MS else POLL_MS

    /** Records a check made at [now]; true when the server answered after being down. */
    fun record(check: HealthCheck, now: Long): Boolean {
        when (check) {
            HealthCheck.Up -> {
                val back = wasDown
                markUp()
                wasDown = false
                return back
            }
            // The web resets its flag on any answer but leaves the overlay as it was.
            HealthCheck.Error -> wasDown = false
            HealthCheck.NoAnswer -> {
                wasDown = true
                val since = downSince ?: now
                downSince = since
                overlay = now - since >= OVERLAY_THRESHOLD_MS
            }
        }
        return false
    }

    /** The overlay's Retry: true when the server answered and should be opened again. */
    fun retry(check: HealthCheck): Boolean {
        if (check != HealthCheck.Up) return false
        markUp()
        return true
    }

    private fun markUp() {
        downSince = null
        overlay = false
    }

    companion object {
        const val POLL_MS = 10_000L
        const val DOWN_POLL_MS = 2_000L
        const val OVERLAY_THRESHOLD_MS = 15_000L
    }
}
