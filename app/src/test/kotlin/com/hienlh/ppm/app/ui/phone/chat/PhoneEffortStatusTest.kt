package com.hienlh.ppm.app.ui.phone.chat

import com.hienlh.ppm.protocol.EffortSettingReport
import com.hienlh.ppm.protocol.EffortStatusReport
import com.hienlh.ppm.protocol.ObservedEffort
import com.hienlh.ppm.state.Effort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The effort the turns run at and why (`effortInEffect`, `effortNotice`), on the cases the web's tests pin. */
class PhoneEffortStatusTest {
    @Test
    fun `with nothing known the pick runs, and nothing is said`() {
        val shown = shownEffort("medium", null)
        assertEquals(ShownEffort(Effort.MEDIUM, Effort.MEDIUM, null), shown)
        assertNull(effortNotice(shown, null, "Opus 5.5"))
        assertEquals(Effort.HIGH, shownEffort(null, null).pick)
    }

    @Test
    fun `a variable in a settings file outranks the pick, and the notice names it and where it is`() {
        val status = EffortStatusReport(
            env = EffortSettingReport("max", "userSettings"),
            observed = ObservedEffort(requested = "high", applied = "max"),
        )
        val shown = shownEffort("high", status)
        assertEquals(ShownEffort(Effort.HIGH, Effort.MAX, "env"), shown)
        val notice = effortNotice(shown, status, "Opus 5.5")!!
        assertEquals("Effort is set to Max outside PPM", notice.title)
        assertEquals(
            "CLAUDE_CODE_EFFORT_LEVEL=max in ~/.claude/settings.json overrides the effort picked here. Remove it there to use the effort picked in PPM.",
            notice.detail,
        )
        // Whatever is picked since, the variable still wins.
        assertEquals(Effort.MAX, shownEffort("low", status).level)
    }

    @Test
    fun `a cap lowers a pick above it`() {
        val status = EffortStatusReport(cap = EffortSettingReport("medium", "projectSettings"))
        val shown = shownEffort("max", status)
        assertEquals(ShownEffort(Effort.MAX, Effort.MEDIUM, "cap"), shown)
        val notice = effortNotice(shown, status, "Opus 5.5")!!
        assertEquals("Effort is capped at Medium", notice.title)
        assertEquals("maxEffortLevel: \"medium\" in .claude/settings.json lowers any effort picked above it.", notice.detail)
        assertNull("a pick under the cap runs as picked", shownEffort("low", status).reason)
    }

    @Test
    fun `a model without Extra runs it as High, and one without levels runs none`() {
        val noExtra = EffortStatusReport(supported = listOf("low", "medium", "high"))
        val shown = shownEffort("xhigh", noExtra)
        assertEquals(ShownEffort(Effort.XHIGH, Effort.HIGH, "model"), shown)
        assertEquals("Runs at High", effortNotice(shown, noExtra, "Sonnet 4.6")!!.title)
        assertEquals("Extra isn't available for Sonnet 4.6, so Claude Code runs it at High.", effortNotice(shown, noExtra, "Sonnet 4.6")!!.detail)

        val none = EffortStatusReport(supported = emptyList())
        val haiku = shownEffort("high", none)
        assertNull(haiku.level)
        assertEquals("Haiku 4.5 takes no effort level", effortNotice(haiku, none, "Haiku 4.5")!!.title)
    }

    @Test
    fun `auto leaves it to the model`() {
        val status = EffortStatusReport(env = EffortSettingReport("auto", "environment"))
        val shown = shownEffort("high", status)
        assertEquals(ShownEffort(Effort.HIGH, null, "env"), shown)
        val notice = effortNotice(shown, status, "Opus 5.5")!!
        assertEquals("Effort is left to the model", notice.title)
        assertEquals(
            "CLAUDE_CODE_EFFORT_LEVEL=auto in the environment PPM runs in overrides the effort picked here: no effort is sent, so Opus 5.5's default applies. Remove it there to use the effort picked in PPM.",
            notice.detail,
        )
    }
}
