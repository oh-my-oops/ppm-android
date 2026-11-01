package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

const val PROJECT_UPLOAD_MAX_FILES = 20
const val PROJECT_UPLOAD_MAX_BYTES = 50L * 1024 * 1024

/** Bytes of file data, excluding multipart framing. Null total means indeterminate progress. */
data class ProjectTransferProgress(val bytes: Long, val total: Long?)
data class ProjectUploadedFile(val name: String, val path: String, val size: Long)

/** Open lazily on the IO dispatcher. The transfer closes every opened stream. */
data class ProjectUploadSource(val name: String, val mimeType: String?, val size: Long?, val open: () -> InputStream)

/** The server's upload loop is not transactional; a failed response cannot enumerate its prefix. */
class ProjectUploadFailure(val mayHaveWritten: Boolean, cause: Exception) : Exception(cause.message, cause)

internal const val TRANSFER_BUFFER = 64 * 1024
private val transferHttp by lazy {
    PpmHttp.client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
}

/**
 * Call ONLY after the destination was chosen/opened. Mint a fresh 30-second token, then start
 * the raw/zip request immediately. No token is cached or exposed, and no Range request is sent.
 * Owns [destination], closing it on success, error and cancellation. Progress runs on IO.
 */
suspend fun ApiClient.downloadProjectFile(
    project: String, path: String, directory: Boolean, destination: OutputStream,
    progress: (ProjectTransferProgress) -> Unit = {},
): Long = withContext(Dispatchers.IO) {
    destination.use { output ->
        val route = "/api/project/${ApiClient.escape(project)}/files"
        val token = call("POST", "$route/download/token")["token"].string?.takeIf { it.isNotBlank() }
            ?: throw PpmError.BadResponse("The server did not provide a download token.")
        val query = buildList {
            // The zip endpoint rejects an empty path; '.' is the project root.
            add("path" to if (directory && path.isEmpty()) "." else path)
            if (!directory) add("download" to "true")
            add("dl_token" to token)
        }
        val request = request("GET", if (directory) "$route/download/zip" else "$route/raw", query)
            .newBuilder().removeHeader("Authorization").header("Accept", "application/octet-stream").build()
        transfer(request, AtomicReference<Closeable?>(output)) { response ->
            if (response.code == 401) throw PpmError.Server(401, "The download token was rejected. Start the download again.")
            if (response.code != 200) throwTransferError(response)
            val total = if (directory) null else response.body.contentLength().takeIf { it >= 0 }
            var copied = 0L
            progress(ProjectTransferProgress(0, total))
            val buffer = ByteArray(TRANSFER_BUFFER)
            response.body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    copied += read
                    progress(ProjectTransferProgress(copied, total))
                }
            }
            if (total != null && copied != total) throw IOException("The download ended before all bytes arrived.")
            output.flush()
            copied
        }
    }
}

/**
 * One multipart POST, targetDir plus repeated files parts; existing names are overwritten.
 * Count/known sizes are checked before opening anything; unknown sizes are capped while read.
 * A request failure can leave earlier server writes intact: refresh targetDir even on failure
 * or cancellation. Never retry this POST automatically. Progress runs on the HTTP IO thread.
 */
suspend fun ApiClient.uploadProjectFiles(
    project: String, targetDirectory: String, files: List<ProjectUploadSource>,
    progress: (ProjectTransferProgress) -> Unit = {},
): List<ProjectUploadedFile> = withContext(Dispatchers.IO) {
    require(files.size in 1..PROJECT_UPLOAD_MAX_FILES) { "Choose between 1 and $PROJECT_UPLOAD_MAX_FILES files." }
    files.forEach {
        require(it.name.isNotBlank()) { "An upload is missing its filename." }
        require(it.size == null || it.size in 0..PROJECT_UPLOAD_MAX_BYTES) { "File \"${it.name}\" exceeds the 50 MB limit or has an invalid size." }
    }
    val context = currentCoroutineContext()
    val active = AtomicReference<Closeable?>()
    val total = if (files.all { it.size != null }) files.sumOf { it.size!! } else null
    var sent = 0L
    val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("targetDir", targetDirectory)
    files.forEach { file ->
        multipart.addFormDataPart("files", file.name, object : RequestBody() {
            override fun contentType() = file.mimeType?.toMediaTypeOrNull()
            override fun contentLength() = file.size ?: -1
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                context.ensureActive()
                val input = file.open()
                active.set(input)
                try {
                    input.use {
                        var readFile = 0L
                        val buffer = ByteArray(TRANSFER_BUFFER)
                        while (true) {
                            context.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            readFile += read
                            if (readFile > PROJECT_UPLOAD_MAX_BYTES) throw IOException("File \"${file.name}\" exceeds the 50 MB limit.")
                            sink.write(buffer, 0, read)
                            sent += read
                            progress(ProjectTransferProgress(sent, total))
                        }
                        if (file.size != null && readFile != file.size) throw IOException("File \"${file.name}\" changed size while uploading.")
                    }
                } finally { active.compareAndSet(input, null) }
            }
        })
    }
    val request = request("POST", "/api/project/${ApiClient.escape(project)}/files/upload", body = multipart.build())
    progress(ProjectTransferProgress(0, total))
    try {
        transfer(request, active) { response ->
            val data = ApiClient.unwrap(response.code, response.header("Content-Type"), limitedResponse(response))
            val uploaded = data["uploaded"].array ?: throw PpmError.BadResponse("The server did not return the uploaded files.")
            uploaded.map { item ->
                ProjectUploadedFile(item["name"].string ?: throw PpmError.BadResponse("An uploaded filename is missing."),
                    item["path"].string ?: throw PpmError.BadResponse("An uploaded path is missing."),
                    item["size"].long ?: throw PpmError.BadResponse("An uploaded size is missing."))
            }
        }
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { throw ProjectUploadFailure(mayHaveWritten = true, error) }
}

/** Keep cancellation attached beyond response headers, including a blocked read/write. */
internal suspend fun <T> transfer(request: Request, active: AtomicReference<Closeable?>, read: suspend (Response) -> T): T = coroutineScope {
    val call = transferHttp.newCall(request)
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally { call.cancel(); runCatching { active.get()?.close() } }
    }
    try {
        call.await().use { read(it) }
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        // On ordinary completion the owning use{} must observe close/commit errors. The watcher
        // closes only on cancellation, where it must interrupt a blocked provider or socket.
        if (currentCoroutineContext().isActive) active.set(null)
        cancellation.cancel()
    }
}

internal fun limitedResponse(response: Response): String = response.body.byteStream().use {
    val bytes = ByteArray(TRANSFER_BUFFER)
    var count = 0
    while (count < bytes.size) {
        val read = it.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) continue
        count += read
    }
    String(bytes, 0, count, Charsets.UTF_8)
}

internal fun throwTransferError(response: Response): Nothing {
    ApiClient.unwrap(response.code, response.header("Content-Type"), limitedResponse(response))
    throw PpmError.Server(response.code, "The download answered HTTP ${response.code}.")
}
