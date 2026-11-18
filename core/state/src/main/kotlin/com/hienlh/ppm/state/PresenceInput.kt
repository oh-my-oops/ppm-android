package com.hienlh.ppm.state

/**
 * "A person just interacted with PPM" → `presence:input` on `/ws/global`, at most one send per
 * [THROTTLE_MILLIS] (`presence-input.ts`), so the server's presence tracker can tell someone at
 * this device from an app merely left open.
 *
 * An input that arrives before the socket is up is kept as pending rather than counted as sent:
 * it does not arm the throttle, and [retryIfPending] sends it the moment the socket is ready.
 * Counting a dropped send would swallow the next real input for up to a minute.
 */
class PresenceInput(
    /** Sends the frame if the socket is open; false when it could not. */
    private val send: (String) -> Boolean,
    private val now: () -> Long = ::monotonicMillis,
) {
    private var lastSentAt: Long? = null
    private var pending = false

    /** Real input: a tap, a key, a scroll wheel, typing. */
    fun input() {
        val t = now()
        val last = lastSentAt
        if (!pending && last != null && t - last < THROTTLE_MILLIS) return
        attempt(t)
    }

    /** The socket became ready: a no-op unless the last input could not be sent. */
    fun retryIfPending() {
        if (pending) attempt(now())
    }

    private fun attempt(t: Long) {
        if (send(FRAME)) {
            lastSentAt = t
            pending = false
        } else {
            pending = true
        }
    }

    companion object {
        /** `PRESENCE_SEND_THROTTLE_MS`. */
        const val THROTTLE_MILLIS = 60_000L
        const val FRAME = """{"type":"presence:input"}"""
    }
}
