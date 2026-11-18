package com.hienlh.ppm.app.ui.settings.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsPasswordInput
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.app.ui.assistant.CardButton
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AssistantMcpServer
import com.hienlh.ppm.state.assistant.McpServerCheck
import com.hienlh.ppm.state.assistant.McpServerForm
import com.hienlh.ppm.state.assistant.checkMcpServer
import com.hienlh.ppm.state.assistant.mcpKeepsSaved
import com.hienlh.ppm.state.assistant.mcpValuePlaceholder

/**
 * Add or edit one of the Assistant's MCP servers (`assistant-mcp-server-dialog.tsx`). Env and
 * header values are secrets this app never receives: a saved one shows as a placeholder and
 * leaving it blank keeps it — except a header once the URL points at another origin, which the
 * server will not send there. Values are typed into hidden fields and live only in this draft.
 */
@Composable
internal fun AssistantMcpServerDialog(
    server: AssistantMcpServer?,
    savedKeys: Set<String>,
    takenNames: Set<String>,
    onClose: () -> Unit,
    onDone: (AssistantMcpServer) -> Unit,
) {
    var form by remember(server) { mutableStateOf(McpServerForm.of(server)) }
    var error by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(setOf<Int>()) }
    val stdio = form.stdio
    val sameTransport = server?.transport == form.transport
    val keepsSaved = mcpKeepsSaved(server, form)
    fun done() {
        when (val check = checkMcpServer(server, form, savedKeys, takenNames)) {
            is McpServerCheck.Problem -> error = check.error
            is McpServerCheck.Ok -> onDone(check.server)
        }
    }
    fun update(i: Int, key: String? = null, value: String? = null) {
        form = form.copy(pairs = form.pairs.mapIndexed { idx, p -> if (idx == i) (key ?: p.first) to (value ?: p.second) else p })
    }
    AsstDialog(
        title = if (server != null) "Edit MCP server" else "Add MCP server",
        description = "Only PPM Assistant sessions use this server. Every one of its tools asks you before it runs.",
        onClose = onClose,
        footer = {
            CardButton("Cancel", onClose, Modifier.weight(1f))
            CardButton("Done", ::done, Modifier.weight(1f).testTag("asst-mcp-done"), variant = CardButtonVariant.PRIMARY)
        },
    ) {
        Field("Name") {
            SettingsTextInput(form.name, { form = form.copy(name = it) }, "github", Modifier.fillMaxWidth().testTag("asst-mcp-name"))
        }
        Field("Transport") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((t, label) in listOf(AssistantMcpServer.STDIO to "Command (stdio)", AssistantMcpServer.HTTP to "HTTP")) {
                    SettingsButton(label, primary = form.transport == t, modifier = Modifier.weight(1f).testTag("asst-mcp-transport-$t")) {
                        form = form.copy(transport = t)
                    }
                }
            }
        }
        if (stdio) {
            Field("Command") {
                SettingsTextInput(form.command, { form = form.copy(command = it) }, "npx", Modifier.fillMaxWidth().testTag("asst-mcp-command"))
            }
            Field("Arguments, one per line") {
                SettingsTextInput(
                    form.args,
                    { form = form.copy(args = it) },
                    "-y\n@modelcontextprotocol/server-github",
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("asst-mcp-args"),
                    mono = true,
                    singleLine = false,
                )
            }
        } else {
            Field("URL") {
                SettingsTextInput(form.url, { form = form.copy(url = it) }, "https://mcp.example.com/mcp", Modifier.fillMaxWidth().testTag("asst-mcp-url"))
            }
        }
        Field("${if (stdio) "Environment variables" else "Headers"} (kept on the server, never shown again)") {
            form.pairs.forEachIndexed { i, (key, value) ->
                val saved = sameTransport && key.trim() in savedKeys
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTextInput(key, { update(i, key = it) }, if (stdio) "NAME" else "Header", Modifier.weight(1f).testTag("asst-mcp-key-$i"))
                    SettingsPasswordInput(
                        value = value,
                        onValueChange = { update(i, value = it) },
                        placeholder = mcpValuePlaceholder(saved, keepsSaved),
                        visible = i in shown,
                        onToggleVisible = { shown = if (i in shown) shown - i else shown + i },
                        modifier = Modifier.weight(1f),
                        inputTestTag = "asst-mcp-value-$i",
                    )
                    AsstIconButton(ProductIcon.X, "Remove") {
                        form = form.copy(pairs = form.pairs.filterIndexed { idx, _ -> idx != i })
                        shown = emptySet()
                    }
                }
            }
            SettingsButton(if (stdio) "+ Add variable" else "+ Add header", modifier = Modifier.testTag("asst-mcp-add-pair")) {
                form = form.copy(pairs = form.pairs + ("" to ""))
            }
        }
        error?.let { BasicText(it, Modifier.testTag("asst-mcp-error"), ppmText(12f).copy(color = LocalPpmTokens.current.error.color)) }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = asstNote())
        content()
    }
}
