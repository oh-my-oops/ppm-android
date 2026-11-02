package com.hienlh.ppm.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The prompt cache going cold under an open chat: the notice joins the end of the list. On anh's
 * tablet that threw `index: -1, size: 51` out of the list's builder, which read the cache live while
 * the keys it stacks items by came from the composition before. A new cache state restarts the
 * minute clock, whose first reading lands between that composition and the list's measure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi")
class ChatTranscriptColdCacheTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    @Test
    fun `a cache that turns cold under an open chat adds the notice`() {
        val ttl = 300_000.0
        val now = System.currentTimeMillis().toDouble()
        // Few enough to fit, so the notice is drawn by the measure that first finds it.
        val items = (0 until 4).map { i ->
            val role = if (i % 2 == 0) ChatItem.Role.USER else ChatItem.Role.ASSISTANT
            ChatItem("m$i", role, listOf(ChatBlock.Text("Message $i")), Instant.ofEpochSecond(1_700_000_000L + i))
        }
        fun cache(endedAt: Double) = PromptCacheState(ttlMs = ttl, lastTurnEndedAt = endedAt, billedPrefixTokens = 50_000.0, contextTokens = 60_000.0)
        var state by mutableStateOf(ChatState(sessionId = "s", items = items, hasLoadedHistory = true, promptCache = cache(now)))
        compose.mainClock.autoAdvance = false
        compose.setContent { ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {}) }
        compose.mainClock.advanceTimeByFrame()
        val notice = "The prompt cache has likely expired"
        assertEquals(0, compose.onAllNodesWithText(notice, substring = true).fetchSemanticsNodes().size)

        state = state.copy(promptCache = cache(now - 2 * ttl))
        // A frame recomposes with the new state and restarts the clock, whose first reading turns
        // the cache cold; the list measures again before the next frame recomposes.
        repeat(3) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText(notice, substring = true).fetchSemanticsNodes().size)
    }
}
