package com.hienlh.ppm.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** A file as the server serves it raw: its bytes and the type it was served as. */
class RawFile(val bytes: ByteArray, val contentType: String?)

/** A path that names a file from the root: `/…`, or a Windows drive. */
private val ABSOLUTE_PATH = Regex("""^(/|[A-Za-z]:[/\\])""")

/**
 * A file's bytes, read as `useBlobUrl` reads them: an absolute path from wherever the server lets
 * it be read (`GET /api/fs/raw`, which serves an image from any path), anything else from inside
 * [project] (`GET /api/project/:name/files/raw`). A refusal keeps its HTTP status, which the web
 * shows ("preview unavailable · 403").
 */
suspend fun ApiClient.rawFile(project: String, path: String): RawFile {
    val request = if (ABSOLUTE_PATH.containsMatchIn(path)) {
        request("GET", "/api/fs/raw", listOf("path" to path))
    } else {
        request("GET", "/api/project/${ApiClient.escape(project)}/files/raw", listOf("path" to path))
    }
    val response = try {
        PpmHttp.getClient.newCall(request).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(describe(error))
    }
    return withContext(Dispatchers.IO) {
        response.use {
            try {
                if (!it.isSuccessful) {
                    // An answer that is not PPM's JSON (a proxy's page) still keeps its status.
                    try {
                        ApiClient.unwrap(it.code, it.header("Content-Type"), it.body.string())
                    } catch (_: PpmError.BadResponse) {
                    }
                    throw PpmError.Server(it.code, "The server answered HTTP ${it.code}.")
                }
                RawFile(it.body.bytes(), it.header("Content-Type"))
            } catch (error: IOException) {
                throw PpmError.Unreachable(describe(error))
            }
        }
    }
}

/**
 * A picture a message shows from the web (`<img src="https://…">`): fetched as the browser
 * fetches one, from wherever it is, with nothing of the server's sent along.
 */
suspend fun webFile(url: String): RawFile {
    val request = try {
        Request.Builder().url(url).header("Accept", "image/*,*/*;q=0.8").get().build()
    } catch (_: IllegalArgumentException) {
        throw PpmError.InvalidUrl()
    }
    val response = try {
        PpmHttp.getClient.newCall(request).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(describe(error))
    }
    return withContext(Dispatchers.IO) {
        response.use {
            try {
                if (!it.isSuccessful) throw PpmError.Server(it.code, "The server answered HTTP ${it.code}.")
                RawFile(it.body.bytes(), it.header("Content-Type"))
            } catch (error: IOException) {
                throw PpmError.Unreachable(describe(error))
            }
        }
    }
}
