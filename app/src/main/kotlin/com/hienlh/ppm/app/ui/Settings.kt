package com.hienlh.ppm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.ServerProfile
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.PpmTheme
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.launch

/**
 * Settings as a tab (`settings-body.tsx` at a phone's width): the category list over the pane,
 * a row opening its pane and Back returning to the list. Only the categories this app has are
 * listed — Appearance's theme, and the servers it connects to, which the web has no need of.
 */
@Composable
fun SettingsTab(model: AppModel) {
    var category by rememberSaveable { mutableStateOf(SettingsCategory.Appearance) }
    var showList by rememberSaveable { mutableStateOf(true) }
    BackHandler(enabled = !showList) { showList = true }
    Box(Modifier.fillMaxSize()) {
        SettingsPane(model, category, onBack = { showList = true })
        if (showList) {
            SettingsIndex {
                category = it
                showList = false
            }
        }
    }
}

enum class SettingsCategory(val label: String, val subtitle: String, val icon: ProductIcon) {
    Appearance("Appearance", "Theme", ProductIcon.Palette),
    Servers("Servers", "The PPM servers this app connects to", ProductIcon.HardDrive),
}

/** `absolute inset-0 bg-background`: "Settings", then a row per category. */
@Composable
private fun SettingsIndex(onSelect: (SettingsCategory) -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxSize().background(tokens.bgSolid.color)) {
        BasicText(
            "Settings",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
        )
        Separator()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (category in SettingsCategory.entries) {
                // `px-2.5 py-3 rounded-lg gap-3`, `active:bg-accent`.
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .ppmClickable(pressedColor = tokens.panel2.color, shape = shape) { onSelect(category) }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(tokens.panel.color),
                        contentAlignment = Alignment.Center,
                    ) { PpmIcon(category.icon, 16.dp, tokens.text2.color) }
                    Column(Modifier.weight(1f)) {
                        BasicText(category.label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(category.subtitle, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text2.color)
                }
            }
        }
    }
}

/** The pane: Back, the category's icon and name (`px-2 py-2.5`), then its content in `p-4 pb-8`. */
@Composable
private fun SettingsPane(model: AppModel, category: SettingsCategory, onBack: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Back to settings list", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.ArrowLeft, 16.dp, tokens.text.color) }
            PpmIcon(category.icon, 16.dp, tokens.text2.color)
            BasicText(category.label, style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Separator()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp)) {
            when (category) {
                SettingsCategory.Appearance -> AppearanceSection(model)
                SettingsCategory.Servers -> ServersSection(model)
            }
        }
    }
}

@Composable
private fun Separator() {
    val tokens = LocalPpmTokens.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
}

// Appearance

/** The theme section of `appearance-settings-section.tsx`: the six themes, then Light, Dark or System. */
@Composable
private fun AppearanceSection(model: AppModel) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText("Theme", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
        val active = model.theme.id
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in BuiltInThemes.all.chunked(2)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (theme in row) {
                        ThemeCard(theme, theme.id == active, Modifier.weight(1f).fillMaxHeight()) { model.scope.launch { model.pickTheme(theme) } }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((mode, label, icon) in THEME_MODES) {
                ModeButton(label, icon, model.themeSelection.mode == mode, model.theme.isDark, Modifier.weight(1f)) {
                    model.scope.launch { model.pickThemeMode(mode) }
                }
            }
        }
    }
}

/** `THEME_MODE_OPTIONS`. */
private val THEME_MODES = listOf(
    Triple("light", "Light", ProductIcon.Sun),
    Triple("dark", "Dark", ProductIcon.Moon),
    Triple("system", "System", ProductIcon.Monitor),
)

