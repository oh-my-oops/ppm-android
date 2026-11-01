package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SharedPreferences, in memory. */
internal class MemoryValues : KeyValueStore {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** This device's copy of the layouts and its trips to the server (panel-utils.ts, "Server sync"). */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceCacheTest {
    private class FakeRemote : WorkspaceRemote {
        var server: SavedLayout? = null
        var stamp = "2026-10-20T05:00:00.000Z"
        val puts = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun get(project: String) = server

        override suspend fun put(project: String, layout: JsonObject): String {
            puts += project to layout
            return stamp
        }
    }

    private val values = MemoryValues()
    private val remote = FakeRemote()

    /** 2026-10-20T04:00:00Z */
    private var clock = 1_792_468_800_000L

    private fun TestScope.cache() = WorkspaceCache(values, "workspace.s1.", remote, this, now = { clock })

    private fun layout(text: String) = PanelLayout.fromJson(WireJson.parse(text))!!

    private fun oneTab(id: String, type: String = "editor") = layout(
        """{"panels":{"p":{"id":"p","tabs":[{"id":"$id","type":"$type","title":"t","metadata":{},"closable":true}],
           "activeTabId":"$id","tabHistory":["$id"]}},"grid":[["p"]],"focusedPanelId":"p"}""",
    )

    private fun stored(project: String) = values.map["workspace.s1.$project"]?.let { WireJson.parse(it) as JsonObject }

    @Test
    fun `window cleanup drains unsent layouts after the owner scope ends`() = runTest {
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        val cache = WorkspaceCache(values, "workspace.s1.", remote, owner, now = { clock })
        cache.save("app", oneTab("unsent"))
        owner.cancel()
        cache.flushPending()
        assertEquals(listOf("app"), remote.puts.map { it.first })
        assertEquals(oneTab("unsent").toJson(), remote.puts.single().second)
        assertEquals("2026-10-20T05:00:00.000Z", stored("app")!!["updatedAt"].string)
    }

    @Test
    fun `forget cancels pending writes and leaves other projects alone`() = runTest {
        val cache = cache()
        cache.save("removed", oneTab("old"))
        cache.save("kept", oneTab("keep"))
        cache.forget("removed")
        cache.flush()
        runCurrent()
        assertFalse(cache.has("removed"))
        assertEquals(listOf("kept"), remote.puts.map { it.first })
        assertTrue(cache.has("kept"))
    }

    @Test
    fun `late hydrate cannot restore forgotten project even if same name is reused`() = runTest {
        val response = CompletableDeferred<SavedLayout?>()
        val remote = object : WorkspaceRemote {
            override suspend fun get(project: String) = response.await()
            override suspend fun put(project: String, layout: JsonObject): String? = null
        }
        val cache = WorkspaceCache(values, "workspace.s1.", remote, this, now = { clock })
        val hydrate = async { cache.hydrate("app") }
        runCurrent()
        cache.forget("app")
        cache.save("app", oneTab("new"))
        response.complete(SavedLayout(oneTab("old"), "2030-01-01T00:00:00.000Z"))
        assertFalse(hydrate.await())
        assertEquals("new", cache.load("app")!!.panels.getValue("p").activeTabId)
    }

    @Test
    fun `late put cannot stamp a newer local edit`() = runTest {
        val response = CompletableDeferred<String?>()
        val remote = object : WorkspaceRemote {
            override suspend fun get(project: String): SavedLayout? = null
            override suspend fun put(project: String, layout: JsonObject) = response.await()
        }
        val cache = WorkspaceCache(values, "workspace.s1.", remote, this, now = { clock })
        cache.save("app", oneTab("old"))
        cache.flush()
        runCurrent()
        clock += 1000
        cache.save("app", oneTab("new"))
        response.complete("2030-01-01T00:00:00.000Z")
        runCurrent()
        assertEquals("2026-10-20T04:00:01.000Z", stored("app")!!["updatedAt"].string)
        cache.forget("app")
    }

