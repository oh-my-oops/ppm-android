package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.FileReplaceAnswer
import com.hienlh.ppm.protocol.FileReplaceFile
import com.hienlh.ppm.protocol.FileSearchAnswer
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.fileReplaceBody
import com.hienlh.ppm.protocol.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The search host answers within 30 s (`TIME_LIMIT_MS`) and the route allows 40, longer than the
 * shared GET client's 30 s: this one waits 45. Cancelling the call aborts the server's search.
 */
private val searchClient: OkHttpClient by lazy { PpmHttp.client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build() }

/**
 * `GET /files/search` (`search-client.ts` `searchUrl`). The bounded content search, never the
 * recursive tree or file index. Preserves the server's error status (400 = the engine refused the
 * pattern). The search host's bytes come back without the `{ ok, data }` envelope; an older
 * server's do not, and both are read.
 */
suspend fun ApiClient.searchProjectFiles(project: String, options: FileSearchOptions): FileSearchAnswer {
    val query = buildList {
        add("q" to options.query)
        add("caseSensitive" to options.caseSensitive.toString())
        add("wholeWord" to options.wholeWord.toString())
        add("regex" to options.regex.toString())
        if (options.include.isNotBlank()) add("include" to options.include.trim())
        if (options.exclude.isNotBlank()) add("exclude" to options.exclude.trim())
        add("useIgnore" to options.useIgnore.toString())
        options.replace?.let {
            // An empty replace text in an open field is still a replacement: it deletes the matches.
            add("replace" to it)
            add("preserveCase" to options.preserveCase.toString())
        }
    }
    return FileSearchAnswer.from(searchHostCall("GET", "/api/project/${ApiClient.escape(project)}/files/search", query))
        ?: throw PpmError.BadResponse("The file search results could not be read.")
}

/** `POST /files/replace`: the server checks each match is still where the search found it, then writes. */
suspend fun ApiClient.replaceInProjectFiles(project: String, files: List<FileReplaceFile>): FileReplaceAnswer =
    FileReplaceAnswer.from(searchHostCall("POST", "/api/project/${ApiClient.escape(project)}/files/replace", body = fileReplaceBody(files)))
        ?: throw PpmError.BadResponse("The replace report could not be read.")

private suspend fun ApiClient.searchHostCall(
    method: String,
    path: String,
    query: List<Pair<String, String>> = emptyList(),
    body: JsonElement? = null,
): JsonElement {
    val request = request(method, path, query, body?.toString()?.toRequestBody("application/json".toMediaType()))
    val response = try {
        searchClient.newCall(request).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(describe(error))
    }
    return withContext(Dispatchers.IO) {
        response.use {
            val text = try {
                it.body.string()
            } catch (error: IOException) {
                throw PpmError.Unreachable(describe(error))
            }
            bareOrEnvelope(it.code, it.header("Content-Type"), text)
        }
    }
}

/** A success body with or without the `{ ok, data }` envelope; every refusal goes through [ApiClient.unwrap]. */
internal fun bareOrEnvelope(status: Int, contentType: String?, text: String): JsonElement {
    val json = WireJson.parseOrNull(text)
    if (status in 200..299 && json is JsonObject && json["ok"] == null) return json
    return ApiClient.unwrap(status, contentType, text)
}
