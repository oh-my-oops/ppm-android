package com.hienlh.ppm.app.ui.phone.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.BuildConfig
import com.hienlh.ppm.app.ui.Separator
import com.hienlh.ppm.app.ui.SettingsCategory
import com.hienlh.ppm.app.ui.phone.OpenOnTablet
import com.hienlh.ppm.app.ui.phone.OpenOnTabletNotice
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.ListRow
import com.hienlh.ppm.design.ppmText

/**
 * Phone Settings screen (`designs/ppm-mobile-tablet-redesign/phone/6-settings/`,
 * Requirements 15.1 & 15.2).
 *
 * - Home list displays the 6 everyday phone sections ([PhoneSettingsSection.Accounts],
 *   [PhoneSettingsSection.AiProvider], [PhoneSettingsSection.Notifications],
 *   [PhoneSettingsSection.Theme], [PhoneSettingsSection.Servers], [PhoneSettingsSection.About]).
 * - Opening any of the 6 sections shows its full phone page.
 * - Opening any other [SettingsCategory] shows `"Open on a tablet"` ([PhoneTabletOnlySectionPage]).
 */
@Composable
fun PhoneSettingsScreen(
    model: AppModel,
    initialRoute: PhoneSettingsRoute = PhoneSettingsRoute.Home,
    onRouteChanged: (PhoneSettingsRoute) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var route by remember(initialRoute) { mutableStateOf(initialRoute) }

    val navigate: (PhoneSettingsRoute) -> Unit = { next ->
        route = next
        if (next != PhoneSettingsRoute.Home) {
            model.requestedSettingsCategory = null
        }
        onRouteChanged(next)
    }

    val goHome: () -> Unit = {
        navigate(PhoneSettingsRoute.Home)
    }

    BackHandler(enabled = route != PhoneSettingsRoute.Home) {
        goHome()
    }

    val servers by model.store.servers.collectAsState()
    val selectedServerId by model.store.selectedId.collectAsState()
    val activeServerName = servers.firstOrNull { it.id == selectedServerId }?.name ?: model.serverName
    val themeName = model.theme.name
    val serverVersion = model.info?.version?.let { "v$it" } ?: "v${BuildConfig.VERSION_NAME}"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color),
    ) {
        when (val current = route) {
            PhoneSettingsRoute.Home -> {
                PhoneSettingsHomeList(
                    themeLabel = themeName,
                    serverLabel = activeServerName,
                    aboutLabel = serverVersion,
                    onSelectSection = { section ->
                        navigate(PhoneSettingsRoute.FullPage(section))
                    },
                    onSelectCategory = { category ->
                        navigate(resolvePhoneSettingsCategory(category))
                    },
                )
            }

            is PhoneSettingsRoute.FullPage -> {
                when (current.section) {
                    PhoneSettingsSection.Accounts -> PhoneAccountsPage(
                        model = model,
                        onBack = goHome,
                    )
                    PhoneSettingsSection.AiProvider -> PhoneAiProviderPage(
                        model = model,
                        onBack = goHome,
                        onOpenAccounts = {
                            navigate(PhoneSettingsRoute.FullPage(PhoneSettingsSection.Accounts))
                        },
                    )
                    PhoneSettingsSection.Notifications -> NotificationsPage(
                        model = model,
                        onBack = goHome,
                    )
                    PhoneSettingsSection.Theme -> PhoneThemePage(
                        model = model,
                        onBack = goHome,
                    )
                    PhoneSettingsSection.Servers -> PhoneServersPage(
                        model = model,
                        onBack = goHome,
                    )
                    PhoneSettingsSection.About -> PhoneAboutPage(
                        model = model,
                        onBack = goHome,
                    )
                }
            }

            is PhoneSettingsRoute.TabletOnlyPage -> {
                PhoneTabletOnlySectionPage(
                    category = current.category,
                    onBack = goHome,
                )
            }
        }
    }
}

@Composable
fun PhoneSettingsHomeList(
    accountsSummary: String = "Claude, Codex & Antigravity",
    aiProviderSummary: String = "Model, effort & permission",
    notificationsSummary: String = "Push, Telegram & ntfy",
    themeLabel: String = "Aurora Dark",
    serverLabel: String = "Connected",
    aboutLabel: String = "v${BuildConfig.VERSION_NAME}",
    onSelectSection: (PhoneSettingsSection) -> Unit,
    onSelectCategory: (SettingsCategory) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("phone-settings-home-list"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (group in PhoneSettingsGroup.entries) {
            val sections = PhoneSettingsSection.forGroup(group)
            if (sections.isEmpty()) continue
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneGroupLabel(group.title)
                PhoneSectionCard {
                    sections.forEachIndexed { idx, section ->
                        if (idx > 0) Separator()
                        val trailingValue = when (section) {
                            PhoneSettingsSection.Accounts -> null
                            PhoneSettingsSection.AiProvider -> null
                            PhoneSettingsSection.Notifications -> null
                            PhoneSettingsSection.Theme -> themeLabel
                            PhoneSettingsSection.Servers -> serverLabel
                            PhoneSettingsSection.About -> aboutLabel
                        }
                        val subtitleText = when (section) {
                            PhoneSettingsSection.Accounts -> accountsSummary
                            PhoneSettingsSection.AiProvider -> aiProviderSummary
                            PhoneSettingsSection.Notifications -> notificationsSummary
                            else -> section.subtitle
                        }
                        ListRow(
                            title = section.title,
                            subtitle = subtitleText,
                            modifier = Modifier.testTag("phone-settings-row-${section.id}"),
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(tokens.panel2.color),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PpmIcon(section.icon, 16.dp, tokens.text2.color)
                                }
                            },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (!trailingValue.isNullOrBlank()) {
                                        BasicText(
                                            text = trailingValue,
                                            style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text3.color),
                                            maxLines = 1,
                                        )
                                    }
                                    PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color)
                                }
                            },
                            onClick = { onSelectSection(section) },
                        )
                    }
                }
            }
        }

        OpenOnTabletNotice(
            text = "Tools, Remote Access, Storage, Keyboard Shortcuts and the other sections open on a tablet.",
        )

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Shown when any non-phone [SettingsCategory] is opened on a phone (Requirement 15.2):
 * displays `"Open on a tablet"`.
 */
@Composable
fun PhoneTabletOnlySectionPage(
    category: SettingsCategory,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color)
            .testTag("phone-settings-tablet-only-${category.id}"),
    ) {
        PhoneSettingsSubPageHeader(
            title = category.label,
            onBack = onBack,
        )
        OpenOnTablet(
            title = category.label,
            notice = "Open on a tablet",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}
