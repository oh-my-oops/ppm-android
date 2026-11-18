package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.AppearanceExtrasSection
import com.hienlh.ppm.app.ui.Separator
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.PpmTheme
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.launch

@Composable
fun PhoneThemePage(
    model: AppModel,
    onBack: () -> Unit,
) {
    val activeThemeId = if (model.themeSelection.style == "custom") "" else model.theme.id
    val activeMode = model.themeSelection.mode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color),
    ) {
        PhoneSettingsSubPageHeader(
            title = "Theme",
            onBack = onBack,
        )
        PhoneThemeContent(
            themes = BuiltInThemes.all,
            activeThemeId = activeThemeId,
            activeMode = activeMode,
            onSelectMode = { mode ->
                model.scope.launch { model.pickThemeMode(mode) }
            },
            onSelectTheme = { theme ->
                model.scope.launch { model.pickTheme(theme) }
            },
            extras = { AppearanceExtrasSection(model) },
        )
    }
}

@Composable
fun PhoneThemeContent(
    themes: List<PpmTheme> = BuiltInThemes.all,
    activeThemeId: String,
    activeMode: String,
    onSelectMode: (String) -> Unit,
    onSelectTheme: (PpmTheme) -> Unit,
    modifier: Modifier = Modifier,
    /** Imported themes, tab style, word wrap and explorer skin, under the themes; null leaves them out. */
    extras: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Mode segmented control (Light / Dark / System)
        PhoneGroupLabel("Mode")
        PhoneSegmentedControl(
            items = listOf(
                "light" to "Light",
                "dark" to "Dark",
                "system" to "System",
            ),
            selectedId = activeMode,
            onSelect = onSelectMode,
            tagPrefix = "phone-theme-mode",
        )

        // 2. 2-column Theme Card grid
        PhoneGroupLabel("Color theme")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in themes.chunked(2)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (theme in row) {
                        PhoneThemeCard(
                            theme = theme,
                            selected = theme.id == activeThemeId,
                            onClick = { onSelectTheme(theme) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (extras != null) {
            Separator()
            Column(Modifier.testTag("phone-theme-extras")) { extras() }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PhoneThemeCard(
    theme: PpmTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)
    val previewShape = RoundedCornerShape(10.dp)
    val t = theme.tokens
    val cleanName = theme.name.replace(Regex(" (Dark|Light)$"), "")

    Column(
        modifier = modifier
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(
                color = if (selected) tokens.accent.color else tokens.borderSoft.color,
                shape = shape,
                width = if (selected) 2.dp else 1.dp,
            )
            .ppmClickable(onClick = onClick)
            .padding(10.dp)
            .testTag("phone-theme-card-${theme.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 74 dp miniature workspace preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .clip(previewShape)
                .background(t.bgSolid.color)
                .cssBorder(t.border.color, previewShape),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Rail strip
                Column(
                    modifier = Modifier
                        .width(24.dp)
                        .fillMaxHeight()
                        .background(t.panel.color)
                        .padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(t.accent.color),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(t.text3.color),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(t.text3.color),
                    )
                }
                // Main canvas preview
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(t.text.color),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(t.text2.color),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(10.dp)
                                .clip(CircleShape)
                                .background(t.accent.color),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                BasicText(
                    text = cleanName,
                    style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = if (theme.isDark) "Dark" else "Light",
                    style = ppmText(11f).copy(color = tokens.text3.color),
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(tokens.accent.color),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.Check, 12.dp, tokens.accentFg.color)
                }
            }
        }
    }
}