/** `rounded-lg border p-2 min-h-11`: the swatch, the name and its mode; the active one ringed with a check. */
@Composable
private fun ThemeCard(theme: PpmTheme, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .then(if (active) Modifier.ring(3.dp, tokens.accentWash.color, 12.dp) else Modifier)
            .clip(shape)
            .border(1.dp, (if (active) tokens.accent else tokens.border).color, shape)
            .ppmClickable(pressedColor = tokens.panel2.opacity(0.4).color, shape = shape, onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeSwatch(theme)
            Column(Modifier.weight(1f)) {
                BasicText(
                    theme.name.replace(Regex(" (Dark|Light)$"), ""),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(if (theme.isDark) "Dark" else "Light", style = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color))
            }
        }
        // `absolute top-1 right-1`, from the padding box.
        if (active) PpmIcon(ProductIcon.Check, 14.dp, tokens.accent.color, Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp))
    }
}

/** `ThemeSwatch`: three 12x24 strips in a bordered, rounded box. */
@Composable
internal fun ThemeSwatch(theme: PpmTheme) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.clip(shape).border(1.dp, tokens.border.color, shape).padding(1.dp)) {
        for (color in theme.swatch) Box(Modifier.width(12.dp).height(24.dp).background(color.color))
    }
}

/** The web's `Button` in `default` (picked, with `ring-2 ring-primary`) or `outline`, `h-9 gap-2`. */
@Composable
private fun ModeButton(label: String, icon: ProductIcon, on: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val content = if (on) tokens.accentFg else tokens.text
    Row(
        modifier
            .height(36.dp)
            .then(if (on) Modifier.ring(2.dp, tokens.accent.color, 8.dp) else Modifier)
            .clip(shape)
            .background(
                when {
                    on -> tokens.accent.color
                    // `dark:bg-input/30`; `--input` is the border colour.
                    dark -> tokens.border.opacity(0.3).color
                    else -> tokens.bgSolid.color
                },
            )
            .then(if (on) Modifier else Modifier.border(1.dp, tokens.border.color, shape))
            .ppmClickable(pressedColor = if (on) Color.Black.copy(alpha = 0.1f) else tokens.panel2.color, shape = shape, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PpmIcon(icon, 16.dp, content.color)
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = content.color), maxLines = 1)
    }
}

/** Tailwind's `ring-N`: a solid [width] band outside the box, its corners following [radius]. */
private fun Modifier.ring(width: Dp, color: Color, radius: Dp): Modifier = drawBehind {
    val band = width.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(-band, -band),
        size = Size(size.width + band * 2, size.height + band * 2),
        cornerRadius = CornerRadius(radius.toPx() + band),
    )
}

// Servers

/**
 * The servers this app knows, the one in use ticked: a tap switches to another, the bin forgets
 * one (and the password saved for it), and Add server asks for a new one.
 */
@Composable
private fun ServersSection(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val servers by model.store.servers.collectAsState()
    val selected by model.store.selectedId.collectAsState()
    var removing by remember { mutableStateOf<ServerProfile?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.debug != null) {
            BasicText(
                "This launch is pinned to ${model.debug.url} for debugging; the saved servers are not used.",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            )
        }
        for (server in servers) {
            val current = server.id == selected
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, (if (current) tokens.accent else tokens.border).color, shape)
                    .ppmClickable(pressedColor = tokens.panel2.color, shape = shape) { if (!current) model.store.select(server) }
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    BasicText(server.name, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(server.url, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (current) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Remove ${server.name}") { removing = server },
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.Trash2, 16.dp, tokens.text2.color) }
            }
        }
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .background(if (model.theme.isDark) tokens.border.opacity(0.3).color else tokens.bgSolid.color)
                .border(1.dp, tokens.border.color, shape)
                .ppmClickable(pressedColor = tokens.panel2.color, shape = shape) { model.addingServer = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            PpmIcon(ProductIcon.Plus, 16.dp, tokens.text.color)
            BasicText("Add server", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
        }
    }
    removing?.let { server ->
        ConfirmDialog(
            title = "Remove ${server.name}?",
            message = "This device forgets the server and the password saved for it. Nothing on the server changes.",
            confirm = "Remove",
            onConfirm = { model.remove(server) },
            onDismiss = { removing = null },
        )
    }
}
