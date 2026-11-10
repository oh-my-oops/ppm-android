package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.hienlh.ppm.app.ui.SettingsToggleRow
import com.hienlh.ppm.app.ui.phone.OpenOnTabletNotice
import com.hienlh.ppm.client.applyUpgrade
import com.hienlh.ppm.client.upgradeStatus
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PhoneAboutPage(
    model: AppModel,
    onBack: () -> Unit,
) {
    val api = model.api
    val scope = rememberCoroutineScope()

    var availableVersion by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var upgrading by remember { mutableStateOf(false) }
    var statusNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        val client = api ?: return@LaunchedEffect
        try {
            val st = client.upgradeStatus(refresh = false)
            availableVersion = st.availableVersion
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color),
    ) {
        PhoneSettingsSubPageHeader(
            title = "About",
            onBack = onBack,
        )
        PhoneAboutContent(
            serverVersion = model.info?.version?.let { "v$it" } ?: "Connected",
            appVersion = "v${BuildConfig.VERSION_NAME}",
            availableVersion = availableVersion,
            checking = checking,
            upgrading = upgrading,
            statusNote = statusNote,
            onCheckUpdates = {
                val client = api ?: return@PhoneAboutContent
                checking = true
                statusNote = null
                scope.launch {
                    try {
                        val st = client.upgradeStatus(refresh = true)
                        availableVersion = st.availableVersion
                        if (st.availableVersion.isNullOrBlank()) {
                            statusNote = "Server is up to date (v${st.currentVersion})"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        statusNote = t.message ?: "Check failed"
                    } finally {
                        checking = false
                    }
                }
            },
            onConfirmUpgrade = { targetVersion ->
                val client = api ?: return@PhoneAboutContent
                upgrading = true
                statusNote = "Updating to v$targetVersion…"
                model.upgrade?.update(targetVersion)
                scope.launch {
                    try {
                        if (model.upgrade == null) {
                            client.applyUpgrade()
                        }
                        delay(2_000L)
                        runCatching { model.loadProjects() }
                        availableVersion = null
                        statusNote = "Updated to v$targetVersion"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        statusNote = t.message ?: "Upgrade failed"
                    } finally {
                        upgrading = false
                    }
                }
            },
        )
    }
}

@Composable
fun PhoneAboutContent(
    serverVersion: String,
    appVersion: String = "v${BuildConfig.VERSION_NAME}",
    availableVersion: String? = null,
    checking: Boolean = false,
    upgrading: Boolean = false,
    statusNote: String? = null,
    onCheckUpdates: () -> Unit = {},
    onConfirmUpgrade: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var confirmUpdateSheetOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Identity Card
        PhoneSectionCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(
                    text = "PPM — Personal Project Manager",
                    style = ppmText(15f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                BasicText(
                    text = "AI-powered workspace for code, terminals, git & databases.",
                    style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                )
            }
        }

        // 2. Server & App Versions Card
        PhoneSectionCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText(
                            text = "PPM Server",
                            style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color),
                        )
                        BasicText(
                            text = serverVersion,
                            style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                        )
                    }
                    if (!availableVersion.isNullOrBlank()) {
                        PhonePillButton(
                            label = if (upgrading) "Updating…" else "Update to v$availableVersion",
                            primary = true,
                            enabled = !upgrading,
                            onClick = { confirmUpdateSheetOpen = true },
                            modifier = Modifier.testTag("phone-about-update-btn"),
                        )
                    } else {
                        PhonePillButton(
                            label = if (checking) "Checking…" else "Check for updates",
                            enabled = !checking,
                            onClick = onCheckUpdates,
                            modifier = Modifier.testTag("phone-about-check-updates-btn"),
                        )
                    }
                }

                if (!statusNote.isNullOrBlank()) {
                    BasicText(
                        text = statusNote,
                        style = ppmText(12f).copy(color = tokens.text2.color),
                        modifier = Modifier.testTag("phone-about-status-note"),
                    )
                }

                Separator()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = "PPM Android App",
                        style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color),
                    )
                    BasicText(
                        text = appVersion,
                        style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                    )
                }
            }
        }

        OpenOnTabletNotice(
            text = "Device name, server password and onboarding guide open on a tablet.",
        )

        Spacer(Modifier.height(16.dp))
    }

    if (confirmUpdateSheetOpen && !availableVersion.isNullOrBlank()) {
        val targetVersion = availableVersion
        Sheet(
            title = "Update PPM Server to v$targetVersion?",
            subtitle = "The server will install v$targetVersion and restart. Active chats and terminals will reconnect automatically.",
            onDismiss = { confirmUpdateSheetOpen = false },
            forceBottomSheet = true,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PhonePillButton(
                    label = "Cancel",
                    onClick = { confirmUpdateSheetOpen = false },
                    modifier = Modifier.weight(1f),
                )
                PhonePillButton(
                    label = "Update & Restart",
                    primary = true,
                    onClick = {
                        confirmUpdateSheetOpen = false
                        onConfirmUpgrade(targetVersion)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("phone-about-confirm-update"),
                )
            }
        }
    }
}
