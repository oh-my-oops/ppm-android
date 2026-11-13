package com.hienlh.ppm.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The HTTP clients every server shares (one connection pool). */
object PpmHttp {
    /** Tells the server what kind of client is asking; the web sends `web`. */
    const val CLIENT_NAME = "android"

    /** How long a GET may wait with nothing arriving, as the web's api-client allows for headers. */
    const val GET_TIMEOUT_SECONDS = 30L

    /** A POST may take as long as the work behind it (the web gives one no timeout at all). */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    val getClient: OkHttpClient = client.newBuilder()
        .readTimeout(GET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** An event stream must not time out between frames. */
    val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Sockets: no read timeout; the owners detect a silent socket themselves (45 s). */
    val socketClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
}

/** Calls whose answer nobody waits for, such as giving a stream's lease back. */
internal object Background {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Runs the call, cancelling it with the coroutine. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // Dropped because the coroutine was cancelled: closed off its thread, which may be the UI thread,
                // where Android refuses the socket work closing can do (NetworkOnMainThreadException).
                continuation.resume(response) { _, value, _ -> Background.scope.launch { value.close() } }
            }
        },
    )
}

/** What to tell the user when the request never got an answer. */
internal fun describe(error: IOException): String = when (error) {
    is UnknownHostException -> "The address does not resolve."
    is ConnectException -> "Nothing answers at that address and port."
    is NoRouteToHostException -> "No route to that address."
    is SocketTimeoutException -> "The server did not answer in time."
    else -> error.message ?: error.javaClass.simpleName
}
