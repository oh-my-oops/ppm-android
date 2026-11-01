package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

data class HostTransferProgress(val bytes: Long, val total: Long?)
data class HostUploadedFile(val path: String, val size: Long)

/** A repeatable opener: a collision retry opens a fresh stream. Never holds the file bytes. */
data class HostUploadSource(val relativePath: String, val size: Long?, val open: () -> InputStream, val mimeType: String? = null)

/**
 * File-only host download. Choose/open SAF destination first. Each attempt mints a new,
 * single-use, path-bound token; never reuse the project download token or send a Range.
 * Owns destination, including failure/cancellation. Progress callbacks run on IO.
 */
suspend fun ApiClient.downloadHostFile(path: String, destination: OutputStream,
                                      progress: (HostTransferProgress) -> Unit = {}): Long = withContext(Dispatchers.IO) {
    destination.use { output ->
        val token = call("POST", "/api/fs/download/token", body = buildJsonObject { put("path", path) })["token"].string
            ?.takeIf { it.isNotBlank() } ?: throw PpmError.BadResponse("The server did not provide a download token.")
        val request = request("GET", "/api/fs/raw", listOf("path" to path, "download" to "true", "dl_token" to token))
            .newBuilder().removeHeader("Authorization").header("Accept", "application/octet-stream").build()
        transfer(request, AtomicReference<Closeable?>(output)) { response ->
            if (response.code == 401) throw PpmError.Server(401, "The download token was rejected. Start the download again.")
            if (response.code != 200) throwTransferError(response)
            val total = response.body.contentLength().takeIf { it >= 0 }
            var copied = 0L
            progress(HostTransferProgress(0, total))
            response.body.byteStream().use { input ->
                val buffer = ByteArray(TRANSFER_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    copied += read
                    progress(HostTransferProgress(copied, total))
                }
            }
            if (total != null && copied != total) throw IOException("The download ended before all bytes arrived.")
            output.flush()
            copied
        }
    }
}

/**
 * Raw streaming PUT, without the project multipart/count/size limits. EEXIST is returned to
 * the queue for a user decision. Never retry automatically; server commits via a temporary file.
 */
suspend fun ApiClient.uploadHostFile(path: String, source: HostUploadSource, overwrite: Boolean = false,
                                    progress: (HostTransferProgress) -> Unit = {}): HostUploadedFile = withContext(Dispatchers.IO) {
    require(source.size == null || source.size >= 0) { "Invalid upload size." }
    val context = currentCoroutineContext()
    val active = AtomicReference<Closeable?>()
    val body = object : RequestBody() {
        override fun contentType() = (source.mimeType ?: "application/octet-stream").toMediaTypeOrNull()
        override fun contentLength() = source.size ?: -1
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            context.ensureActive()
            val input = source.open()
            active.set(input)
            try {
                input.use {
                    var copied = 0L
                    val buffer = ByteArray(TRANSFER_BUFFER)
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        sink.write(buffer, 0, read)
                        copied += read
                        progress(HostTransferProgress(copied, source.size))
                    }
                    if (source.size != null && copied != source.size) throw IOException("The file changed size while uploading.")
                }
            } finally { active.compareAndSet(input, null) }
        }
    }
    progress(HostTransferProgress(0, source.size))
    transfer(request("PUT", "/api/fs/upload", listOf("path" to path, "overwrite" to if (overwrite) "1" else "0"), body), active) { response ->
        val data = ApiClient.unwrap(response.code, response.header("Content-Type"), limitedResponse(response))
        HostUploadedFile(data["path"].string ?: throw PpmError.BadResponse("The uploaded path is missing."),
            data["size"].long ?: throw PpmError.BadResponse("The uploaded size is missing."))
    }
}
