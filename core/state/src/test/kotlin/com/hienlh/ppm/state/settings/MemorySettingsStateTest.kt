package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.MemoryEmbedding
import com.hienlh.ppm.protocol.MemoryEmbeddingTest
import com.hienlh.ppm.protocol.MemoryInstallStatus
import com.hienlh.ppm.protocol.MemoryProviderStatus
import com.hienlh.ppm.protocol.MemoryStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Settings → Memory as `memory-settings-section.tsx` and `memory-embedding-source.tsx` run it. */
class MemorySettingsStateTest {
    private class FakeSource(var status: MemoryStatus) : MemorySettingsData.Source {
        val calls = mutableListOf<String>()
        var failWith: String? = null

        private fun maybeFail() { failWith?.let { throw IllegalStateException(it) } }

        override suspend fun status(): MemoryStatus { calls += "status"; maybeFail(); return status }
        override suspend fun setEnabled(enabled: Boolean): Boolean {
            calls += "enabled=$enabled"; maybeFail()
            status = status.copy(enabled = enabled)
            return enabled
        }
        override suspend fun sync() { calls += "sync"; maybeFail() }
        override suspend fun setEmbedding(fields: Map<String, String>) {
            calls += "embedding=$fields"; maybeFail()
            fields["source"]?.let { status = status.copy(embedding = status.embedding.copy(source = it)) }
        }
        override suspend fun install(): MemoryInstallStatus { calls += "install"; maybeFail(); return MemoryInstallStatus("installing", "packages") }
        override suspend fun remove() { calls += "remove"; maybeFail() }
        override suspend fun test(fields: Map<String, String>): MemoryEmbeddingTest { calls += "test=$fields"; maybeFail(); return MemoryEmbeddingTest(true, 1536, 210) }
    }

    private fun status(source: String = "off", url: String = "", model: String = "") = MemoryStatus(
        enabled = true,
        root = "~/.ppm/memory",
        embedding = MemoryEmbedding(source = source, apiUrl = url, apiModel = model, reason = if (source == "off") "Search by meaning is off" else ""),
    )

    @Test fun `a provider row says its error, what it skipped, or where it reads from`() {
        val claude = MEMORY_PROVIDERS.first { it.id == "claude" }
        assertEquals(MemoryProviderNote("boom", true), memoryProviderNote(MemoryProviderStatus(error = "boom"), claude))
        assertEquals(
            MemoryProviderNote("Skipped 2 items: secret.md", true),
            memoryProviderNote(MemoryProviderStatus(count = 1, skipped = listOf("secret.md", "x")), claude),
        )
        assertEquals(MemoryProviderNote(claude.empty, false), memoryProviderNote(null, claude))
        assertEquals(MemoryProviderNote(claude.source, false), memoryProviderNote(MemoryProviderStatus(count = 3), claude))

        val rel = { _: Instant -> "5m ago" }
        assertEquals("Not synced yet", memoryProviderSummary(MemoryProviderStatus(count = 3), rel))
        assertEquals("3 memories · 5m ago", memoryProviderSummary(MemoryProviderStatus(3, "2026-11-13T08:55:00Z"), rel))
        assertEquals("1 memory · 5m ago", memoryProviderSummary(MemoryProviderStatus(1, "2026-11-13T08:55:00Z"), rel))
        assertEquals("0 · 5m ago", memoryProviderSummary(MemoryProviderStatus(0, "2026-11-13T08:55:00Z"), rel))
    }

    @Test fun `vector counts and install progress read as the web writes them`() {
        assertEquals("4 of 6 memories have meanings; 2 are being computed.", memoryVectorCount(4, 6, true))
        assertEquals("6 of 6 memories have meanings.", memoryVectorCount(6, 6, true))
        val mb = 1024L * 1024L
        assertEquals(MemoryInstallProgress(325, 650, 50), memoryInstallProgress(MemoryInstallStatus("installing", "model", 325 * mb, 650 * mb)))
        assertEquals(MemoryInstallProgress(0, 0, 0), memoryInstallProgress(MemoryInstallStatus("installing", "packages")))
    }

    @Test fun `switching memory off saves it and reads the status again`() = runBlocking {
        val source = FakeSource(status())
        val data = MemorySettingsData(source)
        data.load()
        data.setEnabled(false)
        assertFalse(data.state.value.status!!.enabled)
        assertEquals(listOf("status", "enabled=false", "status"), source.calls)
        assertFalse(data.state.value.saving)
    }

