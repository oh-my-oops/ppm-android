package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.fsInfo
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostFileInfo
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.NumberFormat

@Composable
internal fun HostFileProperties(api: ApiClient, entry: HostFileEntry, platform: String?, onDismiss: () -> Unit) {
    var info by remember(api, entry.path) { mutableStateOf<HostFileInfo?>(null) }
    var error by remember(api, entry.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(api, entry.path) {
        try { info = api.fsInfo(entry.path) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = (failure as? PpmError.Server)?.hint ?: failure.message ?: "Could not read properties" }
    }
    HostExplorerDialog(entry.name, "Properties", onDismiss) {
        error?.let { BasicText(it, style = ppmText(14f).copy(color = LocalPpmTokens.current.error.color)) }
        if (info == null && error == null) BasicText("Reading…", style = ppmText(14f).copy(color = LocalPpmTokens.current.text2.color))
        info?.let { stat ->
            SelectionContainer {
                Column {
                    Property("Name", stat.name)
                    Property("Kind", when (stat.kind) { "file" -> "File"; "directory" -> "Folder"; "symlink" -> "Symbolic link"; else -> "Unknown" })
                    Property("Location", stat.path.trimEnd('/', '\\').let { it.substringBeforeLast(if ('\\' in it) '\\' else '/', "/") })
                    Property(if (stat.kind == "directory") "Contains" else "Size", if (stat.kind == "directory")
                        "${stat.childCount ?: 0}${if (stat.truncated) "+" else ""} item${if (stat.childCount == 1) "" else "s"}"
                    else "${hostDisplaySize(stat.size)} (${NumberFormat.getIntegerInstance().format(stat.size)} bytes)")
                    Property("Created", displayDate(stat.created)); Property("Modified", displayDate(stat.modified))
                    Property("Permissions", if (platform == "win32") { if (stat.readOnly) "Read-only" else "Read / write" }
                        else (stat.mode and 511).toString(8).padStart(3, '0') + if (stat.readOnly) " (read-only)" else "")
                    Property("Hidden", if (stat.hidden) "Yes" else "")
                    Property("Links to", stat.target.orEmpty())
                }
            }
        }
        HostDialogButton("Close", onClick = onDismiss)
    }
}

@Composable
internal fun HostExplorerDialog(title: String, description: String? = null, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).clip(shape).background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape).cssScrollY().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(title, style = ppmText(18f, FontWeight.SemiBold).copy(color = tokens.text.color))
            description?.let { BasicText(it, style = ppmText(14f).copy(color = tokens.text2.color)) }
            content()
        }
    }
}

@Composable
internal fun HostDialogButton(label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    BasicText(label, Modifier.clip(shape)
        .then(if (danger) Modifier.background(tokens.error.color) else Modifier.cssBorder(tokens.border.color, shape))
        .ppmClickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        style = ppmText(14f, FontWeight.Medium).copy(color = if (danger) Color.White else tokens.text.color))
}

@Composable
private fun Property(label: String, value: String) {
    if (value.isEmpty()) return
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label, Modifier.width(90.dp), style = ppmText(14f).copy(color = tokens.text2.color))
        BasicText(value, Modifier.weight(1f), style = ppmText(14f).copy(color = tokens.text.color))
    }
}
private fun displayDate(value: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.parse(value).atZone(ZoneId.systemDefault()))
}.getOrDefault("")
