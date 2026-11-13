package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.MemoryEmbeddingTest
import com.hienlh.ppm.protocol.MemoryInstallStatus
import com.hienlh.ppm.protocol.MemoryStatus
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * `/api/memory` — Settings → Memory (`src/web/lib/api-memory.ts` and `memory-embedding-source.tsx`):
 * what PPM's memory store holds, Sync now, the on/off switch, the embedding source and the local
 * model's install. Changes arrive as `memory:changed` / `memory:embed-install` on `/ws/global`.
 */

/** `GET /api/memory/status`: the counts and provider rows are for [project] when given. */
suspend fun ApiClient.memoryStatus(project: String?): MemoryStatus =
    MemoryStatus.from(call("GET", "/api/memory/status", if (project != null) listOf("project" to project) else emptyList()))
        ?: throw PpmError.BadResponse("The memory status could not be read.")

/** `POST /api/memory/sync`: syncs every project now; refused (409) while memory is off. */
suspend fun ApiClient.syncMemoryNow() {
    call("POST", "/api/memory/sync")
}

/** `PUT /api/memory/settings {enabled}`: answers the switch as saved. */
suspend fun ApiClient.setMemoryEnabled(enabled: Boolean): Boolean =
    call("PUT", "/api/memory/settings", body = buildJsonObject { put("enabled", enabled) })["enabled"].bool == true

/**
 * `PUT /api/memory/settings {embedding}`: [fields] laid over the stored embedding settings
 * (`source`, `api_url`, `api_model`, `api_key`; a missing key keeps the stored one).
 */
suspend fun ApiClient.setMemoryEmbedding(fields: JsonObject) {
    call("PUT", "/api/memory/settings", body = buildJsonObject { put("embedding", fields) })
}

/** `POST /api/memory/embedding/install`: starts the local model's install; progress follows as `memory:embed-install`. */
suspend fun ApiClient.installMemoryModel(): MemoryInstallStatus =
    MemoryInstallStatus.from(call("POST", "/api/memory/embedding/install"))

/** `DELETE /api/memory/embedding/install`: cancels a running install, or removes the installed model. */
suspend fun ApiClient.removeMemoryModel(): MemoryInstallStatus =
    MemoryInstallStatus.from(call("DELETE", "/api/memory/embedding/install"))

/** `POST /api/memory/embedding/test`: embeds one sentence with [fields] laid over the stored settings. */
suspend fun ApiClient.testMemoryEmbedding(fields: JsonObject): MemoryEmbeddingTest =
    MemoryEmbeddingTest.from(call("POST", "/api/memory/embedding/test", body = fields))
