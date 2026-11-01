package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** [keyPrefix] must identify the server, as with WorkspaceCache and terminal session storage. */
class FileTreePersistence(private val values: KeyValueStore, private val keyPrefix: String) {
    fun expanded(project: String): Set<String> = read("ppm-explorer-expanded:", project).filter { it.isNotEmpty() }.take(50).toSet()
    fun saveExpanded(project: String, paths: Set<String>) = write("ppm-explorer-expanded:", project, paths.filter { it.isNotEmpty() }.take(50))

    // Web selection is transient. Native selection restoration is an explicit port requirement;
    // keep it in its own key, without changing the web-compatible expanded-array format.
    fun selected(project: String): List<String> = read("ppm-explorer-selected:", project)
    fun saveSelected(project: String, paths: List<String>) = write("ppm-explorer-selected:", project, paths)

    fun forget(project: String) {
        values.put(keyPrefix + "ppm-explorer-expanded:" + project, null)
        values.put(keyPrefix + "ppm-explorer-selected:" + project, null)
    }

    private fun read(kind: String, project: String): List<String> = try {
        values.get(keyPrefix + kind + project)?.let(WireJson::parseOrNull).array?.mapNotNull { it.string }?.distinct().orEmpty()
    } catch (_: Exception) { emptyList() }

    private fun write(kind: String, project: String, paths: List<String>) {
        try { values.put(keyPrefix + kind + project, JsonArray(paths.map(::JsonPrimitive)).toString()) }
        catch (_: Exception) { /* Unavailable preferences must not prevent browsing. */ }
    }
}
