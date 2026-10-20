package com.hienlh.ppm.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `tests/unit/web/score-file-search.test.ts`, ported. */
class FileSearchTest {
    private fun ranked(query: String, candidates: List<Pair<String, String>>): List<String> =
        candidates.mapNotNull { (label, path) -> scoreFileSearch(query, label, path)?.let { label to it } }
            .sortedBy { it.second }
            .map { it.first }

    @Test
    fun `each tier, and no match`() {
        assertNull(scoreFileSearch("xyz", "README.md", "README.md"))
        assertEquals(0, scoreFileSearch("readme.md", "README.md", "README.md")?.tier)
        assertEquals(1, scoreFileSearch("read", "README.md", "README.md")?.tier)
        assertEquals(2, scoreFileSearch("adme", "README.md", "README.md")?.tier)
        assertEquals(3, scoreFileSearch("260419", "plan.md", "plans/260419/plan.md")?.tier)
        assertEquals(4, scoreFileSearch("rdm", "README.md", "README.md")?.tier)
        assertEquals(5, scoreFileSearch("plnmd", "index.ts", "plans/some/plan.md/index.ts")?.tier)
    }

    @Test
    fun `README first among dated plan files, however it is typed`() {
        val candidates = listOf(
            "implementation-260419-16..." to "docs/journals/implementation-260419",
            "phase-01-types-and-state..." to "plans/260419-2309-workflow-engine/phase-01",
            "phase-02-node-registry-a..." to "plans/260419-2309-workflow-engine/phase-02",
            "phase-02-create-video-co..." to "plans/260419-2334-integrate-editly/phase-02",
            "phase-03-add-compose-c..." to "plans/260419-2334-integrate-editly/phase-03",
            "docs-manager-260419-16..." to "plans/reports/docs-manager-260419",
            "project-manager-260420-..." to "plans/reports/project-manager-260420",
            "README.md" to "README.md",
        )
        for (query in listOf("readme.md", "readme", "README")) assertEquals(query, "README.md", ranked(query, candidates).first())
    }

    @Test
    fun `exact beats prefix beats substring, and a shorter name wins a tie`() {
        val candidates = listOf(
            "config.service.ts" to "src/services/config.service.ts",
            "config-validation.test.ts" to "tests/unit/config-validation.test.ts",
            "some-config-helper.ts" to "src/lib/some-config-helper.ts",
            "package.json" to "package.json",
        )
        assertEquals("package.json", ranked("package.json", candidates).first())
        val config = ranked("config", candidates)
        assertEquals(listOf("config.service.ts", "config-validation.test.ts"), config.take(2))
        assertTrue(config.indexOf("some-config-helper.ts") > 1)
        val utils = listOf("utils.ts" to "src/utils.ts", "test-utils.ts" to "src/test-utils.ts", "utils-helper.ts" to "src/lib/utils-helper.ts")
        assertEquals("utils.ts", ranked("utils", utils).first())
    }

    @Test
    fun `every word of a query has to match`() {
        assertEquals(2, scoreFileSearch("chat ai", "New AI Chat", "new ai chat ai assistant claude")?.tier)
        assertNull(scoreFileSearch("chat xyz", "New AI Chat", "new ai chat"))
    }
}
