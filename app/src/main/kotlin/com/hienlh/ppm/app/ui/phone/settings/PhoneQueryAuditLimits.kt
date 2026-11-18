package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.ConfirmDialog
import com.hienlh.ppm.app.ui.SettingsActionButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.client.QueryAuditUsage
import com.hienlh.ppm.client.queryAuditUsage
import com.hienlh.ppm.client.settingsClearQueryAuditLogs
import com.hienlh.ppm.client.settingsPutQueryAudit
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * The Query Audit log's two limits, Save and Clear, as `query-audit-section.tsx` lays them out
 * below `md`: the two fields side by side, the size on disk beside Save, then a full-width
 * outline Clear that asks first. [onChanged] gets what the server holds after a save or a clear.
 */
@Composable
internal fun PhoneQueryAuditLimits(model: AppModel, usage: QueryAuditUsage, onChanged: (QueryAuditUsage) -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var days by remember(usage.retentionDays) { mutableStateOf(usage.retentionDays.toString()) }
    var sizeMb by remember(usage.maxSizeMb) { mutableStateOf(usage.maxSizeMb.toString()) }
    var saving by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    val dirty = days != usage.retentionDays.toString() || sizeMb != usage.maxSizeMb.toString()
    val label = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Keep for (days)", style = label)
                SettingsTextInput(days, { days = it }, "", Modifier.fillMaxWidth().testTag("phone-query-audit-days"), keyboardType = KeyboardType.Number)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Max size (MB)", style = label)
                SettingsTextInput(sizeMb, { sizeMb = it }, "", Modifier.fillMaxWidth().testTag("phone-query-audit-size"), keyboardType = KeyboardType.Number)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                "${NumberFormat.getIntegerInstance().format(usage.entryCount)} entries · ${queryAuditSize(usage.sizeBytes)} on disk",
                Modifier.weight(1f),
                ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
            )
            SettingsActionButton(
                label = "Save",
                busy = saving,
                enabled = dirty && !saving,
                modifier = Modifier.testTag("phone-query-audit-save"),
            ) {
                saving = true
                scope.launch {
                    try {
                        // `Number(days)`: what is typed goes as it is, and the server says what it refuses.
                        val api = model.api ?: error("Not connected")
                        api.settingsPutQueryAudit(days.trim().toInt(), sizeMb.trim().toInt())
                        onChanged(api.queryAuditUsage())
                        model.toast(ErrorToastMessage("Audit retention updated", kind = ErrorToastMessage.Kind.SUCCESS))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        model.toast(ErrorToastMessage("Could not save", e.message))
                    } finally {
                        saving = false
                    }
                }
            }
        }
        SettingsActionButton(
            label = "Clear all recorded queries",
            outline = true,
            icon = ProductIcon.Trash2,
            busy = clearing,
            enabled = !clearing && usage.entryCount > 0,
            modifier = Modifier.fillMaxWidth().testTag("phone-query-audit-clear"),
        ) { confirmingClear = true }
    }

    if (confirmingClear) {
        ConfirmDialog(
            title = "Clear the Query Audit log?",
            message = "Delete every recorded query? This cannot be undone.",
            confirm = "Clear",
            confirmTestTag = "phone-query-audit-clear-confirm",
            onConfirm = {
                confirmingClear = false
                clearing = true
                scope.launch {
                    try {
                        val api = model.api ?: error("Not connected")
                        api.settingsClearQueryAuditLogs()
                        onChanged(api.queryAuditUsage())
                        model.toast(ErrorToastMessage("Query audit log cleared", kind = ErrorToastMessage.Kind.SUCCESS))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        model.toast(ErrorToastMessage("Could not clear the log", e.message))
                    } finally {
                        clearing = false
                    }
                }
            },
            onDismiss = { confirmingClear = false },
        )
    }
}
