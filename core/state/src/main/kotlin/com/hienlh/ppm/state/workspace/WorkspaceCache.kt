package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.putWorkspace
import com.hienlh.ppm.client.workspace
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The server's copy of every project's layout (`/api/project/:name/workspace`). */
interface WorkspaceRemote {
    suspend fun get(project: String): SavedLayout?

    /** Answers the time the server stamped the copy with. */
    suspend fun put(project: String, layout: JsonObject): String?
}

fun ApiClient.workspaceRemote(): WorkspaceRemote = object : WorkspaceRemote {
    override suspend fun get(project: String): SavedLayout? =
        workspace(project)?.let { saved -> PanelLayout.fromJson(saved.layout)?.let { SavedLayout(it, saved.updatedAt) } }

    override suspend fun put(project: String, layout: JsonObject): String? = putWorkspace(project, layout)
}

/**
 * This device's copy of each project's layout, sent on to the server 1.5 s after the last change
 * (panel-utils.ts: `savePanelLayout`, `syncWorkspaceToServer`, `hydrateWorkspaceFromServer`).
 *
 * The local copy is what [WorkspaceStore] reads; [hydrate] replaces it with the server's when the
 * server's is at least as new, which is how a layout made on another device arrives.
 */
class WorkspaceCache(
    private val values: KeyValueStore,
    /** Keeps one server's layouts apart from another's. */
    private val keyPrefix: String,
    private val remote: WorkspaceRemote,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : LayoutStorage {
    private val pending = HashMap<String, Pending>()

    private class Pending(val layout: PanelLayout, val job: Job)

    override fun load(project: String): PanelLayout? = stored(project)?.layout?.let(::migrateLoadedLayout)

    override fun save(project: String, layout: PanelLayout) {
        write(project, SavedLayout(layout, ISO.format(Instant.ofEpochMilli(now()))))
        // `__global__` is not a project the server knows.
        if (project == GLOBAL_PROJECT) return
        pending.remove(project)?.job?.cancel()
        val job = scope.launch {
            delay(SYNC_DELAY_MS)
            pending.remove(project)
            push(project, layout)
        }
        pending[project] = Pending(layout, job)
    }

    /** Sends every change still waiting out now: Android may end a process in the background without asking. */
    fun flush() {
        val waiting = pending.toList()
        pending.clear()
        for ((project, change) in waiting) {
            change.job.cancel()
            scope.launch { push(project, change.layout) }
        }
    }

    /** Whether this device keeps a copy of [project]'s layout. */
    fun has(project: String): Boolean = values.get(key(project)) != null

    /**
     * Takes the server's copy of [project] when it is at least as new as this device's. True when
     * that changed what this device holds, i.e. when the layout shown is out of date.
     */
    suspend fun hydrate(project: String): Boolean {
        if (project == GLOBAL_PROJECT) return false
        val server = try {
            remote.get(project)
        } catch (_: Exception) {
            null
        } ?: return false
        val local = stored(project)
        if (resolveWorkspaceConflict(local, server) !== server) return false
        // Migrated before it is written, as on the web, so a legacy tab from another device never lands here.
        val layout = migratePortsToTunnels(server.layout)
        // The copy this device sent itself comes back stamped by the server: nothing to show again.
        if (local != null && local.layout.toJson() == layout.toJson()) return false
        write(project, SavedLayout(layout, server.updatedAt))
        return true
    }

    private suspend fun push(project: String, layout: PanelLayout) {
        val updatedAt = try {
            remote.put(project, layout.toJson())
        } catch (_: Exception) {
            // The local copy is still the cache; the next change sends it again.
            return
        } ?: return
        // The server's clock decides the next conflict, not this device's.
        val local = values.get(key(project))?.let(WireJson::parseOrNull) as? JsonObject ?: return
        values.put(key(project), JsonObject(local + ("updatedAt" to JsonPrimitive(updatedAt))).toString())
    }

    private fun stored(project: String): SavedLayout? =
        values.get(key(project))?.let(WireJson::parseOrNull)?.let(SavedLayout::fromJson)

    private fun write(project: String, saved: SavedLayout) = values.put(key(project), saved.toJson().toString())

    private fun key(project: String) = "$keyPrefix$project"

    private companion object {
        const val SYNC_DELAY_MS = 1500L

        /** `Date.toISOString()`: always the milliseconds. */
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