    @Test fun `a failed save or sync is reported, and the switch stays as it was`() = runBlocking {
        val errors = mutableListOf<String>()
        val source = FakeSource(status())
        val data = MemorySettingsData(source) { errors += it }
        data.load()
        source.failWith = "PPM memory is off"
        data.syncNow()
        data.setEnabled(false)
        assertEquals(listOf("Sync failed: PPM memory is off", "Could not save: PPM memory is off"), errors)
        assertTrue(data.state.value.status!!.enabled)
        assertFalse(data.state.value.syncing)
    }

    @Test fun `a status that cannot be read says why`() = runBlocking {
        val source = FakeSource(status()).apply { failWith = "PPM memory is unavailable right now: no host" }
        val data = MemorySettingsData(source)
        data.load()
        assertNull(data.state.value.status)
        assertEquals("PPM memory is unavailable right now: no host", data.state.value.loadError)
    }

    @Test fun `API with no saved endpoint opens its form without saving`() = runBlocking {
        val source = FakeSource(status())
        val data = MemorySettingsData(source)
        data.load()
        data.choose("api")
        assertEquals("api", data.state.value.chosenSource)
        assertEquals(listOf("status"), source.calls)
        // A reload that does not move the saved source keeps the form open.
        data.load()
        assertEquals("api", data.state.value.chosenSource)
    }

    @Test fun `choosing the local model saves the source`() = runBlocking {
        val source = FakeSource(status())
        val data = MemorySettingsData(source)
        data.load()
        data.choose("local")
        assertEquals("local", data.state.value.chosenSource)
        assertEquals(listOf("status", "embedding={source=local}", "status"), source.calls)
        assertNull(data.state.value.busy)
    }

    @Test fun `a refused source goes back to the one before`() = runBlocking {
        val errors = mutableListOf<String>()
        val source = FakeSource(status())
        val data = MemorySettingsData(source) { errors += it }
        data.load()
        source.failWith = "An API source needs a URL and a model"
        data.choose("local")
        assertEquals("off", data.state.value.chosenSource)
        assertEquals(listOf("Could not save: An API source needs a URL and a model"), errors)
    }

    @Test fun `Save sends the trimmed fields and leaves an empty key out`() = runBlocking {
        val source = FakeSource(status("api", "https://api.openai.com/v1", "text-embedding-3-small"))
        val data = MemorySettingsData(source)
        data.load()
        assertEquals("https://api.openai.com/v1", data.state.value.url)
        data.setModel("  m2 ")
        data.save()
        data.setApiKey(" sk-1 ")
        data.test()
        assertEquals(
            listOf(
                "status",
                "embedding={source=api, api_url=https://api.openai.com/v1, api_model=m2}",
                "status",
                "test={source=api, api_url=https://api.openai.com/v1, api_model=m2, api_key=sk-1}",
            ),
            source.calls,
        )
        assertEquals(MemoryEmbeddingTest(true, 1536, 210), data.state.value.testResult)
    }

    @Test fun `a test that throws shows its error in place of a result`() = runBlocking {
        val source = FakeSource(status("api", "https://x/v1", "m"))
        val data = MemorySettingsData(source)
        data.load()
        source.failWith = "timeout"
        data.test()
        assertEquals(MemoryEmbeddingTest(false, error = "timeout"), data.state.value.testResult)
    }

    @Test fun `install progress comes from the socket and a settled install reloads`() = runBlocking {
        val source = FakeSource(status("local"))
        val data = MemorySettingsData(source)
        data.load()
        data.startInstall()
        assertEquals("installing", data.state.value.install.state)
        assertFalse(data.onInstallEvent(MemoryInstallStatus("installing", "model", 10, 20)))
        assertEquals(10L, data.state.value.install.bytes)
        assertTrue(data.onInstallEvent(MemoryInstallStatus("ready")))
        data.removeInstall()
        assertEquals("not-installed", data.state.value.install.state)
        assertEquals(listOf("status", "install", "remove", "status"), source.calls)
    }

    @Test fun `words only while vectors cannot be used`() = runBlocking {
        val data = MemorySettingsData(FakeSource(status()))
        data.load()
        assertTrue(data.state.value.wordsOnly)
        val ready = MemorySettingsData(FakeSource(status("local").copy(embedding = MemoryEmbedding("local", ready = true))))
        ready.load()
        assertFalse(ready.state.value.wordsOnly)
    }
}
