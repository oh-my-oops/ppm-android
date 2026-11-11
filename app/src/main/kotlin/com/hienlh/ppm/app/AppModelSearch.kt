package com.hienlh.ppm.app

import com.hienlh.ppm.app.ui.phone.files.PhoneSearchBackend
import com.hienlh.ppm.app.ui.phone.files.PhoneSearchController
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.state.files.FileMutationItem
import com.hienlh.ppm.state.files.FileMutationOperation
import com.hienlh.ppm.state.files.FileMutationOutcome
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.workspace.absoluteProjectPath
import java.util.WeakHashMap

private class PhoneSearchHolder(val api: ApiClient, val controller: PhoneSearchController)

/** Main thread only, like every other [AppModel] member. */
private val phoneSearches = WeakHashMap<AppModel, PhoneSearchHolder>()

/**
 * The phone's content search for the open server (`search-store.ts` + `search-client.ts`), kept
 * for the model's life so leaving the Search screen, opening a match or switching projects loses
 * nothing. A new server gets a new one; null while no server is open.
 */
val AppModel.phoneSearch: PhoneSearchController?
    get() {
        val api = api ?: return null
        phoneSearches[this]?.takeIf { it.api === api }?.let { return it.controller }
        phoneSearches.remove(this)?.controller?.close()
        val controller = PhoneSearchController(
            backend = PhoneSearchBackend.api(api),
            scope = scope,
            isUnsaved = { project, file -> hasUnsavedEdits(project, file) },
            onReplaced = { project, files -> if (this.api === api) searchReplaced(project, files) },
        )
        phoneSearches[this] = PhoneSearchHolder(api, controller)
        return controller
    }

/** Files a search replace wrote: refresh their folders and every open tab on them. */
internal fun AppModel.searchReplaced(project: String, files: List<String>) {
    val root = projects.firstOrNull { it.name == project }?.path ?: return
    filesMutated(
        FileMutationReport(
            items = files.map { FileMutationItem(FileMutationOperation.WRITE, project, it, outcome = FileMutationOutcome.SUCCEEDED) },
            affectedDirectories = files.map { absoluteProjectPath(root, it.substringBeforeLast('/', "")) }.toSet(),
        ),
    )
}
