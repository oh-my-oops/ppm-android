package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** `feature-intros:changed` on `/ws/global` reaches whoever listens, and stops once they let go. */
class GlobalEventsFeatureIntrosTest {
    @Test
    fun `a new-feature change is handed to its listeners`() {
        val events = GlobalEvents(ApiClient("http://127.0.0.1:9/".toHttpUrl(), "t"), CoroutineScope(Job()))
        val heard = mutableListOf<Pair<String, String?>>()
        val off = events.onFeatureIntrosChanged { change, id -> heard += change to id }
        events.handle(WireJson.parse("""{"type":"feature-intros:changed","change":"skip","id":"session-tools-2026-11","answer":null}"""))
        events.handle(WireJson.parse("""{"type":"feature-intros:changed","change":"settings"}"""))
        off()
        events.handle(WireJson.parse("""{"type":"feature-intros:changed","change":"answer","id":"x"}"""))
        assertEquals(listOf("skip" to "session-tools-2026-11", "settings" to null), heard)
    }
}
