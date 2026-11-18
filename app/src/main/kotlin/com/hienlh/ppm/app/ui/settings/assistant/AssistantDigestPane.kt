package com.hienlh.ppm.app.ui.settings.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.assistantSettings
import com.hienlh.ppm.client.patchAssistantSettings
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AssistantSettingsView
import com.hienlh.ppm.state.assistant.isClockTime
import com.hienlh.ppm.state.assistant.zoneLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/*
 * Settings → PPM Assistant → Digest & quiet hours (`assistant-digest-quiet-section.tsx`): when PPM
 * wakes or holds the Assistant on its own, plus the restart-news switch. Saving a time also sends
 * this device's zone; the zone the times are read in is shown under them.
 */

internal const val DIGEST_HINT = "Asks each manager chat (queued while it is busy), then sends one digest."
internal const val QUIET_HINT = "Messages are held until quiet hours end. Urgent items go out at once."
internal const val RESTART_NEWS_LABEL = "Tell the Assistant after PPM restarts"
internal const val RESTART_NEWS_HINT = "After every restart, PPM wakes the Assistant with why it restarted and which chats were cut off, so it can ask their managers to continue."

private fun deviceZone(): String? = runCatching { ZoneId.systemDefault().id }.getOrNull()

@Composable
internal fun AssistantDigestPane(model: AppModel, api: ApiClient) {
    val scope = rememberCoroutineScope()
    var saved by remember(api) { mutableStateOf<AssistantSettingsView?>(null) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(api) {
        try {
            saved = api.assistantSettings().settings
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load the Assistant's settings"
        }
    }
    val s = saved
    if (s == null) {
        AsstLoading(error)
        return
    }
    // A time is read in the zone it was saved in, so saving one sends this device's zone; the
    // restart-news switch holds no time and leaves the saved zone alone.
    fun save(patch: JsonObject, withZone: Boolean) {
        saving = true
        scope.launch {
            try {
                val body = if (withZone) JsonObject(patch + listOfNotNull(deviceZone()?.let { "timezone" to JsonPrimitive(it) })) else patch
                saved = api.patchAssistantSettings(body).settings
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not save", e.message))
            } finally {
                saving = false
            }
        }
    }
    val digest = s.digest
    val quiet = s.quietHours
    Column(Modifier.fillMaxWidth().testTag("assistant-digest-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsstCard(ProductIcon.Coffee, "Digest & quiet hours") {
            AsstLine("Morning digest", DIGEST_HINT) {
                TimeField("Digest time", digest.time, !saving, "asst-digest-time") { save(buildJsonObject { put("digest", digest.copy(time = it).toJson()) }, true) }
                Box(Modifier.testTag("asst-digest-switch")) {
                    PpmSwitch(digest.enabled, { save(buildJsonObject { put("digest", digest.copy(enabled = it).toJson()) }, true) }, enabled = !saving)
                }
            }
            AsstDivider()
            AsstLine("Quiet hours", QUIET_HINT) {
                TimeField("Quiet hours start", quiet.start, !saving, "asst-quiet-start") { save(buildJsonObject { put("quiet_hours", quiet.copy(start = it).toJson()) }, true) }
                BasicText("–", style = asstNote())
                TimeField("Quiet hours end", quiet.end, !saving, "asst-quiet-end") { save(buildJsonObject { put("quiet_hours", quiet.copy(end = it).toJson()) }, true) }
                Box(Modifier.testTag("asst-quiet-switch")) {
                    PpmSwitch(quiet.enabled, { save(buildJsonObject { put("quiet_hours", quiet.copy(enabled = it).toJson()) }, true) }, enabled = !saving)
                }
            }
        }
        BasicText(zoneLine(s.timezone, deviceZone()), Modifier.padding(horizontal = 4.dp).testTag("assistant-time-zone"), asstNote())
        Box(Modifier.padding(top = 12.dp).testTag("assistant-restart-news")) {
            AsstCard(ProductIcon.RotateCcw, "Restarts") {
                AsstLine(RESTART_NEWS_LABEL, RESTART_NEWS_HINT) {
                    Box(Modifier.testTag("asst-restart-news-switch")) {
                        PpmSwitch(s.restartNews, { save(buildJsonObject { put("restart_news", it) }, false) }, enabled = !saving)
                    }
                }
            }
        }
    }
}

/**
 * An "HH:MM" field saved once it is left or Done is pressed, not per keystroke ("08:30" passes
 * through "08:03" on the way); anything that is not a clock time goes back to the saved one.
 */
@Composable
private fun TimeField(label: String, value: String, enabled: Boolean, tag: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        if (isClockTime(text) && text != value) onCommit(text) else if (!isClockTime(text)) text = value
    }
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = text,
        onValueChange = { text = it.take(5) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        textStyle = ppmText(14f, kind = FontKind.MONO).copy(color = tokens.text.color),
        cursorBrush = SolidColor(tokens.accent.color),
        modifier = Modifier
            .width(96.dp)
            .testTag(tag)
            .semantics { contentDescription = label }
            .onFocusChanged { f ->
                if (focused && !f.isFocused) commit()
                focused = f.isFocused
            }
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
