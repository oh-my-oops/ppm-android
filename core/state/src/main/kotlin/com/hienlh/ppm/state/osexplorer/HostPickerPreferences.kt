package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Folder picker history is local to one server and stores visited parents, not chosen files. */
class HostPickerPreferences(private val values: KeyValueStore, private val key: String) {
    private val mutable = MutableStateFlow(runCatching {
        values.get(key)?.let(WireJson::parseOrNull).array.orEmpty().mapNotNull { it.string }.distinct().take(5)
    }.getOrDefault(emptyList()))
    val recent: StateFlow<List<String>> = mutable.asStateFlow()
    fun rememberDirectory(path: String) {
        mutable.value = (listOf(path) + mutable.value.filter { it != path }).take(5)
        runCatching { values.put(key, JsonArray(mutable.value.map(::JsonPrimitive)).toString()) }
    }
}

enum class HostPickerMode { FILE, FOLDER, BOTH }
fun hostPickerAccepts(name: String, accept: List<String>): Boolean = accept.isEmpty() || ("." + name.substringAfterLast('.').lowercase()) in accept
