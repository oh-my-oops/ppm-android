package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.AccountsSettingsSection
import com.hienlh.ppm.app.ui.AiProviderSettingsSection
import com.hienlh.ppm.app.ui.AiResourcesSettingsSection
import com.hienlh.ppm.app.ui.CloudSettingsSection
import com.hienlh.ppm.app.ui.DatabaseDriversSection
import com.hienlh.ppm.app.ui.DesignSettingsSection
import com.hienlh.ppm.app.ui.ExtensionsSettingsSection
import com.hienlh.ppm.app.ui.FilesSettingsSection
import com.hienlh.ppm.app.ui.JiraSettingsSection
import com.hienlh.ppm.app.ui.KeyboardShortcutsSection
import com.hienlh.ppm.app.ui.LanguageServersSection
import com.hienlh.ppm.app.ui.McpSettingsSection
import com.hienlh.ppm.app.ui.ProxySettingsSection
import com.hienlh.ppm.app.ui.RemoteAccessSettingsSection
import com.hienlh.ppm.app.ui.RemoteDesktopSettingsSection
import com.hienlh.ppm.app.ui.SettingsCategory
import com.hienlh.ppm.app.ui.ToolsSettingsSection
import com.hienlh.ppm.app.ui.VoiceSection
import com.hienlh.ppm.app.ui.settings.assistant.AssistantSettingsSection
import com.hienlh.ppm.app.ui.settings.vault.VaultSettingsSection
import com.hienlh.ppm.design.LocalPpmTokens

/**
 * A phone page that hosts a tablet Settings section as it is, under the sub-page header and in a
 * scroll, as the Memory page does: the section itself sizes its targets for a phone.
 */
@Composable
fun PhoneHostedSettingsPage(title: String, tag: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color)
            .testTag(tag),
    ) {
        PhoneSettingsSubPageHeader(title = title, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

/**
 * The tablet's pane of [category], for the phone pages that host it whole: the sections with no
 * page of their own, and the "all settings" pages under Accounts and AI Provider. The categories
 * with a phone page of their own (Memory, Notifications, Appearance, Servers, Schedules, Storage,
 * General) are not drawn here.
 */
@Composable
internal fun PhoneHostedSectionContent(model: AppModel, category: SettingsCategory) {
    when (category) {
        SettingsCategory.Accounts -> AccountsSettingsSection(model)
        SettingsCategory.Ai -> AiProviderSettingsSection(model)
        SettingsCategory.Tools -> ToolsSettingsSection(model)
        SettingsCategory.Mcp -> McpSettingsSection(model)
        SettingsCategory.AiResources -> AiResourcesSettingsSection(model)
        SettingsCategory.Assistant -> AssistantSettingsSection(model)
        SettingsCategory.Vault -> VaultSettingsSection(model)
        SettingsCategory.Design -> DesignSettingsSection(model)
        SettingsCategory.Voice -> VoiceSection(model)
        SettingsCategory.Shortcuts -> KeyboardShortcutsSection(model)
        SettingsCategory.RemoteAccess -> RemoteAccessSettingsSection(model)
        SettingsCategory.Cloud -> CloudSettingsSection(model)
        SettingsCategory.Proxy -> ProxySettingsSection(model)
        SettingsCategory.Extensions -> ExtensionsSettingsSection(model)
        SettingsCategory.Jira -> JiraSettingsSection(model)
        SettingsCategory.Files -> FilesSettingsSection(model)
        SettingsCategory.LanguageServers -> LanguageServersSection(model)
        SettingsCategory.DatabaseDrivers -> DatabaseDriversSection(model)
        SettingsCategory.RemoteDesktop -> RemoteDesktopSettingsSection(model)
        SettingsCategory.General,
        SettingsCategory.Appearance,
        SettingsCategory.Servers,
        SettingsCategory.Memory,
        SettingsCategory.Notifications,
        SettingsCategory.Schedules,
        SettingsCategory.QueryAudit,
        -> Unit
    }
}
