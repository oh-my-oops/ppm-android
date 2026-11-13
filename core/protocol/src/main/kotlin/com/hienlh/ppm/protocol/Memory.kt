package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/*
 * Settings → Memory's wire types (`src/web/lib/api-memory.ts` and the `embedding` block that
 * `memory-embedding-source.tsx` reads field by field): PPM's shared memory store, where it was
 * copied from, and where memory vectors come from.
 */

/** One provider's copy into the store (`MemoryProviderStatus`). */
data class MemoryProviderStatus(
    val count: Int = 0,
    val syncedAt: String? = null,
    val error: String? = null,
    val skipped: List<String> = emptyList(),
    val redacted: Int = 0,
) {
    companion object {
        fun from(value: JsonElement?): MemoryProviderStatus? {
            if (value.obj == null) return null
            return MemoryProviderStatus(
                count = value["count"].int ?: 0,
                syncedAt = value["syncedAt"].string,
                error = value["error"].string,
                skipped = value["skipped"].array.orEmpty().mapNotNull { it.string },
                redacted = value["redacted"].int ?: 0,
            )
        }
    }
}

/**
 * The local model's install (`LocalInstallStatus`): [state] is `not-installed`, `installing`
 * (with [step], [bytes] of [totalBytes]), `ready` or `failed` (with [reason]).
 */
data class MemoryInstallStatus(
    val state: String = NOT_INSTALLED,
    val step: String? = null,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val reason: String = "",
) {
    companion object {
        const val NOT_INSTALLED = "not-installed"
        const val INSTALLING = "installing"
        const val READY = "ready"
        const val FAILED = "failed"

        /** `asInstall`: anything without a state reads as not installed. */
        fun from(value: JsonElement?): MemoryInstallStatus {
            val state = value["state"].string ?: return MemoryInstallStatus()
            return MemoryInstallStatus(
                state = state,
                step = value["step"].string,
                bytes = value["bytes"].double?.toLong() ?: 0,
                totalBytes = value["totalBytes"].double?.toLong() ?: 0,
                reason = value["reason"].string.orEmpty(),
            )
        }
    }
}

/** `status.embedding`: the saved source (never the key, only whether there is one), readiness and progress. */
data class MemoryEmbedding(
    /** `off`, `local` or `api`. */
    val source: String = "off",
    val apiUrl: String = "",
    val apiModel: String = "",
    val hasApiKey: Boolean = false,
    val ready: Boolean = false,
    val reason: String = "",
    val vectors: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val install: MemoryInstallStatus = MemoryInstallStatus(),
) {
    companion object {
        fun from(value: JsonElement?): MemoryEmbedding {
            val source = value["source"].string
            return MemoryEmbedding(
                source = if (source == "local" || source == "api") source else "off",
                apiUrl = value["api_url"].string.orEmpty(),
                apiModel = value["api_model"].string.orEmpty(),
                hasApiKey = value["has_api_key"].bool == true,
                ready = value["ready"].bool == true,
                reason = value["reason"].string.orEmpty(),
                vectors = value["vectors"].double?.toInt() ?: 0,
                total = value["total"].double?.toInt() ?: 0,
                running = value["running"].bool == true,
                install = MemoryInstallStatus.from(value["install"]),
            )
        }
    }
}

/** `GET /api/memory/status` (`MemoryStatus`). */
data class MemoryStatus(
    val enabled: Boolean,
    /** The folder memories live in, as the server spells it. */
    val root: String,
    val projectCount: Int = 0,
    val globalCount: Int = 0,
    val archivedCount: Int = 0,
    /** By provider id: `claude`, `codex`, `antigravity`. */
    val providers: Map<String, MemoryProviderStatus> = emptyMap(),
    val embedding: MemoryEmbedding = MemoryEmbedding(),
) {
    companion object {
        fun from(value: JsonElement?): MemoryStatus? {
            if (value.obj == null) return null
            val counts = value["counts"]
            return MemoryStatus(
                enabled = value["enabled"].bool == true,
                root = value["root"].string.orEmpty(),
                projectCount = counts["project"].int ?: 0,
                globalCount = counts["global"].int ?: 0,
                archivedCount = counts["archived"].int ?: 0,
                providers = value["providers"].obj.orEmpty().mapNotNull { (id, v) -> MemoryProviderStatus.from(v)?.let { id to it } }.toMap(),
                embedding = MemoryEmbedding.from(value["embedding"]),
            )
        }
    }
}

/** `POST /api/memory/embedding/test`: [dims] and [ms] when it worked, else the endpoint's [error]. */
data class MemoryEmbeddingTest(val ok: Boolean, val dims: Int = 0, val ms: Long = 0, val error: String = "") {
    companion object {
        fun from(value: JsonElement?): MemoryEmbeddingTest =
            if (value["ok"].bool == true) {
                MemoryEmbeddingTest(true, value["dims"].double?.toInt() ?: 0, value["ms"].double?.toLong() ?: 0)
            } else {
                MemoryEmbeddingTest(false, error = value["error"].string.orEmpty())
            }
    }
}
