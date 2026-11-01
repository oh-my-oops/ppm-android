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
 * the folder as soon as it composes. Close it when the test is over.
 */
internal fun explorerSessionOn(directory: HostDirectory): ExplorerSession =
    ExplorerSession(
        HostDirectoryReader { _, _ -> directory },
        ExplorerPreferences(MemoryValues(), "test."),
        CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        directory.current,
    ).also { it.show() }
