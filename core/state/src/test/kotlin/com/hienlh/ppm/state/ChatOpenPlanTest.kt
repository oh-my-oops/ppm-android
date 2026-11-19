package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ASSISTANT_PROJECT_NAME
import org.junit.Assert.assertEquals
import org.junit.Test

/** `chat-open-plan.test.ts`. */
class ChatOpenPlanTest {
    @Test
    fun `a chat of the project on screen opens as a tab, as before`() {
        assertEquals(ChatOpenPlan.HERE, planChatOpen("ppm", "ppm", mobile = false))
        assertEquals(ChatOpenPlan.HERE, planChatOpen("ppm", "ppm", mobile = true))
    }

    @Test
    fun `on a tablet, another project's chat opens in a floating window`() {
        assertEquals(ChatOpenPlan.WINDOW, planChatOpen("ppm", "nxsys", mobile = false))
    }

    @Test
    fun `on a phone, where there are no windows, the screen switches to the chat's project`() {
        assertEquals(ChatOpenPlan.SWITCH, planChatOpen("ppm", "nxsys", mobile = true))
    }

    @Test
    fun `with no project on screen there is nothing to keep, so the project switches`() {
        assertEquals(ChatOpenPlan.SWITCH, planChatOpen("ppm", null, mobile = false))
    }

    @Test
    fun `a PPM Assistant session opens in the Assistant, wherever the user is`() {
        for (mobile in listOf(false, true)) {
            assertEquals(ChatOpenPlan.ASSISTANT, planChatOpen(ASSISTANT_PROJECT_NAME, "nxsys", mobile))
            assertEquals(ChatOpenPlan.ASSISTANT, planChatOpen(ASSISTANT_PROJECT_NAME, null, mobile))
        }
    }

    @Test
    fun `project names are compared exactly`() {
        assertEquals(ChatOpenPlan.WINDOW, planChatOpen("PPM", "ppm", mobile = false))
    }
}
