package com.hienlh.ppm.design

import android.util.Log

/**
 * The app's logger: `android.util.Log`, plus whatever the app hands it (`AppTrace`, which sends
 * each line to PPM's Logs window as a web tab's console does). Every module logs through this
 * rather than `Log` itself, or its lines stay on the device.
 */
object PpmLog {
    /** Gets every line after logcat has; must not throw, and must not log through [PpmLog]. */
    interface Sink {
        fun line(priority: Int, tag: String, message: String, error: Throwable?)

        /** A view failed to draw and the app carried on without it (a WebView renderer gone). */
        fun renderError(where: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink? = null

    /** A line logged while handing one to the sink is not handed on again. */
    private val inSink = ThreadLocal<Boolean>()

    fun v(tag: String, message: String, error: Throwable? = null) = log(Log.VERBOSE, tag, message, error)

    fun d(tag: String, message: String, error: Throwable? = null) = log(Log.DEBUG, tag, message, error)

    fun i(tag: String, message: String, error: Throwable? = null) = log(Log.INFO, tag, message, error)

    fun w(tag: String, message: String, error: Throwable? = null) = log(Log.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = log(Log.ERROR, tag, message, error)

    fun renderError(where: String, message: String, error: Throwable? = null) {
        Log.e(where, message, error)
        forward { it.renderError(where, message, error) }
    }

    private fun log(priority: Int, tag: String, message: String, error: Throwable?) {
        Log.println(priority, tag, if (error == null) message else "$message\n${Log.getStackTraceString(error)}")
        forward { it.line(priority, tag, message, error) }
    }

    private inline fun forward(block: (Sink) -> Unit) {
        val current = sink ?: return
        if (inSink.get() == true) return
        inSink.set(true)
        try {
            block(current)
        } catch (_: Throwable) {
            // Logging must never be what breaks the app.
        } finally {
            inSink.set(false)
        }
    }
}
