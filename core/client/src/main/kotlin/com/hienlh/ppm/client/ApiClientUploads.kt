package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.UploadedFile
import com.hienlh.ppm.protocol.array
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import java.io.File
import java.util.UUID

/** A file to send with a chat message. */
class Upload(val name: String, val mimeType: String, val data: ByteArray)

/**
 * `POST /api/project/:p/chat/upload`, multipart with every file under `files`, as the composer
 * sends them: where the server saved each one. [progress] gets the share of the body sent so far, 0…1.
 */
suspend fun ApiClient.upload(
    project: String,
    files: List<Upload>,
    progress: ((Double) -> Unit)? = null,
): List<UploadedFile> {
    val boundary = "ppm-${UUID.randomUUID()}"
    val body = multipart(files, boundary)
    val media = "multipart/form-data; boundary=$boundary".toMediaTypeOrNull()
    val request = request("POST", "${chatPath(project)}/upload", body = ProgressBody(body, media, progress))
    return send(PpmHttp.client, request).array.orEmpty().mapNotNull(UploadedFile::from)
}

/**
 * One file sent as [upload] sends it, read from disk while it goes out rather than held in memory
 * — a video off the camera is tens of megabytes. Where the server saved it.
 */
suspend fun ApiClient.uploadFile(project: String, name: String, mimeType: String, file: File): UploadedFile? {
    val boundary = "ppm-${UUID.randomUUID()}"
    val media = "multipart/form-data; boundary=$boundary".toMediaTypeOrNull()
    val request = request("POST", "${chatPath(project)}/upload", body = FilePartBody(file, partHead(name, mimeType, boundary), boundary, media))
    return send(PpmHttp.client, request).array.orEmpty().firstNotNullOfOrNull(UploadedFile::from)
}

/**
 * The bytes of a file sent with a message (`GET /api/project/:p/chat/uploads/:filename`), named by
 * the path the message carries. The route is under a project, but every upload is reachable from any.
 */
suspend fun ApiClient.uploadedFile(project: String, path: String): ByteArray =
    bytes(request("GET", "${chatPath(project)}/uploads/${ApiClient.escape(path.split('/', '\\').last())}"))

/** Discards an upload that was never sent, as removing an attachment from the composer does. */
suspend fun ApiClient.deleteUpload(project: String, path: String) {
    val filename = path.substringAfterLast('/')
    call("DELETE", "${chatPath(project)}/uploads/${ApiClient.escape(filename)}")
}

internal fun multipart(files: List<Upload>, boundary: String): ByteArray {
    val body = Buffer()
    for (file in files) {
        body.writeUtf8(partHead(file.name, file.mimeType, boundary))
        body.write(file.data)
        body.writeUtf8("\r\n")
    }
    body.writeUtf8("--$boundary--\r\n")
    return body.readByteArray()
}

/** What goes ahead of one file's bytes in the body. */
private fun partHead(fileName: String, mimeType: String, boundary: String): String {
    // A quote or a line break in the name would end the header early.
    val name = fileName.replace("\"", "%22").replace("\r", "").replace("\n", "")
    return "--$boundary\r\n" +
        "Content-Disposition: form-data; name=\"files\"; filename=\"$name\"\r\n" +
        "Content-Type: $mimeType\r\n\r\n"
}

/** A one-file multipart body whose file is read as it is written. */
private class FilePartBody(
    private val file: File,
    private val head: String,
    private val boundary: String,
    private val media: MediaType?,
) : RequestBody() {
    private val tail = "\r\n--$boundary--\r\n"

    override fun contentType() = media

    override fun contentLength() = head.toByteArray().size + file.length() + tail.toByteArray().size

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(head)
        file.source().use { sink.writeAll(it) }
        sink.writeUtf8(tail)
    }
}

private class ProgressBody(
    private val bytes: ByteArray,
    private val media: MediaType?,
    private val report: ((Double) -> Unit)?,
) : RequestBody() {
    override fun contentType() = media

    override fun contentLength() = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        if (report == null || bytes.isEmpty()) {
            sink.write(bytes)
            return
        }
        var sent = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                report.invoke(sent.toDouble() / bytes.size)
            }
        }.buffer()
        counting.write(bytes)
        counting.flush()
    }
}
