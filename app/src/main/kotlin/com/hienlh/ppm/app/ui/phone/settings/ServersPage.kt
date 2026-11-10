package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.ServerProfile
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

@Composable
fun PhoneServersPage(
    model: AppModel,
    onBack: () -> Unit,
) {
    val servers by model.store.servers.collectAsState()
    val selectedId by model.store.selectedId.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color),
    ) {
        PhoneSettingsSubPageHeader(
            title = "Servers",
            onBack = onBack,
        )
        PhoneServersContent(
            servers = servers,
            selectedServerId = selectedId,
            debugUrl = model.debug?.url?.toString(),
            onSelectServer = { server -> model.store.select(server) },
            onRemoveServer = { server -> model.remove(server) },
            onLogOut = { model.logOut() },
            onAddServer = { model.addingServer = true },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun PhoneServersContent(
    servers: List<ServerProfile>,
    selectedServerId: String?,
    debugUrl: String? = null,
    onSelectServer: (ServerProfile) -> Unit,
    onRemoveServer: (ServerProfile) -> Unit,
    onLogOut: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var removingServer by remember { mutableStateOf<ServerProfile?>(null) }
    var loggingOutServer by remember { mutableStateOf<ServerProfile?>(null) }

    val currentServer = servers.firstOrNull { it.id == selectedServerId }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            if (!debugUrl.isNullOrBlank()) {
                val shape = RoundedCornerShape(12.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.borderSoft.color, shape)
                        .padding(12.dp),
                ) {
                    BasicText(
                        text = "This launch is pinned to $debugUrl for debugging; the saved servers are not used.",
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                    )
                }
            }

            for (server in servers) {
                val isCurrent = server.id == selectedServerId
                val shape = RoundedCornerShape(14.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(tokens.panel.color)
                        .cssBorder(
                            color = if (isCurrent) tokens.accent.color else tokens.borderSoft.color,
                            shape = shape,
                            width = if (isCurrent) 2.dp else 1.dp,
                        )
                        .ppmClickable(onClick = { if (!isCurrent) onSelectServer(server) })
                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp)
                        .testTag("phone-server-card-${server.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Radio dot
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .cssBorder(
                                color = if (isCurrent) tokens.accent.color else tokens.border.color,
                                shape = CircleShape,
                                width = 2.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(tokens.accent.color),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BasicText(
                                text = server.name,
                                style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(tokens.success.opacity(0.14).color)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    BasicText(
                                        text = "In use",
                                        style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.success.color),
                                    )
                                }
                            }
                        }
                        BasicText(
                            text = server.url,
                            style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .ppmClickable(
                                onClickLabel = "Remove ${server.name}",
                                onClick = { removingServer = server },
                            )
                            .testTag("phone-server-remove-${server.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.Trash2, 16.dp, tokens.text2.color)
                    }
                }
            }
        }

        BottomActionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentServer != null && debugUrl == null) {
                    PhonePillButton(
                        label = "Log out",
                        onClick = { loggingOutServer = currentServer },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("phone-servers-logout-btn"),
                    )
                }
                PhonePillButton(
                    label = "+ Add server",
                    primary = true,
                    onClick = onAddServer,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("phone-servers-add-btn"),
                )
            }
        }
    }

    val targetLogout = loggingOutServer
    if (targetLogout != null) {
        Sheet(
            title = "Log out of ${targetLogout.name}?",
            subtitle = "This device forgets the password saved for this server and asks for it again. The server stays in the list.",
            onDismiss = { loggingOutServer = null },
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
                    onClick = { loggingOutServer = null },
                    modifier = Modifier.weight(1f),
                )
                PhonePillButton(
                    label = "Log out",
                    danger = true,
                    onClick = {
                        loggingOutServer = null
                        onLogOut()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("phone-servers-confirm-logout"),
                )
            }
        }
    }

    val targetRemove = removingServer
    if (targetRemove != null) {
        Sheet(
            title = "Remove ${targetRemove.name}?",
            subtitle = "This device forgets the server and the password saved for it. Nothing on the server changes.",
            onDismiss = { removingServer = null },
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
                    onClick = { removingServer = null },
                    modifier = Modifier.weight(1f),
                )
                PhonePillButton(
                    label = "Remove",
                    danger = true,
                    onClick = {
                        removingServer = null
                        onRemoveServer(targetRemove)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("phone-servers-confirm-remove"),
                )
            }
        }
    }
    }
}
