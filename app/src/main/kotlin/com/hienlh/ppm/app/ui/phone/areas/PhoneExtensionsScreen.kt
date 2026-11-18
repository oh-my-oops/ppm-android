package com.hienlh.ppm.app.ui.phone.areas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.extensions.ExtensionIcons
import com.hienlh.ppm.client.extensionsDevLink
import com.hienlh.ppm.client.extensionsInstall
import com.hienlh.ppm.client.extensionsList
import com.hienlh.ppm.client.extensionsRemove
import com.hienlh.ppm.client.extensionsSetEnabled
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.settings.ExtensionItemRow
import com.hienlh.ppm.state.settings.parseExtensionsList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The extensions manager on a phone (`extension-manager-section.tsx`): install by package name,
 * dev-link a local folder, and the installed extensions with their Enable/Disable and Remove
 * buttons. The web's controls at a phone's sizes: 16px inputs and 44px targets.
 */
@Composable
internal fun PhoneExtensionsScreen(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    val scope = rememberCoroutineScope()
    var extensions by remember { mutableStateOf<List<ExtensionItemRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var showDevLink by remember { mutableStateOf(false) }
    var togglingId by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    val installName = remember { TextFieldState() }
    val devPath = remember { TextFieldState() }

    suspend fun fetchExtensions() {
        val client = api ?: return
        try {
            extensions = parseExtensionsList(client.extensionsList())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // As the web: a failed read keeps the list it had.
        } finally {
            loading = false
        }
    }

    fun failed(e: Exception, fallback: String) = model.toast(ErrorToastMessage(e.message?.takeIf { it.isNotBlank() } ?: fallback))
    fun ok(title: String) = model.toast(ErrorToastMessage(title, kind = ErrorToastMessage.Kind.SUCCESS))

    // Installed, removed or switched elsewhere (another device, `ppm ext`, the Assistant).
    val revision = model.liveSync.extensionsRevision
    LaunchedEffect(api, revision) { fetchExtensions() }

    fun install() {
        val name = installName.text.toString().trim()
        val client = api ?: return
        if (name.isEmpty() || installing) return
        installing = true
        scope.launch {
            try {
                client.extensionsInstall(name)
                ok("Installed $name")
                installName.clearText()
                fetchExtensions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e, "Install failed")
            } finally {
                installing = false
            }
        }
    }

    fun devLink() {
        val path = devPath.text.toString().trim()
        val client = api ?: return
        if (path.isEmpty()) return
        scope.launch {
            try {
                client.extensionsDevLink(path)
                ok("Dev-linked successfully")
                devPath.clearText()
                fetchExtensions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e, "Dev link failed")
            }
        }
    }

    fun toggle(ext: ExtensionItemRow) {
        val client = api ?: return
        togglingId = ext.id
        scope.launch {
            try {
                client.extensionsSetEnabled(ext.id, !ext.enabled)
                fetchExtensions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e, "Toggle failed")
            } finally {
                togglingId = null
            }
        }
    }

    fun remove(id: String) {
        val client = api ?: return
        deletingId = id
        scope.launch {
            try {
                client.extensionsRemove(id)
                ok("Removed $id")
                fetchExtensions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e, "Remove failed")
            } finally {
                deletingId = null
            }
        }
    }

    if (loading && api != null) {
        Box(Modifier.fillMaxSize().padding(vertical = 32.dp).testTag("phone-extensions-loading"), contentAlignment = Alignment.TopCenter) {
            PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
        }
        return
    }

    Column(
        Modifier.fillMaxSize().cssScrollY().padding(16.dp).testTag("phone-extensions-manager"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Install
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading("Install Extension")
            InputRow(
                state = installName,
                placeholder = "npm package name (e.g. @ppm/ext-myext)",
                fieldTag = "phone-ext-install-input",
                buttonLabel = "Install",
                buttonIcon = if (installing) ProductIcon.Loader2 else ProductIcon.Plus,
                spinning = installing,
                buttonEnabled = installName.text.isNotBlank() && !installing,
                buttonTag = "phone-ext-install",
                onSubmit = ::install,
            )
            val linkLabel = if (showDevLink) "Hide" else "Dev link local extension..."
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = linkLabel) { showDevLink = !showDevLink }
                    .testTag("phone-ext-devlink-toggle"),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(linkLabel, style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color))
            }
            if (showDevLink) {
                InputRow(
                    state = devPath,
                    placeholder = "Local path (e.g. ./packages/ext-myext)",
                    fieldTag = "phone-ext-devlink-input",
                    buttonLabel = "Link",
                    buttonIcon = ProductIcon.FolderSymlink,
                    buttonEnabled = devPath.text.isNotBlank(),
                    buttonTag = "phone-ext-devlink",
                    onSubmit = ::devLink,
                )
            }
        }

        // Extension list
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading("Installed (${extensions.size})")
            if (extensions.isEmpty()) {
                BasicText(
                    "No extensions installed",
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (ext in extensions) {
                        ExtensionRow(
                            ext = ext,
                            toggling = togglingId == ext.id,
                            deleting = deletingId == ext.id,
                            onToggle = { toggle(ext) },
                            onRemove = { remove(ext.id) },
                        )
                    }
                }
            }
        }
    }
}

