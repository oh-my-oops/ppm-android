package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** `memory:changed` and `memory:embed-install` on `/ws/global` reach Settings → Memory (`use-global-events.ts`). */
class GlobalEventsMemoryTest {
    @Test
    fun `memory changes and install progress are handed to their listeners`() {
        val events = GlobalEvents(ApiClient("http://127.0.0.1:9/".toHttpUrl(), "t"), CoroutineScope(Job()))
        var changed = 0
        val installs = mutableListOf<String?>()
        val offChanged = events.onMemoryChanged { changed++ }
        val offInstall = events.onMemoryEmbedInstall { installs += it["state"].string }

        events.handle(WireJson.parse("""{"type":"memory:changed","settings":{"enabled":true}}"""))
        events.handle(WireJson.parse("""{"type":"memory:changed","embedding":{"vectors":3}}"""))
        events.handle(WireJson.parse("""{"type":"memory:embed-install","status":{"state":"installing","step":"model","bytes":1,"totalBytes":2}}"""))
        // A frame without a status object says nothing.
        events.handle(WireJson.parse("""{"type":"memory:embed-install","status":null}"""))
        offChanged()
        offInstall()
        events.handle(WireJson.parse("""{"type":"memory:changed"}"""))
        events.handle(WireJson.parse("""{"type":"memory:embed-install","status":{"state":"ready"}}"""))

        assertEquals(2, changed)
        assertEquals(listOf<String?>("installing"), installs)
    }
}
