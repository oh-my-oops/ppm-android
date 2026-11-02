package com.hienlh.ppm.app.ui.osexplorer

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.state.osexplorer.ExplorerPreferences
import com.hienlh.ppm.state.osexplorer.ExplorerSession
import com.hienlh.ppm.state.osexplorer.HostDirectoryReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private class MemoryValues : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * A session shown on [directory] that answers at once, with its preferences kept in memory, so that a test sees
 * the folder as soon as it composes. It answers [directory] to every path, unless a test that navigates says what [answer] is
 * for each, and it starts at [directory]'s own path unless a test says [initialPath] is another way to spell it (`~`).
 * Close it when the test is over.
 */
internal fun explorerSessionOn(
    directory: HostDirectory, answer: (String) -> HostDirectory = { directory }, initialPath: String = directory.current,
): ExplorerSession =
    ExplorerSession(
        HostDirectoryReader { path, _ -> answer(path) },
        ExplorerPreferences(MemoryValues(), "test."),
        CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        initialPath,
    ).also { it.show() }
