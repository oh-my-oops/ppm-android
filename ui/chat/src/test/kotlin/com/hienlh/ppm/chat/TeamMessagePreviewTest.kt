package com.hienlh.ppm.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** A team message's headline, body and task, as team-message-preview.test.ts checks them. */
class TeamMessagePreviewTest {
    @Test
    fun `a task assignment heads with its subject, never raw JSON`() {
        val raw = """{"type":"task_assignment","taskId":"9","subject":"P9: E2E screenshots, docs, final review","description":"After P8 merge: run CDP headless-Chrome e2e per phase-09.","assignedBy":"team-lead","timestamp":"2026-09-03T00:19:07.516Z"}"""
        val out = TeamMessagePreview.of(raw)
        assertEquals("P9: E2E screenshots, docs, final review", out.title)
        assertEquals("After P8 merge: run CDP headless-Chrome e2e per phase-09.", out.detail)
        assertEquals("9", out.taskId)
        assertFalse(out.title.contains("{\""))
    }

    @Test
    fun `the author's summary outranks the payload, and the headline is never the body too`() {
        assertEquals("from author", TeamMessagePreview.of("""{"type":"task_assignment","subject":"from payload"}""", "from author").title)
        val only = TeamMessagePreview.of("""{"type":"completion","description":"only field present"}""")
        assertEquals("only field present", only.title)
        assertNull(only.detail)
    }

    @Test
    fun `a shutdown heads with its reason and a plan reply with its feedback`() {
        assertEquals("work finished", TeamMessagePreview.of("""{"type":"shutdown_request","reason":"work finished"}""").title)
        assertEquals("looks good, proceed", TeamMessagePreview.of("""{"type":"plan_approval_response","approve":true,"feedback":"looks good, proceed"}""").title)
    }

    @Test
    fun `prose heads with its first line and keeps the rest as the body`() {
        val out = TeamMessagePreview.of("Done with phase 3\nAll tests pass\nSee report")
        assertEquals("Done with phase 3", out.title)
        assertEquals("All tests pass\nSee report", out.detail)
        val ack = TeamMessagePreview.of("ack")
        assertEquals("ack", ack.title)
        assertNull(ack.detail)
    }

    @Test
    fun `a payload no field matches reads as key and value, else as its type`() {
        assertEquals("phase: 7 · branch: feat/x", TeamMessagePreview.of("""{"type":"future_kind","phase":"7","branch":"feat/x"}""").title)
        assertEquals("idle_notification", TeamMessagePreview.of("""{"type":"idle_notification"}""").title)
    }

    @Test
    fun `empty text, a scalar and an array`() {
        assertEquals("", TeamMessagePreview.of("").title)
        assertEquals("", TeamMessagePreview.of("   ").title)
        assertEquals("labelled", TeamMessagePreview.of("", "labelled").title)
        assertEquals("42", TeamMessagePreview.of("42").title)
        assertEquals("""["a","b"]""", TeamMessagePreview.of("""["a","b"]""").title)
    }

    @Test
    fun `a snake case task id counts`() {
        val out = TeamMessagePreview.of("""{"type":"task_assignment","task_id":"4","subject":"Wave 1 integration"}""")
        assertEquals("4", out.taskId)
        assertEquals("Wave 1 integration", out.title)
    }
}
