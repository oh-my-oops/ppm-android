package com.hienlh.ppm.media

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.rawMediaUrl
import com.hienlh.ppm.state.media.MediaSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** The caller chooses/opens a destination first and owns its stream and partial-file cleanup. */
suspend fun downloadMedia(api: ApiClient, source: MediaSource, output: OutputStream): Unit = coroutineScope {
    val call = mediaHttp.newCall(mediaRequest(api, api.rawMediaUrl(source.project, source.path)))
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("The server returned an empty response.")
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    } catch (e: IOException) {
        coroutineContext.ensureActive()
        throw e
    } finally { cancellation.cancel() }
}
