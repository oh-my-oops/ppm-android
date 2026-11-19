package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalSummaryTest {
    /** A script proposal card as `scriptProposalSummary` builds it, input values verbatim and a seconds cron's runs. */
    private val scriptCard = """
        {"kind":"schedule","action":"create_script","summary":{
          "headline":"Create the script schedule \"watch\" in \"ppm\": it runs code on this machine with nobody watching",
          "facts":[
            {"label":"Name","value":"watch"},
            {"label":"Cron","value":"*/20 * * * * *"},
            {"label":"Next 3 runs","value":"2026-11-19 09:00:20, 2026-11-19 09:00:40, 2026-11-19 09:01:00 (Europe/London)"},
            {"label":"Sends to chat","value":"One (0f0e0d0c-0b0a-4908-8706-050403020100)"},
            {"label":"Sends to chat","value":"Two (1f0e0d0c-0b0a-4908-8706-050403020100) in another project","tone":"warning"},
            {"label":"Input folder","value":"\"C:\\Users\\me\\repo\""},
            {"label":"Its AI turns run in","value":"Bypass permissions","tone":"warning"}
          ],
          "body":{"label":"Script (runs exactly as shown)","text":"export default async (ctx) => {}","format":"code"},
          "warning":"Nobody watches this.",
          "reviewInPpm":"A script schedule runs code unattended, so it can only be allowed in PPM."
        }}
    """.trimIndent()

    @Test
    fun `a schedule card parses into its server summary, every fact kept in order and verbatim`() {
        val input = SessionCollabApprovalInput.from(SESSION_COLLAB_APPROVAL, WireJson.parse(scriptCard)) as SessionCollabApprovalInput.Schedule
        assertEquals("create_script", input.action)
        assertEquals("The AI proposes a script schedule", input.heading)
        assertEquals(listOf(SessionCollabAnswer.ALLOW_ONCE, SessionCollabAnswer.DENY), input.answers)
        val s = input.summary
        assertEquals(listOf("Name", "Cron", "Next 3 runs", "Sends to chat", "Sends to chat", "Input folder", "Its AI turns run in"), s.facts.map { it.label })
        // A text input is shown as the script receives it: one backslash each, between quotes.
        assertEquals("\"C:\\Users\\me\\repo\"", s.facts[5].value)
        assertEquals("2026-11-19 09:00:20, 2026-11-19 09:00:40, 2026-11-19 09:01:00 (Europe/London)", s.facts[2].value)
        assertEquals(listOf(false, false, false, false, true, false, true), s.facts.map { it.warning })
        assertEquals(ApprovalSummary.Body("Script (runs exactly as shown)", "export default async (ctx) => {}", "code"), s.body)
        assertEquals("Nobody watches this.", s.warning)
        assertEquals("A script schedule runs code unattended, so it can only be allowed in PPM.", s.reviewInPpm)
    }

    @Test
    fun `the pause and turn-on cards head with what they ask`() {
        fun card(action: String) = SessionCollabApprovalInput.from(
            SESSION_COLLAB_APPROVAL,
            WireJson.parse("""{"kind":"schedule","action":"$action","summary":{"headline":"h","facts":[]}}"""),
        ) as SessionCollabApprovalInput.Schedule
        assertEquals("The AI wants to turn on a schedule", card("enable").heading)
        assertEquals("The AI wants to pause a schedule", card("disable").heading)
    }

    @Test
    fun `a schedule card without a headline is not one`() {
        assertNull(SessionCollabApprovalInput.from(SESSION_COLLAB_APPROVAL, WireJson.parse("""{"kind":"schedule","action":"enable"}""")))
        assertNull(SessionCollabApprovalInput.from(SESSION_COLLAB_APPROVAL, WireJson.parse("""{"kind":"schedule","summary":{"facts":[]}}""")))
    }

    @Test
    fun `an endpoint approval request carries its summary, and a provider's has none`() {
        val endpoint = ApprovalRequest.from(
            WireJson.parse(
                """{"type":"approval_request","requestId":"r1","tool":"schedule_propose_script","input":{"name":"x"},"origin":"endpoint",
                   "summary":{"headline":"Create it","facts":[{"label":"Cron","value":"0 9 * * 1-5"}],"body":{"label":"SQL","text":"DELETE","format":"sql"},"statementCount":1}}""",
            ),
        )!!
        assertEquals("Create it", endpoint.summary?.headline)
        assertEquals(1, endpoint.summary?.statementCount)
        assertEquals("sql", endpoint.summary?.body?.format)
        assertNull(endpoint.summary?.reviewInPpm)
        assertNull(ApprovalRequest.from(WireJson.parse("""{"requestId":"r2","tool":"Bash","input":{"command":"ls"}}"""))!!.summary)
    }
}
