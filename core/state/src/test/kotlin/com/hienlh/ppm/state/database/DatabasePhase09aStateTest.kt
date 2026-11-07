package com.hienlh.ppm.state.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabasePhase09aStateTest {

    @Test
    fun parseAndBuildDbUrl_preservesSchemeSocketIpv6AndSslFlags() {
        // 1. PostgreSQL Unix socket URL (`?host=/var/run/postgresql`)
        val pgSock = parseDbUrl("postgresql://app@/shop?host=/var/run/postgresql&sslmode=prefer&application_name=ppm")
        assertTrue(pgSock is DbUrlParseResult.Url)
        val pgParts = (pgSock as DbUrlParseResult.Url).parts
        assertEquals("postgres", pgParts.type)
        assertEquals("postgresql", pgParts.scheme)
        assertEquals("", pgParts.host)
        assertEquals("/var/run/postgresql", pgParts.socket)
        assertEquals("app", pgParts.user)
        assertEquals("shop", pgParts.database)
        assertEquals(DbSslParam("sslmode", "prefer"), pgParts.ssl)
        assertEquals(listOf("application_name" to "ppm"), pgParts.params)
        assertEquals(
            "postgresql://app@/shop?host=/var/run/postgresql&sslmode=prefer&application_name=ppm",
            buildDbUrl(pgParts),
        )

        // 2. IPv6 with unencoded '@' in password and SSL flags
        val ipv6 = parseDbUrl("mysql://admin:p@ss:word@[::1]:3307/analytics?ssl-mode=VERIFY_IDENTITY")
        assertTrue(ipv6 is DbUrlParseResult.Url)
        val mParts = (ipv6 as DbUrlParseResult.Url).parts
        assertEquals("::1", mParts.host)
        assertEquals(3307, mParts.port)
        assertEquals("admin", mParts.user)
        assertEquals("p@ss:word", mParts.password)
        val flags = sslFlags(mParts.ssl)
        assertTrue(flags.useSsl)
        assertTrue(flags.rejectUnauthorized)

        // 3. Round-trip Fields <-> URL switching preserves extra query params & scheme
        val initialForm = applyUrlPartsToForm(ConnectionFormValues(type = "postgres", entry = "fields"), pgParts)
        val (urlForm, prob1) = switchFormEntryMode(initialForm, "url")
        assertNull(prob1)
        assertNotNull(urlForm)
        assertEquals(
            "postgresql://app@/shop?host=/var/run/postgresql&sslmode=prefer&application_name=ppm",
            urlForm!!.url,
        )
        val (backToFields, prob2) = switchFormEntryMode(urlForm, "fields")
        assertNull(prob2)
        assertEquals("socket", backToFields!!.connMode)
        assertEquals("/var/run/postgresql", backToFields.socket)
    }

    @Test
    fun filterAllowedDatabases_supportsJsBracketCaretRegexAndAbortsCatastrophicBacktracking() {
        val names = listOf("prod_core", "staging_core", "dev_analytics", "test_db")

        // JS `[^]` construct matches any character
        val jsFiltered = filterAllowedDatabases(
            names = names,
            allowedDatabases = emptyList(),
            allowedDatabasesRegex = "^prod[^]*core$",
        )
        assertEquals(listOf("prod_core"), jsFiltered)

        // Catastrophic backtracking regex `(a+)+$` on a 60-'a' non-matching string must not hang
        val evilName = "a".repeat(60) + "!"
        val startMs = System.currentTimeMillis()
        val evilResult = filterAllowedDatabases(
            names = listOf(evilName, "ok_db"),
            allowedDatabases = emptyList(),
            allowedDatabasesRegex = "^(a+)+$",
        )
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue("ReDoS guard should finish under 500ms, took ${elapsedMs}ms", elapsedMs < 500L)
        assertTrue(evilResult.isEmpty())
    }

    @Test
    fun connectionAndObjectTreeRows_paginateGroupsAndDeduplicateImportedNames() {
        val taken = setOf("Prod", "Prod (2)")
        assertEquals("Prod (3)", resolveImportedConnectionName("Prod", taken))

        val objects = (1..250).map { idx ->
            DbObjectItem(schema = "public", name = "table_$idx", kind = "table", rowEstimate = idx * 100L)
        }
        val rows = buildObjectTreeRows(
            ref = DbRef(conn = 1, database = "shop"),
            list = DbObjectListState(schemas = listOf("public"), objects = objects),
            schema = "public",
            openGroups = setOf("table"),
        )
        // 1 Group header + 200 ObjectEntry rows + 1 MoreEntries(hiddenCount = 50)
        assertEquals(202, rows.size)
        val more = rows.last() as ObjectTreeRow.MoreEntries
        assertEquals(50, more.hiddenCount)
        assertEquals("~1.5k", formatRowEstimate(1_500L))
        assertEquals("~13k", formatRowEstimate(12_500L))
    }
}
