package com.hienlh.ppm.state.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delegate rule an older project is offered (`manager-default-rules.test.ts`): found however the
 * file's lines are wrapped, added once, before the first rule of the opening section and never
 * inside a later section whose heading may change what a line under it means.
 */
class ManagerDefaultRulesTest {
    /** The head of PPM's template as it was before the rule, and as it is now. */
    private val head = "# Manager rules\n\nThese are the rules this project's manager chat works by.\n\n"
    private val oldTemplate = head + "- Before anything that reaches a person, report needs_decision.\n- Never deploy without an OK.\n"
    private val newTemplate = head + MANAGER_DELEGATE_RULE + "\n- Before anything that reaches a person, report needs_decision.\n- Never deploy without an OK.\n"

    @Test
    fun `is found rewrapped or reworded after its opening sentence`() {
        assertTrue(hasManagerDelegateRule(newTemplate))
        assertFalse(hasManagerDelegateRule(oldTemplate))
        assertTrue(hasManagerDelegateRule("- Manage the work;\n  do not do it yourself. Children do it.\n"))
        assertTrue(hasManagerDelegateRule("- manage THE work; do not do it yourself."))
        assertFalse(hasManagerDelegateRule("- Manage the work.\n"))
    }

    @Test
    fun `goes back where the template has it, once`() {
        val once = withManagerDelegateRule(oldTemplate)
        assertEquals(newTemplate, once)
        assertEquals(once, withManagerDelegateRule(once))
        assertEquals(2, once.split("Manage the work; do not do it yourself.").size)
    }

    @Test
    fun `goes before the first rule of an untitled file`() {
        assertEquals("$MANAGER_DELEGATE_RULE\n- a\n- b\n", withManagerDelegateRule("- a\n- b\n"))
    }

    @Test
    fun `never lands inside a later section, going above its heading when no rule comes before it`() {
        val rules = "# Manager rules\n\nIntro.\n## Allowed without asking\n- run tests\n"
        assertEquals(
            "# Manager rules\n\nIntro.\n\n$MANAGER_DELEGATE_RULE\n\n## Allowed without asking\n- run tests\n",
            withManagerDelegateRule(rules),
        )
    }

    @Test
    fun `is appended after prose with no rule, and is the whole file when the file is empty`() {
        assertEquals("# Rules\nBe careful.\n\n$MANAGER_DELEGATE_RULE\n", withManagerDelegateRule("# Rules\nBe careful."))
        assertEquals("$MANAGER_DELEGATE_RULE\n", withManagerDelegateRule(""))
        assertEquals("$MANAGER_DELEGATE_RULE\n", withManagerDelegateRule("\n  \n"))
    }

    @Test
    fun `the dialog shows the rule as one paragraph without its list marker`() {
        assertTrue(MANAGER_DELEGATE_RULE_PROSE.startsWith("Manage the work; do not do it yourself. Hand every piece"))
        assertFalse(MANAGER_DELEGATE_RULE_PROSE.contains("\n"))
        assertFalse(MANAGER_DELEGATE_RULE_PROSE.contains("  "))
        assertTrue(MANAGER_DELEGATE_RULE_PROSE.endsWith("the project's task list and cards."))
    }
}