/** `text-xs font-medium text-muted-foreground`. */
@Composable
private fun SectionHeading(text: String) {
    val tokens = LocalPpmTokens.current
    BasicText(text, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
}

/** The field and its outline button (`flex gap-1.5`), Enter submitting as the button does. */
@Composable
private fun InputRow(
    state: TextFieldState,
    placeholder: String,
    fieldTag: String,
    buttonLabel: String,
    buttonIcon: ProductIcon,
    buttonEnabled: Boolean,
    buttonTag: String,
    spinning: Boolean = false,
    onSubmit: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, shape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            PpmTextField(
                state = state,
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth().testTag(fieldTag),
                onKeyboardAction = { onSubmit() },
            )
        }
        Row(
            // The border insets what follows it, so the 44px target sits outside it.
            Modifier
                .alpha(if (buttonEnabled) 1f else 0.5f)
                .heightIn(min = 44.dp)
                .clip(shape)
                .ppmClickable(enabled = buttonEnabled, pressedColor = tokens.panel2.color, shape = shape, onClickLabel = buttonLabel, onClick = onSubmit)
                .testTag(buttonTag)
                .cssBorder(tokens.border.color, shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(buttonIcon, 14.dp, tokens.text.color, spinning = spinning)
            BasicText(buttonLabel, style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color))
        }
    }
}

/**
 * An installed extension (`flex items-center gap-2 px-2.5 py-2 rounded-lg bg-muted/50`): its
 * manifest icon, name, `id vversion` and "active", then Enable/Disable and Remove.
 */
@Composable
private fun ExtensionRow(
    ext: ExtensionItemRow,
    toggling: Boolean,
    deleting: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel2.opacity(0.5).color)
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
            .testTag("phone-ext-row-${ext.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(tokens.bgSolid.color), contentAlignment = Alignment.Center) {
            PpmIcon(ExtensionIcons.icon(ext.icon) ?: ProductIcon.Puzzle.vector, 16.dp, tokens.text2.color)
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                ext.displayName.ifBlank { ext.id },
                style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "${ext.id} v${ext.version}",
                    Modifier.weight(1f, fill = false),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ext.activated) {
                    BasicText(" active", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.success.color))
                }
            }
        }
        val toggleLabel = if (ext.enabled) "Disable" else "Enable"
        IconButton44(toggleLabel, "phone-ext-toggle-${ext.id}", enabled = !toggling, onClick = onToggle) {
            when {
                toggling -> PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text2.color, spinning = true)
                ext.enabled -> PpmIcon(ProductIcon.Power, 16.dp, tokens.success.color)
                else -> PpmIcon(LucideIcon.PowerOff, 16.dp, tokens.text3.color)
            }
        }
        IconButton44("Remove", "phone-ext-remove-${ext.id}", enabled = !deleting, onClick = onRemove) {
            if (deleting) {
                PpmIcon(ProductIcon.Loader2, 16.dp, tokens.error.color, spinning = true)
            } else {
                PpmIcon(ProductIcon.Trash2, 16.dp, tokens.error.color)
            }
        }
    }
}

@Composable
private fun IconButton44(label: String, tag: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, shape = shape, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
