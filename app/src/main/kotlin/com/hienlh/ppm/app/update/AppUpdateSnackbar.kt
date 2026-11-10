package com.hienlh.ppm.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText

/**
 * Floating snackbar card for Android APK self-updates (`Phone 2 · UP3`, `Tablet 2 · UP3`):
 * - Offer: `"Update available · PPM for Android v0.1.3 → v0.1.4"` with `"Update now"` and `"×"`
 * - Downloading: `"Downloading the update · PPM for Android v0.1.4 · 64%"` with progress bar & `"Cancel"`
 * - Ready to install: `"Update downloaded · PPM for Android v0.1.4"` with `"Install"`
 * - Failed: `"Update failed"` with reason and `"Retry"`
 */
@Composable
fun AppUpdateSnackbar(
    updater: AppUpdater? = AppUpdater.activeInstance?.get(),
    modifier: Modifier = Modifier,
) {
    if (updater == null) return
    val state by updater.state.collectAsState()
    if (state is AppUpdater.State.Idle) return

    AppUpdateSnackbarCard(
        state = state,
        onUpdateNow = { updater.startUpdate() },
        onCancelDownload = { updater.cancelDownload() },
        onInstall = { updater.installReadyUpdate() },
        onRetry = {
            val failed = state as? AppUpdater.State.Failed
            if (failed?.canInstallDirectly == true) {
                updater.installReadyUpdate()
            } else {
                updater.startUpdate()
            }
        },
        onDismiss = { updater.dismissOffer() },
        modifier = modifier,
    )
}

@Composable
fun AppUpdateSnackbarCard(
    state: AppUpdater.State,
    onUpdateNow: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is AppUpdater.State.Idle) return
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape)
            .padding(14.dp),
    ) {
        when (state) {
            is AppUpdater.State.Available -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(tokens.accent.opacity(0.14).color),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.ArrowUpCircle, 16.dp, tokens.accent.color)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText(
                            "Update available",
                            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                        )
                        BasicText(
                            "PPM for Android v${state.currentVersion} → v${state.release.version}",
                            style = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val btnShape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .height(32.dp)
                            .clip(btnShape)
                            .background(tokens.accent.color)
                            .ppmClickable(pressedColor = tokens.accent.opacity(0.85).color, onClick = onUpdateNow)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "Update now",
                            style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accentFg.color),
                        )
                    }
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .ppmClickable(pressedColor = tokens.panel.color, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, 14.dp, tokens.text3.color)
                    }
                }
            }

            is AppUpdater.State.Downloading -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PpmIcon(ProductIcon.Download, 16.dp, tokens.accent.color)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            BasicText(
                                "Downloading the update",
                                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                            )
                            val pctText = state.percent?.let { " · $it%" } ?: ""
                            BasicText(
                                "PPM for Android v${state.release.version}$pctText",
                                style = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
                            )
                        }
                        val btnShape = RoundedCornerShape(8.dp)
                        Box(
                            Modifier
                                .height(30.dp)
                                .clip(btnShape)
                                .background(tokens.panel.color)
                                .cssBorder(tokens.border.color, btnShape)
                                .ppmClickable(pressedColor = tokens.panel2.color, onClick = onCancelDownload)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                "Cancel",
                                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
                            )
                        }
                    }
                    val fraction = ((state.percent ?: 10).coerceIn(5, 100)) / 100f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(tokens.border.color),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(tokens.accent.color),
                        )
                    }
                }
            }

            is AppUpdater.State.ReadyToInstall -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(tokens.success.opacity(0.14).color),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.CheckCircle2, 16.dp, tokens.success.color)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText(
                            "Update downloaded",
                            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                        )
                        BasicText(
                            "PPM for Android v${state.release.version}",
                            style = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
                        )
                    }
                    val btnShape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .height(32.dp)
                            .clip(btnShape)
                            .background(tokens.success.color)
                            .ppmClickable(pressedColor = tokens.success.opacity(0.85).color, onClick = onInstall)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "Install",
                            style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = Color(0xFF04130C)),
                        )
                    }
                }
            }

            is AppUpdater.State.Failed -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PpmIcon(ProductIcon.AlertCircle, 18.dp, tokens.error.color)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText(
                            "Update failed",
                            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                        )
                        BasicText(
                            state.reason,
                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val btnShape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .height(32.dp)
                            .clip(btnShape)
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, btnShape)
                            .ppmClickable(pressedColor = tokens.panel2.color, onClick = onRetry)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "Retry",
                            style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                        )
                    }
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .ppmClickable(pressedColor = tokens.panel.color, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, 14.dp, tokens.text3.color)
                    }
                }
            }

            AppUpdater.State.Idle -> Unit
        }
    }
}