    @Test
    fun `forget invalidates a put that finishes despite cancellation`() = runTest {
        val response = CompletableDeferred<String?>()
        val remote = object : WorkspaceRemote {
            override suspend fun get(project: String): SavedLayout? = null
            override suspend fun put(project: String, layout: JsonObject) = withContext(NonCancellable) { response.await() }
        }
        val cache = WorkspaceCache(values, "workspace.s1.", remote, this, now = { clock })
        cache.save("app", oneTab("old"))
        cache.flush()
        runCurrent()
        cache.forget("app")
        response.complete("2030-01-01T00:00:00.000Z")
        runCurrent()
        assertFalse(cache.has("app"))
    }

    @Test
    fun `a change is kept at once and sent 1,5 s after the last one`() = runTest {
        val cache = cache()
        cache.save("app", oneTab("editor:a"))
        assertEquals("2026-10-20T04:00:00.000Z", stored("app")!!["updatedAt"].string)
        advanceTimeBy(1000)
        cache.save("app", oneTab("editor:b"))
        advanceTimeBy(1499)
        runCurrent()
        assertTrue(remote.puts.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        val (project, sent) = remote.puts.single()
        assertEquals("app", project)
        assertEquals(oneTab("editor:b").toJson(), sent)
        // The server's clock decides the next conflict.
        assertEquals("2026-10-20T05:00:00.000Z", stored("app")!!["updatedAt"].string)
        assertEquals("editor:b", cache.load("app")!!.panels.getValue("p").activeTabId)
    }

    @Test
    fun `the global workspace never reaches the server`() = runTest {
        val cache = cache()
        cache.save(GLOBAL_PROJECT, oneTab("editor:a"))
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(remote.puts.isEmpty())
        assertTrue(stored(GLOBAL_PROJECT) != null)
        remote.server = SavedLayout(oneTab("editor:z"), "2030-01-01T00:00:00.000Z")
        assertFalse(cache.hydrate(GLOBAL_PROJECT))
    }

    @Test
    fun `flushing sends what is waiting without the delay`() = runTest {
        val cache = cache()
        cache.save("app", oneTab("editor:a"))
        cache.save("lib", oneTab("editor:b"))
        cache.flush()
        runCurrent()
        assertEquals(setOf("app", "lib"), remote.puts.map { it.first }.toSet())
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(2, remote.puts.size)
    }

    @Test
    fun `hydrating takes the server's copy when it is at least as new`() = runTest {
        val cache = cache()
        cache.save("app", oneTab("editor:local"))
        remote.server = SavedLayout(oneTab("editor:server"), "2026-10-20T04:00:00.000Z")
        assertTrue(cache.hydrate("app"))
        assertEquals("editor:server", cache.load("app")!!.panels.getValue("p").activeTabId)
        assertEquals("2026-10-20T04:00:00.000Z", stored("app")!!["updatedAt"].string)
    }

    @Test
    fun `the layout this device sent, back from the server, changes nothing`() = runTest {
        val cache = cache()
        cache.save("app", oneTab("editor:same"))
        remote.server = SavedLayout(oneTab("editor:same"), "2026-10-20T05:00:00.000Z")
        assertFalse(cache.hydrate("app"))
        assertTrue(cache.has("app"))
        assertFalse(cache.has("lib"))
    }

    @Test
    fun `hydrating keeps this device's copy when it is newer`() = runTest {
        val cache = cache()
        cache.save("app", oneTab("editor:local"))
        remote.server = SavedLayout(oneTab("editor:server"), "2026-10-20T03:59:59.999Z")
        assertFalse(cache.hydrate("app"))
        assertEquals("editor:local", cache.load("app")!!.panels.getValue("p").activeTabId)
    }

    @Test
    fun `a server copy with an old tunnel tab is cleaned before it is kept`() = runTest {
        val cache = cache()
        remote.server = SavedLayout(oneTab("tunnels", type = "tunnels"), "2026-10-20T04:00:00.000Z")
        assertTrue(cache.hydrate("app"))
        val tabs = (stored("app")!!["panels"] as JsonObject)["p"].let { (it as JsonObject)["tabs"].toString() }
        assertEquals("[]", tabs)
        assertNull(cache.load("app")!!.panels.getValue("p").activeTabId)
    }
}
