package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val DESIGN_JSON_MEDIA = "application/json".toMediaType()

data class DesignProviderOption(
    val id: String,
    val name: String,
    val supportsDesignInstructions: Boolean = true,
)

data class DesignPreviewCapResponse(
    val url: String,
    val token: String,
    val expiresAt: Long,
    val rotated: Boolean,
)

sealed interface DesignTweaksApplyResult {
    data class Applied(
        val gens: Map<String, String>,
        val undoId: String,
    ) : DesignTweaksApplyResult

    data class Stale(
        val file: String,
        val currentGen: String,
        val message: String,
    ) : DesignTweaksApplyResult
}

sealed interface DesignStyleEditResult {
    data class Applied(
        val gen: String,
        val undoId: String,
    ) : DesignStyleEditResult

    data class Rejected(
        val reason: String, // "stale" | "element-moved" | "rate-limited"
        val currentGen: String? = null,
        val message: String,
    ) : DesignStyleEditResult
}

sealed interface DesignUndoEditResult {
    data class Undone(val gen: String) : DesignUndoEditResult
    data class Rejected(
        val reason: String, // "cannot-undo" | "unknown"
        val message: String,
    ) : DesignUndoEditResult
}

data class DesignExportPayload(
    val bytes: ByteArray,
    val filename: String,
    val warningCount: Int,
    val warnings: List<String>,
)

data class DesignExportStreamResult(
    val bytesWritten: Long,
    val filename: String,
    val warningCount: Int,
    val warnings: List<String>,
)

private fun designBasePath(projectName: String): String =
    "/api/project/${ApiClient.escape(projectName)}/designs"

private fun designSlugPath(projectName: String, slug: String): String =
    "${designBasePath(projectName)}/${ApiClient.escape(slug)}"

// ===========================================================================
// 1. Designs CRUD & Design Systems Showcase / Stale
// ===========================================================================

suspend fun ApiClient.designList(projectName: String): JsonElement =
    call("GET", designBasePath(projectName))

suspend fun ApiClient.designGet(projectName: String, slug: String): JsonElement =
    call("GET", designSlugPath(projectName, slug))

suspend fun ApiClient.designCreate(
    projectName: String,
    title: String,
    kind: String = "page",
    system: String? = null,
): JsonElement {
    val body = buildJsonObject {
        put("title", title)
        put("kind", kind)
        if (!system.isNullOrBlank()) put("system", system)
    }
    return call("POST", designBasePath(projectName), body = body)
}

suspend fun ApiClient.designRename(
    projectName: String,
    slug: String,
    title: String,
): JsonElement =
    call(
        "PATCH",
        designSlugPath(projectName, slug),
        body = buildJsonObject { put("title", title) },
    )

suspend fun ApiClient.designDelete(
    projectName: String,
    slug: String,
): JsonElement =
    call(
        "DELETE",
        designSlugPath(projectName, slug),
        query = listOf("confirm" to slug),
    )

suspend fun ApiClient.designGetSystemStale(
    projectName: String,
    id: String,
): JsonElement =
    call("GET", "${designBasePath(projectName)}/systems/${ApiClient.escape(id)}/stale")

suspend fun ApiClient.designOpenSystemShowcase(
    projectName: String,
    id: String,
): JsonElement =
    call("POST", "${designBasePath(projectName)}/systems/${ApiClient.escape(id)}/showcase")

suspend fun ApiClient.designProviders(projectName: String): List<DesignProviderOption> {
    val arr = call("GET", "${chatPath(projectName)}/providers").array.orEmpty()
    return arr.mapNotNull { item ->
        val id = item["id"].string ?: return@mapNotNull null
        val name = item["name"].string ?: id
        val supports = item["supportsDesignInstructions"].bool ?: false
        if (!supports) null else DesignProviderOption(id = id, name = name, supportsDesignInstructions = true)
    }
}

// ===========================================================================
// 2. Preview Capability Token & Entry Gen Check
// ===========================================================================

