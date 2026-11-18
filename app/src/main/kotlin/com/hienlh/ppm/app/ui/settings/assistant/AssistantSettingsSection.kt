package com.hienlh.ppm.app.ui.settings.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/*
 * Settings → PPM Assistant (`assistant-settings-section.tsx`), in sub-tabs: General, Sessions &
 * Telegram, Digest & quiet hours, Manager chats and Notebook. The General pane's unsaved draft is
 * kept by the section, so switching sub-tabs and back keeps it; the other panes save as they change.
 */

/** `ASSISTANT_SETTINGS_TABS`. */
val ASSISTANT_SETTINGS_TABS: List<Pair<String, String>> = listOf(
    "general" to "General",
    "sessions" to "Sessions & Telegram",
    "digest" to "Digest & quiet hours",
    "managers" to "Manager chats",
    "notebook" to "Notebook",
)

/**
 * Which sub-tab the pane shows (`useAssistantSettingsTab`): kept outside the pane, so a link from
 * elsewhere — the old PPMBot category above all, which now leads to Sessions & Telegram — can land on one.
 */
object AssistantSettingsTab {
    var tab by mutableStateOf("general")
}

@Composable
fun AssistantSettingsSection(model: AppModel) {
    val api = model.api ?: return
    val general = remember(api) { AssistantGeneralState() }
    val tab = AssistantSettingsTab.tab
    Column(Modifier.fillMaxWidth().testTag("assistant-settings"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TabStrip(tab) { AssistantSettingsTab.tab = it }
        when (tab) {
            "sessions" -> AssistantSessionsPane(model, api)
            "digest" -> AssistantDigestPane(model, api)
            "managers" -> AssistantManagersPane(model, api)
            "notebook" -> AssistantNotebookPane(model, api)
            else -> AssistantGeneralPane(model, api, general)
        }
    }
}

@Composable
private fun TabStrip(selected: String, onSelect: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((id, label) in ASSISTANT_SETTINGS_TABS) {
                val on = id == selected
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .ppmClickable(onClickLabel = label) { onSelect(id) }
                        .semantics {
                            role = Role.Tab
                            this.selected = on
                        }
                        .testTag("assistant-settings-tab-$id"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.heightIn(min = if (mobile) 42.dp else 34.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        BasicText(
                            label,
                            style = ppmText(if (mobile) 14f else 12f, if (on) FontWeight.Medium else FontWeight.Normal)
                                .copy(color = (if (on) tokens.accent else tokens.text3).color),
                            maxLines = 1,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) tokens.accent.color else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.opacity(0.5).color))
    }
}
