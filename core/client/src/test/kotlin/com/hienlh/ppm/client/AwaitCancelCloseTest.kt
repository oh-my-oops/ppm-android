package com.hienlh.ppm.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A request whose coroutine is cancelled after the answer arrived but before it resumed (a screen left
 * while its file loads) closes that answer. Closing it can touch the socket, and Android refuses network
 * on the main thread, so it must not be closed on the thread the coroutine runs on: on the LG V40 it was
 * the UI thread, and the app died with NetworkOnMainThreadException (`Http.kt:65`).
 */
class AwaitCancelCloseTest {
    private val server = MockWebServer()
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "ui") }

    @After
    fun tearDown() {
        ui.shutdownNow()
        server.close()
    }

    @Test
    fun aCancelledAnswerIsClosedOffTheCoroutinesThread() = runBlocking {
        server.enqueue(MockResponse.Builder().body("x".repeat(64 * 1024)).build())
        server.start()
        val started = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var closedOn: String? = null
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) = started.countDown()
                override fun responseHeadersEnd(call: Call, response: Response) = answered.countDown()
            })
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                val source = object : ForwardingSource(body.source()) {
                    override fun close() {
                        closedOn = Thread.currentThread().name
                        closed.countDown()
                        super.close()
                    }
                }
                response.newBuilder().body(source.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
            }
            .build()
        val call = client.newCall(Request.Builder().url(server.url("/raw")).build())
        val dispatcher = ui.asCoroutineDispatcher()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { call.await() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // Hold the coroutine's thread so the answer is waiting to resume when the coroutine is cancelled.
        val gate = CountDownLatch(1)
        ui.execute { gate.await(10, TimeUnit.SECONDS) }
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        job.cancel()
        gate.countDown()
        job.join()

        assertTrue("the answer is closed", closed.await(5, TimeUnit.SECONDS))
        // Debug builds of coroutines name the thread "ui @coroutine#2".
        assertFalse("closed on the coroutine's thread: $closedOn", closedOn.orEmpty().startsWith("ui"))
    }
}