suspend fun ApiClient.designMintPreview(
    projectName: String,
    slug: String,
    purpose: String = "canvas",
    token: String? = null,
    entry: String? = null,
): DesignPreviewCapResponse {
    val body = buildJsonObject {
        put("projectName", projectName)
        put("slug", slug)
        put("purpose", purpose)
        if (!token.isNullOrBlank()) put("token", token)
        if (!entry.isNullOrBlank()) put("entry", entry)
    }
    val data = call("POST", "/api/design-preview", body = body)
    return DesignPreviewCapResponse(
        url = data["url"].string ?: "",
        token = data["token"].string ?: "",
        expiresAt = data["expiresAt"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: 0L,
        rotated = data["rotated"].bool ?: false,
    )
}

suspend fun ApiClient.designHeadPreviewGen(previewPathOrUrl: String): String? {
    val resolvedUrl = if (previewPathOrUrl.startsWith("http://") || previewPathOrUrl.startsWith("https://")) {
        previewPathOrUrl
    } else {
        baseUrl.newBuilder()
            .encodedPath(baseUrl.encodedPath.trimEnd('/') + "/" + previewPathOrUrl.trimStart('/'))
            .build()
            .toString()
    }
    val req = Request.Builder()
        .url(resolvedUrl)
        .head()
        .header("x-ppm-client", PpmHttp.CLIENT_NAME)
        .build()
    return withContext(Dispatchers.IO) {
        val res = try {
            PpmHttp.client.newCall(req).await()
        } catch (error: IOException) {
            throw PpmError.Unreachable( error.message ?: "Network error")
        }
        res.use { response ->
            if (!response.isSuccessful) {
                throw PpmError.Server(response.code, "Preview answered HTTP ${response.code}.")
            }
            response.header("X-PPM-Gen")
        }
    }
}

// ===========================================================================
// 3. Snapshots History
// ===========================================================================

suspend fun ApiClient.designListHistory(projectName: String, slug: String): JsonElement =
    call("GET", "${designSlugPath(projectName, slug)}/history")

suspend fun ApiClient.designRestoreHistory(
    projectName: String,
    slug: String,
    snapshotId: String,
): JsonElement =
    call("POST", "${designSlugPath(projectName, slug)}/history/${ApiClient.escape(snapshotId)}/restore")

// ===========================================================================
// 4. Comments & Pin Context
// ===========================================================================

suspend fun ApiClient.designListComments(projectName: String, slug: String): JsonElement =
    call("GET", "${designSlugPath(projectName, slug)}/comments")

suspend fun ApiClient.designCreateComment(
    projectName: String,
    slug: String,
    file: String,
    anchor: JsonObject,
    body: String,
): JsonElement =
    call(
        "POST",
        "${designSlugPath(projectName, slug)}/comments",
        body = buildJsonObject {
            put("file", file)
            put("anchor", anchor)
            put("body", body)
        },
    )

suspend fun ApiClient.designUpdateComment(
    projectName: String,
    slug: String,
    id: String,
    resolved: Boolean? = null,
    body: String? = null,
    anchor: JsonObject? = null,
): JsonElement =
    call(
        "PATCH",
        "${designSlugPath(projectName, slug)}/comments/${ApiClient.escape(id)}",
        body = buildJsonObject {
            if (resolved != null) put("resolved", resolved)
            if (body != null) put("body", body)
            if (anchor != null) put("anchor", anchor)
        },
    )

suspend fun ApiClient.designDeleteComment(
    projectName: String,
    slug: String,
    id: String,
): JsonElement =
    call("DELETE", "${designSlugPath(projectName, slug)}/comments/${ApiClient.escape(id)}")

suspend fun ApiClient.designReplyComment(
    projectName: String,
    slug: String,
    id: String,
    body: String,
): JsonElement =
    call(
        "POST",
        "${designSlugPath(projectName, slug)}/comments/${ApiClient.escape(id)}/replies",
        body = buildJsonObject { put("body", body) },
    )

suspend fun ApiClient.designResolveCommentContext(
    projectName: String,
    slug: String,
    anchor: JsonObject,
): JsonElement =
    call(
        "POST",
        "${designSlugPath(projectName, slug)}/comments/context",
        body = buildJsonObject { put("anchor", anchor) },
    )

// ===========================================================================
// 5. Tweaks & Style Edits & Undo
// ===========================================================================

suspend fun ApiClient.designReadTweaks(projectName: String, slug: String): JsonElement =
    call("GET", "${designSlugPath(projectName, slug)}/tweaks")

suspend fun ApiClient.designApplyTweaks(
    projectName: String,
    slug: String,
    entry: String,
    gens: Map<String, String>,
    values: Map<String, String>,
): DesignTweaksApplyResult {
    val body = buildJsonObject {
        put("entry", entry)
        put("gens", buildJsonObject { gens.forEach { (k, v) -> put(k, v) } })
        put("values", buildJsonObject { values.forEach { (k, v) -> put(k, v) } })
    }
    val req = request(
        "POST",
        "${designSlugPath(projectName, slug)}/tweaks",
        emptyList(),
        body.toString().toRequestBody(DESIGN_JSON_MEDIA),
    )
    val response = try {
        PpmHttp.client.newCall(req).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(error.message ?: "Unreachable")
    }
    return withContext(Dispatchers.IO) {
        response.use { res ->
            val text = res.body.string()
            val json = WireJson.parseOrNull(text)
            if (res.code == 409 && json != null) {
                val data = json["data"]
                return@withContext DesignTweaksApplyResult.Stale(
                    file = data["file"].string ?: entry,
                    currentGen = data["currentGen"].string ?: "",
                    message = json["error"].string ?: "Design changed on disk",
                )
            }
            val data = ApiClient.unwrap(res.code, res.header("Content-Type"), text)
            val newGens = data["gens"].obj.orEmpty().mapNotNull { (k, v) ->
                val str = v.string ?: return@mapNotNull null
                k to str
            }.toMap()
            DesignTweaksApplyResult.Applied(
                gens = newGens,
                undoId = data["undoId"].string ?: "",
            )
        }
    }
}

suspend fun ApiClient.designApplyStyle(
    projectName: String,
    slug: String,
    file: String,
    gen: String,
    ppmId: Int,
    tag: String,
    props: Map<String, String>,
): DesignStyleEditResult {
    val body = buildJsonObject {
        put("file", file)
        put("gen", gen)
        put("ppmId", ppmId)
        put("tag", tag)
        put("props", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
    }
    val req = request(
        "POST",
        "${designSlugPath(projectName, slug)}/style",
        emptyList(),
        body.toString().toRequestBody(DESIGN_JSON_MEDIA),
    )
    val response = try {
        PpmHttp.client.newCall(req).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(error.message ?: "Unreachable")
    }
    return withContext(Dispatchers.IO) {
        response.use { res ->
            val text = res.body.string()
            val json = WireJson.parseOrNull(text)
            if (res.code == 429) {
                return@withContext DesignStyleEditResult.Rejected(
                    reason = "rate-limited",
                    message = json?.get("error").string ?: "Too many edits, slow down",
                )
            }
            if (res.code == 409 && json != null) {
                val data = json["data"]
                val currentGen = data["currentGen"].string
                return@withContext DesignStyleEditResult.Rejected(
                    reason = if (!currentGen.isNullOrEmpty()) "stale" else "element-moved",
                    currentGen = currentGen,
                    message = json["error"].string ?: "File changed on disk",
                )
            }
            val data = ApiClient.unwrap(res.code, res.header("Content-Type"), text)
            DesignStyleEditResult.Applied(
                gen = data["gen"].string ?: "",
                undoId = data["undoId"].string ?: "",
            )
        }
    }
}

suspend fun ApiClient.designUndoEdit(
    projectName: String,
    slug: String,
    undoId: String,
): DesignUndoEditResult {
    val body = buildJsonObject { put("undoId", undoId) }
    val req = request(
        "POST",
        "${designSlugPath(projectName, slug)}/undo",
        emptyList(),
        body.toString().toRequestBody(DESIGN_JSON_MEDIA),
    )
    val response = try {
        PpmHttp.client.newCall(req).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(error.message ?: "Unreachable")
    }
    return withContext(Dispatchers.IO) {
        response.use { res ->
            val text = res.body.string()
            val json = WireJson.parseOrNull(text)
            if (res.code == 409 || res.code == 404) {
                return@withContext DesignUndoEditResult.Rejected(
                    reason = if (res.code == 409) "cannot-undo" else "unknown",
                    message = json?.get("error").string ?: "Cannot undo",
                )
            }
            val data = ApiClient.unwrap(res.code, res.header("Content-Type"), text)
            DesignUndoEditResult.Undone(gen = data["gen"].string ?: "")
        }
    }
}

// ===========================================================================
// 6. Canvas Check Response & Exports
// ===========================================================================

suspend fun ApiClient.designSubmitCheckResult(
    projectName: String,
    slug: String,
    requestId: String,
    report: JsonObject,
): Boolean {
    val req = request(
        "POST",
        "${designSlugPath(projectName, slug)}/check/${ApiClient.escape(requestId)}",
        emptyList(),
        report.toString().toRequestBody(DESIGN_JSON_MEDIA),
    )
    val response = try {
        PpmHttp.client.newCall(req).await()
    } catch (error: IOException) {
        return false
    }
    return withContext(Dispatchers.IO) {
        response.use { res ->
            // 404 means another client already answered or request timed out — not an error.
            res.isSuccessful || res.code == 404
        }
    }
}

private val FILENAME_FROM_DISPOSITION_RE = Regex("""filename="([^"]+)"""")
private val SAFE_EXPORT_FILENAME_RE = Regex("^[A-Za-z0-9._-]{1,200}$")

/**
 * Extracts and sanitises the filename from `Content-Disposition` (`filenameFrom` in `design-export-client.ts`).
 * Rejects path separators, `..`, control characters, or names > 200 chars, falling back to [fallback].
 */
fun filenameFromContentDisposition(disposition: String?, fallback: String): String {
    val name = FILENAME_FROM_DISPOSITION_RE.find(disposition.orEmpty())?.groupValues?.getOrNull(1).orEmpty()
    return if (name != "." && name != ".." && SAFE_EXPORT_FILENAME_RE.matches(name)) name else fallback
}

private fun parseExportWarnings(warnListRaw: String): List<String> {
    if (warnListRaw.isBlank()) return emptyList()
    return runCatching {
        val decoded = java.net.URLDecoder.decode(warnListRaw, "UTF-8")
        WireJson.parseOrNull(decoded)?.array?.mapNotNull { it.string }?.take(50).orEmpty()
    }.getOrDefault(emptyList())
}

suspend fun ApiClient.designStreamExport(
    projectName: String,
    slug: String,
    kind: String, // "zip" | "html"
    entry: String? = null,
    writeTo: (filename: String, java.io.InputStream) -> Long,
): DesignExportStreamResult {
    val query = if (kind == "html" && !entry.isNullOrBlank() && entry != "index.html") {
        listOf("entry" to entry)
    } else {
        emptyList()
    }
    val req = request(
        "GET",
        "${designSlugPath(projectName, slug)}/export/${ApiClient.escape(kind)}",
        query,
    )
    val response = try {
        PpmHttp.client.newCall(req).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(error.message ?: "Unreachable")
    }
    return withContext(Dispatchers.IO) {
        response.use { res ->
            if (!res.isSuccessful) {
                val text = res.body.string()
                ApiClient.unwrap(res.code, res.header("Content-Type"), text)
            }
            val disp = res.header("Content-Disposition")
            val defaultName = if (kind == "zip") "$slug.zip" else "$slug.html"
            val filename = filenameFromContentDisposition(disp, defaultName)
            val warnCount = res.header("X-PPM-Export-Warnings")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val warnings = parseExportWarnings(res.header("X-PPM-Export-Warning-List").orEmpty())
            val bytesWritten = res.body.byteStream().use { input ->
                writeTo(filename, input)
            }
            DesignExportStreamResult(
                bytesWritten = bytesWritten,
                filename = filename,
                warningCount = warnCount,
                warnings = warnings,
            )
        }
    }
}

suspend fun ApiClient.designDownloadExport(
    projectName: String,
    slug: String,
    kind: String, // "zip" | "html"
    entry: String? = null,
): DesignExportPayload {
    val out = java.io.ByteArrayOutputStream()
    val streamed = designStreamExport(projectName, slug, kind, entry) { _, input ->
        input.copyTo(out)
    }
    return DesignExportPayload(
        bytes = out.toByteArray(),
        filename = streamed.filename,
        warningCount = streamed.warningCount,
        warnings = streamed.warnings,
    )
}
