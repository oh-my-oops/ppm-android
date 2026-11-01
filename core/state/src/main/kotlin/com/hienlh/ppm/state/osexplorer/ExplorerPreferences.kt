package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ExplorerSort { NAME, SIZE, MODIFIED, KIND }
enum class ExplorerView { LIST, ICONS, COLUMNS }
data class ExplorerViewPreferences(val sort: ExplorerSort = ExplorerSort.NAME, val descending: Boolean = false,
                                   val view: ExplorerView = ExplorerView.LIST, val showHidden: Boolean = false)

/** One per server: shared view preferences and PPM pins, separate from read-only host pins. */
class ExplorerPreferences(private val values: KeyValueStore, private val prefix: String) {
    private fun read(key: String) = runCatching { values.get(prefix + key)?.let(WireJson::parseOrNull) }.getOrNull()
    private val initial = read("ppm-explorer-view")
    private val mutable = MutableStateFlow(ExplorerViewPreferences(
        enumValues<ExplorerSort>().firstOrNull { it.name.equals(initial["sort"]["key"].string, true) } ?: ExplorerSort.NAME,
        initial["sort"]["dir"].string == "desc",
        enumValues<ExplorerView>().firstOrNull { it.name.equals(initial["viewMode"].string, true) } ?: ExplorerView.LIST,
        initial["showHidden"].bool == true,
    ))
    val state: StateFlow<ExplorerViewPreferences> = mutable.asStateFlow()
    private val mutablePins = MutableStateFlow(read("ppm-explorer-pins").array.orEmpty().mapNotNull(HostPlace::from).distinctBy { it.path }.take(50))
    val pins: StateFlow<List<HostPlace>> = mutablePins.asStateFlow()

    fun update(preferences: ExplorerViewPreferences) {
        mutable.value = preferences
        write("ppm-explorer-view", buildJsonObject {
            put("sort", buildJsonObject { put("key", preferences.sort.name.lowercase()); put("dir", if (preferences.descending) "desc" else "asc") })
            put("viewMode", preferences.view.name.lowercase()); put("showHidden", preferences.showHidden)
        }.toString())
    }
    fun pin(name: String, path: String) {
        if (mutablePins.value.any { it.path == path } || mutablePins.value.size >= 50) return
        savePins(mutablePins.value + HostPlace(name, path))
    }
    fun unpin(path: String) = savePins(mutablePins.value.filter { it.path != path })
    private fun savePins(pins: List<HostPlace>) {
        mutablePins.value = pins
        write("ppm-explorer-pins", JsonArray(pins.map { buildJsonObject { put("name", it.name); put("path", it.path) } }).toString())
    }
    private fun write(key: String, value: String) { runCatching { values.put(prefix + key, value) } }
}
