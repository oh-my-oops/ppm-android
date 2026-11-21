package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rule a Claude tool card offers (`approvalFromWire` in approval-request.ts). */
class ApprovalRuleTest {
    @Test
    fun `reads the rule off the wire`() {
        val request = ApprovalRequest.from(WireJson.parse("""{"requestId":"r","tool":"Bash","input":{},"rule":{"label":"`git status` commands","project":true}}"""))
        assertEquals(ApprovalRule("`git status` commands", project = true), request?.rule)
    }

    @Test
    fun `a project flag that is not true is no project`() {
        val request = ApprovalRequest.from(WireJson.parse("""{"requestId":"r","tool":"Bash","input":{},"rule":{"label":"WebFetch"}}"""))
        assertEquals(ApprovalRule("WebFetch", project = false), request?.rule)
    }

    @Test
    fun `a rule with no label, or none at all, is none`() {
        assertNull(ApprovalRequest.from(WireJson.parse("""{"requestId":"r","tool":"Bash","input":{},"rule":{"label":3}}"""))?.rule)
        assertNull(ApprovalRequest.from(WireJson.parse("""{"requestId":"r","tool":"Bash","input":{}}"""))?.rule)
    }
}
