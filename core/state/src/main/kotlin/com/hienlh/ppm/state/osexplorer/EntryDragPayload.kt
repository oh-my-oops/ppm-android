package com.hienlh.ppm.state.osexplorer

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The web's `EntryDragOrigin`: where a drag of entries began. [wire] is how the payload spells it. */
enum class EntryDragOrigin(val wire: String) { EXPLORER("explorer"), TREE("tree") }

/**
 * The web's `EntryDragPayload` (entry-drag-payload.ts), what every PPM drag of entries carries under `application/x-ppm-paths`: the [paths]
 * dragged, absolute host paths whichever surface they come from, the [origin], and the [projectName] a project tree's drag names (a
 * tree-internal drop keeps its own project-scoped route). An empty project name is none, as the web's is.
 */
data class EntryDragPayload(val paths: List<String>, val origin: EntryDragOrigin, val projectName: String? = null) {
    /** The web's `encodeEntryDrag`: `{ paths, origin, projectName? }`, in that order. */
    fun toJson(): String = buildJsonObject {
        putJsonArray("paths") { paths.forEach { add(it) } }
        put("origin", origin.wire)
        if (!projectName.isNullOrEmpty()) put("projectName", projectName)
    }.toString()
}
