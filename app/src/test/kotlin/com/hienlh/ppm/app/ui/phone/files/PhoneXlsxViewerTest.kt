package com.hienlh.ppm.app.ui.phone.files

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.XlsxSheet
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The phone's read-only workbook preview (`xlsx-preview.tsx` + `xlsx-preview-model.ts`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneXlsxViewerTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private lateinit var model: AppModel

    private fun sheetJson(sheet: Int, value: String) =
        """{"ok":true,"data":{"sheets":["Sales","Notes"],"sheet":$sheet,"rows":[["Region","Total"],["$value",null]],"columns":2,"truncatedRows":true,"truncatedColumns":false}}"""

    @Volatile private var firstValue = "North"

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val sheet = request.url.queryParameter("sheet")?.toIntOrNull() ?: 0
                val body = when {
                    request.url.encodedPath.endsWith("/files/xlsx-sheet") -> sheetJson(sheet, if (sheet == 0) firstValue else "Memo")
                    else -> """{"ok":true,"data":{}}"""
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "t")
    }

    @After
    fun stop() = server.close()

    private fun recorded(suffix: String) = synchronized(requests) { requests.filter { it.url.encodedPath.endsWith(suffix) } }
    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun textShown(text: String) = composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an xlsx tab shows the sheet as a grid, a tapped cell in the bar, and the sheet tabs`() {
        val tab = WorkspaceTab(
            id = "editor:data/q3.xlsx", type = "editor", title = "q3.xlsx", projectId = "ppm",
            metadata = buildJsonObject { put("filePath", JsonPrimitive("data/q3.xlsx")); put("projectName", JsonPrimitive("ppm")) },
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) { PhoneFileTabHost(model = model, tab = tab) }
        }
        composeRule.waitUntil(5_000) { shown("phone-xlsx-cell-A2") }
        val first = recorded("/files/xlsx-sheet").single()
        assertEquals("/api/project/ppm/files/xlsx-sheet", first.url.encodedPath)
        assertEquals("data/q3.xlsx", first.url.queryParameter("path"))
        assertEquals("0", first.url.queryParameter("sheet"))
        assertTrue("the workbook is never read as the tab's text", recorded("/files/read").isEmpty() && recorded("/files/raw").isEmpty())
        assertTrue(textShown("Showing the first 2 rows"))
        assertTrue(textShown("Tap a cell to see its full value"))

        composeRule.onNodeWithTag("phone-xlsx-cell-A2").performClick()
        composeRule.waitUntil(2_000) { shown("phone-xlsx-cell-value") }
        assertTrue(textShown("North"))
        composeRule.onNodeWithTag("phone-xlsx-cell-ref", useUnmergedTree = true).assertExistsWithText("A2")

        // The file changes on the server: the same sheet is read again, the picked cell kept.
        firstValue = "South"
        composeRule.runOnIdle {
            ReflectionHelpers.getField<MutableSharedFlow<Pair<String, String?>>>(model, "fileChanged").tryEmit("ppm" to "data/q3.xlsx")
        }
        composeRule.waitUntil(5_000) { recorded("/files/xlsx-sheet").size == 2 && textShown("South") }
        assertTrue(shown("phone-xlsx-cell-value"))

        // Another sheet starts with nothing picked.
        composeRule.onNodeWithTag("phone-xlsx-sheet-1").performClick()
        composeRule.waitUntil(5_000) { textShown("Memo") }
        assertEquals("1", recorded("/files/xlsx-sheet").last().url.queryParameter("sheet"))
        assertFalse(shown("phone-xlsx-cell-value"))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsWithText(text: String) {
        val node = fetchSemanticsNode()
        val texts = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.map { it.text }
        assertEquals(listOf(text), texts)
    }

    private fun sheet(sheet: Int, value: String = "v") =
        XlsxSheet(listOf("A", "B"), sheet, listOf(listOf(value)), 1, truncatedRows = false, truncatedColumns = false)

    @Test
    fun `a failed reload keeps the sheet, and a sheet that is gone falls back to the first`() = runTest {
        val answers = mutableMapOf<Int, ArrayDeque<() -> XlsxSheet>>()
        val asked = mutableListOf<Int>()
        val controller = PhoneXlsxController({ s -> asked += s; answers.getValue(s).removeFirst()() }, backgroundScope)
        answers[0] = ArrayDeque(listOf({ sheet(0) }, { throw PpmError.Server(400, "Not a valid .xlsx file.") }, { sheet(0, "first") }))
        answers[1] = ArrayDeque(listOf({ throw PpmError.Server(404, "Sheet not found") }))
        controller.start()
        runCurrent()
        controller.select(XlsxCell(0, 0))
        controller.fileChanged()
        runCurrent()
        assertEquals(0, controller.state.value.data?.sheet)
        assertEquals("Not a valid .xlsx file.", controller.state.value.error)
        assertEquals(XlsxCell(0, 0), controller.state.value.selected)

        controller.pick(1)
        runCurrent()
        assertEquals(listOf(0, 0, 1, 0), asked)
        assertEquals("first", controller.state.value.data?.rows?.single()?.single())
        assertNull(controller.state.value.error)
        assertNull(controller.state.value.pending)
    }

    @Test
    fun `only the latest sheet asked for is applied`() = runTest {
        val slow = CompletableDeferred<XlsxSheet>()
        val controller = PhoneXlsxController({ s -> if (s == 1) slow.await() else sheet(s) }, backgroundScope)
        controller.start()
        runCurrent()
        controller.pick(1)
        runCurrent()
        assertEquals(1, controller.state.value.litSheet)
        controller.pick(0)
        runCurrent()
        slow.complete(sheet(1))
        runCurrent()
        assertEquals(0, controller.state.value.data?.sheet)
    }

    @Test
    fun `columns are lettered and sized as the web sizes them`() {
        assertEquals(listOf("A", "Z", "AA", "AZ", "BA", "ZZ", "AAA"), listOf(0, 25, 26, 51, 52, 701, 702).map(::xlsxColumnLetters))
        assertEquals(listOf(64, 90, 320), xlsxColumnWidths(listOf(listOf("x", "a".repeat(10), "b".repeat(100)), listOf(null, "short\nlines")), 3))
        assertEquals(0, xlsxSheetAfterError(404, 2))
        assertNull(xlsxSheetAfterError(404, 0))
        assertNull(xlsxSheetAfterError(400, 2))
        assertTrue(isPhoneXlsxPath("a/B.XLSM") && isPhoneXlsxPath("q.xlsx") && !isPhoneXlsxPath("old.xls"))
    }
}
