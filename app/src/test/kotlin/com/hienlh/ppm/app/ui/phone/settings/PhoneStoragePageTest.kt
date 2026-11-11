package com.hienlh.ppm.app.ui.phone.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.StorageBackups
import com.hienlh.ppm.client.StorageNewestBackup
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Settings → Storage on a phone (`storage-settings-section.tsx`), read-only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp-xxhdpi")
class PhoneStoragePageTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    @Volatile private var overviewAnswers = true
    @Volatile private var storageReads = 0
    private lateinit var model: AppModel

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (status, body) = when (request.url.encodedPath) {
                    "/api/settings/storage" -> {
                        storageReads++
                        if (overviewAnswers) {
                            200 to """{"ok":true,"data":{"appDb":{"path":"/home/me/.ppm/ppm.db","sizeBytes":5242880},
                            "backups":{"dir":"/home/me/.ppm-backups","dbStem":"ppm","count":12,"totalBytes":62914560,"newest":null,
                              "others":[{"dbStem":"ppm.dev","count":3,"totalBytes":1048576}]},
                            "sessionTrace":{"path":"/home/me/.ppm/session-trace.db","sizeBytes":786432000,"oldestTs":null,"retention_days":14,"max_size_mb":500}}}"""
                        } else {
                            404 to """{"ok":false,"error":"Not found"}"""
                        }
                    }
                    "/api/settings/query-audit" -> 200 to """{"ok":true,"data":{"retention_days":30,"max_size_mb":100,"size_bytes":2097152,"entry_count":1234}}"""
                    else -> 200 to """{"ok":true,"data":{}}"""
                }
                return MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "t")
    }

    @After
    fun stop() = server.close()

    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun textShown(text: String) = composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the page shows what each of PPM's files takes, and the trace over its cap`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) { PhoneStoragePage(model = model, onBack = {}) }
        }
        composeRule.waitUntil(5_000) { textShown("/home/me/.ppm/ppm.db") && textShown("entries") }
        assertTrue(textShown("5.0 MB"))
        assertTrue(textShown("Verified snapshots of ppm.db"))
        assertTrue(textShown("12 · 60 MB"))
        assertTrue(textShown("None yet"))
        assertTrue(textShown("ppm.dev.db: 3 · 1.0 MB"))
        assertTrue(textShown("750 MB of 500 MB"))
        assertTrue(textShown("No events yet"))
        assertTrue(textShown("1,234 entries · 2.0 MB on disk"))
        val meter = composeRule.onNodeWithTag("phone-storage-trace-meter", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(1f, meter.config[SemanticsProperties.ProgressBarRangeInfo].current)

        composeRule.onNodeWithTag("phone-storage-refresh").performClick()
        composeRule.waitUntil(5_000) { storageReads == 2 }
    }

    @Test
    fun `a server without the overview still shows the Query Audit log`() {
        overviewAnswers = false
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) { PhoneStoragePage(model = model, onBack = {}) }
        }
        composeRule.waitUntil(5_000) { shown("phone-storage-error") && textShown("entries") }
        assertTrue(textShown("Could not load storage details: Not found"))
        assertFalse(shown("phone-storage-app-db"))
    }

    @Test
    fun `sizes and dates read as the web writes them`() {
        assertEquals(listOf("512 B", "1.0 KB", "10 KB", "1.5 MB", "2.0 GB"), listOf(512L, 1024L, 10240L, 1572864L, 2147483648L).map(::storageBytes))
        assertEquals("—", storageBytes(-1))
        assertEquals(listOf("0 KB", "1023 KB", "1.0 MB"), listOf(0L, 1047552L, 1048576L).map(::queryAuditSize))
        assertEquals(TraceUsage("250 MB of 500 MB", 0.5f, false), traceUsage(262144000, 500))
        assertTrue(traceUsage(600L * 1024 * 1024, 500).over)
        val now = 1_762_830_000_000L
        assertEquals("just now", storageRelativeDate(now - 30_000, now))
        assertEquals("5m ago", storageRelativeDate(now - 5 * 60_000, now))
        assertEquals("3h ago", storageRelativeDate(now - 3 * 3_600_000, now))
        assertEquals("2d ago", storageRelativeDate(now - 2 * 86_400_000, now))
        assertTrue(storageRelativeDate(now - 30L * 86_400_000, now, Locale.US).matches(Regex("""[A-Z][a-z]{2} \d{1,2}""")))
        val backups = StorageBackups("d", "ppm", 1, 1, StorageNewestBackup("ppm-a.db", now - 2 * 3_600_000), emptyList())
        assertTrue(storageNewestBackup(backups, now).startsWith("ppm-a.db · 2h ago ("))
    }
}
