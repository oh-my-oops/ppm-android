package com.hienlh.ppm.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Request
import java.io.IOException

/** The server keeps its projects in [order] from now on (`reorderProjects`); a name it does not have is ignored. */
suspend fun ApiClient.reorderProjects(order: List<String>) {
    call("PATCH", "/api/projects/reorder", body = buildJsonObject { put("order", JsonArray(order.map(::JsonPrimitive))) })
}

/**
 * A project's uploaded avatar, WebP (`GET /api/projects/:name/image`). [version] is the project's
 * `image`, the file's own name: the web puts it in the address, so a new upload is a new address.
 */
suspend fun ApiClient.projectImage(name: String, version: String): ByteArray =
    bytes(request("GET", "/api/projects/${ApiClient.escape(name)}/image", listOf("v" to version)))

/** The body of a GET that answers a file rather than JSON; an error answer is thrown as JSON's is. */
internal suspend fun ApiClient.bytes(request: Request): ByteArray {
    val response = try {
        PpmHttp.getClient.newCall(request).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(describe(error))
    }
    return withContext(Dispatchers.IO) {
        response.use {
            try {
                if (!it.isSuccessful) {
                    ApiClient.unwrap(it.code, it.header("Content-Type"), it.body.string())
                    throw PpmError.BadResponse("The server answered HTTP ${it.code}.")
                }
                it.body.bytes()
            } catch (error: IOException) {
                throw PpmError.Unreachable(describe(error))
            }
        }
    }
}
