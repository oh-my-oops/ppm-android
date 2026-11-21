package com.hienlh.ppm.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.hienlh.ppm.client.FileTraceQueueStore
import com.hienlh.ppm.client.HttpTraceTransport
import com.hienlh.ppm.client.PpmHttp
import com.hienlh.ppm.client.TraceClient
import com.hienlh.ppm.client.TraceDeviceId
import com.hienlh.ppm.design.PpmLog
import com.hienlh.ppm.state.logs.DeviceLogBuffer
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * The app's half of browser logging (`trace-client.ts` installed by `trace-boot.ts`): every line
 * logged through [PpmLog], every uncaught exception and every render failure the app survives
 * goes to `POST /api/trace` of the server the app is on, so it shows in PPM's Logs window as
 * "PPM app·Android" and in the timeline of the session that was in front. The same lines are kept
 * on the device too ([deviceLog]), which the Logs window shows as "This device".
 *
 * Installed first thing in [PpmApplication.onCreate], before anything else can fail.
 */
object AppTrace {
    /** Which launch, as the web stamps which page load (`page`). */
    private val launchId = UUID.randomUUID().toString()

    /** The window whose server receives the lines and whose chat they join: the one last brought to the front. */
    @Volatile
    private var front: WeakReference<AppModel>? = null

    @Volatile
    var client: TraceClient? = null
        private set

    /** This app's own recent lines, kept on the device: in memory until [install] gives it a file. */
    @Volatile
    var deviceLog: DeviceLogBuffer = DeviceLogBuffer()
        private set

    fun install(app: Application) {
        if (client != null) return
        PpmHttp.userAgent = userAgent(app)
        val deviceId = TraceDeviceId.get(PrefsStore(app))
        val trace = TraceClient(
            transport = HttpTraceTransport { front?.get()?.api },
            deviceId = { deviceId },
            storage = FileTraceQueueStore(File(app.filesDir, QUEUE_FILE)),
            refId = { front?.get()?.activeSessionId },
            context = { context(front?.get()) },
        )
        val device = DeviceLogBuffer(store = FileDeviceLogStore(File(app.filesDir, DEVICE_LOG_FILE)))
        deviceLog = device
        client = trace
        PpmLog.sink = LogSink(trace, device) { front?.get()?.activeSessionId }
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(trace, device, Thread.getDefaultUncaughtExceptionHandler()))
        app.registerActivityLifecycleCallbacks(Visibility(trace, device))
        // A launch is a line of its own, so the server knows this device from its first sign-in
        // (an app that logs nothing else never sent a batch, and so never showed in Logs).
        PpmLog.i("PPM", launchLine(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE ?: "", Build.MODEL ?: ""))
    }

    /** The line a launch logs. */
    fun launchLine(version: String, release: String, model: String): String =
        "PPM app $version started on Android $release ($model)"

    /** What the app sends as its `User-Agent`: a tablet is a device whose shorter side is 600 dp or more. */
    fun userAgent(context: Context): String = PpmHttp.androidUserAgent(
        version = BuildConfig.VERSION_NAME,
        release = Build.VERSION.RELEASE ?: "",
        model = Build.MODEL ?: "",
        tablet = context.resources.configuration.smallestScreenWidthDp >= 600,
    )

    /** [model]'s window came to the front: its server and its chat are the ones lines go to. */
    fun front(model: AppModel) {
        front = WeakReference(model)
        client?.flushSoon()
    }

    private fun context(model: AppModel?): Map<String, String?> = mapOf("page" to launchId, "project" to model?.selectedProject)

    const val QUEUE_FILE = "trace-queue.json"
    const val DEVICE_LOG_FILE = "device-log.json"

    /**
     * `patchConsole`: [PpmLog]'s lines at the console level each priority stands for, and render
     * failures as `render_error`; each also kept in [device] under the chat in front ([sid]).
     */
    class LogSink(
        private val trace: TraceClient,
        private val device: DeviceLogBuffer? = null,
        private val sid: () -> String? = { null },
    ) : PpmLog.Sink {
        override fun line(priority: Int, tag: String, message: String, error: Throwable?) {
            val level = when (priority) {
                Log.VERBOSE, Log.DEBUG -> TraceClient.Level.DEBUG
                Log.INFO -> TraceClient.Level.INFO
                Log.WARN -> TraceClient.Level.WARN
                else -> TraceClient.Level.ERROR
            }
            // As `console.warn("[tag] …", error)` arrives: the line, then the error's stack.
            val args = listOfNotNull("[$tag] $message", error?.stackTraceToString())
            trace.console(level, args)
            device?.add(level.wire, tag, message, stack(error), session())
        }

        override fun renderError(where: String, message: String, error: Throwable?) {
            trace.error("render_error", error, message, mapOf("where" to where))
            device?.add("error", where, message, stack(error), session())
        }

        private fun session(): String? = try { sid() } catch (_: Exception) { null }
    }

    private fun stack(error: Throwable?): List<String> = error?.stackTraceToString()?.lines().orEmpty()

    /** [DeviceLogBuffer]'s file, replaced whole by rename as the trace queue's is. */
    class FileDeviceLogStore(private val file: File) : DeviceLogBuffer.Store {
        private val queue = FileTraceQueueStore(file)

        override fun read(): String? = queue.read()

        override fun write(text: String) = queue.write(text)
    }

    /**
     * An uncaught exception on any thread: on disk before the crash goes on as it always did. One a
     * coroutine threw carries the context kotlinx.coroutines attaches, and is reported as the web
     * reports a rejection nobody handled.
     */
    class CrashRecorder(
        private val trace: TraceClient,
        private val device: DeviceLogBuffer? = null,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                val coroutine = coroutineContextOf(error)
                trace.recordFatal(
                    if (coroutine != null) "unhandled_rejection" else "browser_error",
                    error,
                    mapOf("thread" to thread.name, "fatal" to "true", "coroutine" to coroutine, "ua" to PpmHttp.userAgent),
                )
            } catch (_: Throwable) {
                // The crash itself must still happen.
            }
            try {
                device?.add("fatal", "crash", "The app crashed on thread ${thread.name}: $error", stack(error))
                device?.persist()
            } catch (_: Throwable) {
                // Likewise.
            }
            previous?.uncaughtException(thread, error)
        }

        companion object {
            /** What `handleUncaughtCoroutineException` adds as a suppressed `DiagnosticCoroutineContextException`. */
            fun coroutineContextOf(error: Throwable): String? =
                error.suppressed.firstOrNull { it.javaClass.name.endsWith("DiagnosticCoroutineContextException") }
                    ?.let { it.localizedMessage ?: it.toString() }
        }
    }

    /** `pagehide`/`visibilitychange`: the last activity stopping sends and stores what is queued; the first starting tries again. */
    private class Visibility(private val trace: TraceClient, private val device: DeviceLogBuffer) : Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) trace.flushSoon()
        }

        override fun onActivityStopped(activity: Activity) {
            if (started > 0 && --started == 0) {
                trace.onHidden()
                device.persist()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
