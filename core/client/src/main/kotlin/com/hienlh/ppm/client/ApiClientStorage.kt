package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** The newest snapshot of this server's database (`StorageBackups.newest`); [mtimeMs] as `fs.stat` gives it. */
data class StorageNewestBackup(val name: String, val mtimeMs: Long)

/** Another database's snapshots in the same folder (`StorageBackups.others`). */
data class StorageOtherBackups(val dbStem: String, val count: Long, val totalBytes: Long)

/** The snapshot folder (`StorageBackups` of `src/shared/storage-settings.ts`). */
data class StorageBackups(
    val dir: String,
    val dbStem: String,
    val count: Long,
    val totalBytes: Long,
    val newest: StorageNewestBackup?,
    val others: List<StorageOtherBackups>,
)

/** `session-trace.db` and the limits its daily cleanup applies (`StorageSessionTrace`). */
data class StorageSessionTrace(
    val path: String,
    val sizeBytes: Long,
    val oldestTs: Long?,
    val retentionDays: Long,
    val maxSizeMb: Long,
)

/** `GET /api/settings/storage` (`StorageOverview`): the app database, its backups and the session trace. */
data class StorageOverview(
    val appDbPath: String,
    val appDbSizeBytes: Long,
    val backups: StorageBackups,
    val sessionTrace: StorageSessionTrace,
)

/** `GET /api/settings/query-audit` (`QueryAuditSettings`): the SQL history's limits and size. */
data class QueryAuditUsage(val retentionDays: Long, val maxSizeMb: Long, val sizeBytes: Long, val entryCount: Long)

/** A number however the server wrote it: `mtimeMs` carries a fraction, a size never does. */
private val JsonElement?.wholeNumber: Long? get() = double?.toLong()

/** Settings → Storage's read of what PPM's own files take (`getStorageOverview`). */
suspend fun ApiClient.storageOverview(): StorageOverview {
    val data = call("GET", "/api/settings/storage")
    val appDb = data["appDb"]
    val backups = data["backups"]
    val trace = data["sessionTrace"]
    if (appDb["path"].string == null || backups["dir"].string == null || trace["path"].string == null) {
        throw PpmError.BadResponse("Unexpected storage details.")
    }
    val newest = backups["newest"]?.takeIf { it !is JsonNull }
    return StorageOverview(
        appDbPath = appDb["path"].string.orEmpty(),
        appDbSizeBytes = appDb["sizeBytes"].wholeNumber ?: 0,
        backups = StorageBackups(
            dir = backups["dir"].string.orEmpty(),
            dbStem = backups["dbStem"].string.orEmpty(),
            count = backups["count"].wholeNumber ?: 0,
            totalBytes = backups["totalBytes"].wholeNumber ?: 0,
            newest = newest?.let { n -> n["name"].string?.let { StorageNewestBackup(it, n["mtimeMs"].wholeNumber ?: 0) } },
            others = backups["others"].array.orEmpty().mapNotNull { o ->
                o["dbStem"].string?.let { StorageOtherBackups(it, o["count"].wholeNumber ?: 0, o["totalBytes"].wholeNumber ?: 0) }
            },
        ),
        sessionTrace = sessionTrace(trace),
    )
}

private fun sessionTrace(trace: JsonElement?) = StorageSessionTrace(
    path = trace["path"].string.orEmpty(),
    sizeBytes = trace["sizeBytes"].wholeNumber ?: 0,
    oldestTs = trace["oldestTs"].wholeNumber,
    retentionDays = trace["retention_days"].wholeNumber ?: 0,
    maxSizeMb = trace["max_size_mb"].wholeNumber ?: 0,
)

/** `PUT /api/settings/session-trace` (`updateSessionTraceLimits`): saves both limits and answers the trace as it now stands. */
suspend fun ApiClient.updateSessionTraceLimits(retentionDays: Long, maxSizeMb: Long): StorageSessionTrace = sessionTrace(
    call(
        "PUT",
        "/api/settings/session-trace",
        body = kotlinx.serialization.json.buildJsonObject {
            put("retention_days", kotlinx.serialization.json.JsonPrimitive(retentionDays))
            put("max_size_mb", kotlinx.serialization.json.JsonPrimitive(maxSizeMb))
        },
    ),
)

/** The Query Audit block's read (`getQueryAuditSettings`). */
suspend fun ApiClient.queryAuditUsage(): QueryAuditUsage {
    val data = call("GET", "/api/settings/query-audit")
    return QueryAuditUsage(
        retentionDays = data["retention_days"].wholeNumber ?: 0,
        maxSizeMb = data["max_size_mb"].wholeNumber ?: 0,
        sizeBytes = data["size_bytes"].wholeNumber ?: 0,
        entryCount = data["entry_count"].wholeNumber ?: 0,
    )
}
