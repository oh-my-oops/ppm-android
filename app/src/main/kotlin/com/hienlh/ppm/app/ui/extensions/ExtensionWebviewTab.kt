package com.hienlh.ppm.app.ui.extensions

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.git.GitGraphNativePanelBody
import com.hienlh.ppm.app.ui.git.GitInsightsNativePanelBody
import com.hienlh.ppm.design.FileIcons
import com.hienlh.ppm.state.git.GIT_INSIGHTS_VIEW_TYPES
import com.hienlh.ppm.design.LocalIconFramework
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.extensions.ExtensionWebviewBridge
import com.hienlh.ppm.state.extensions.ExternalLinkGate
import com.hienlh.ppm.state.extensions.WebviewPanelUI
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.web.ExtensionWebviewSurface
import com.hienlh.ppm.web.FrameChannel
import com.hienlh.ppm.web.isWebViewModernEnough
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt

private const val MAX_RETRIES = 3
private const val RETRY_INTERVAL_MS = 2_000L

@Composable
fun ExtensionWebviewBody(
    model: AppModel,
    tab: WorkspaceTab,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val theme = LocalPpmTheme.current
    val context = LocalContext.current
    val framework = LocalIconFramework.current
    val scope = rememberCoroutineScope()

    val ext = model.extensions
    val panels by (ext?.webviewPanels?.collectAsState() ?: remember { mutableStateOf(emptyMap()) })
    val contributions by (ext?.contributions?.collectAsState() ?: remember { mutableStateOf(null) })
    val activationErrors by (ext?.activationErrors?.collectAsState() ?: remember { mutableStateOf(emptyMap()) })

    val panelIdMeta = tab.metaString("panelId")
    val viewTypeMeta = tab.metaString("viewType")
    val extensionIdMeta = tab.metaString("extensionId")
    // Use the tab's own project name (frozen at creation time), NOT model.selectedProject (`extension-webview.tsx:76`).
    val tabProjectName = tab.metaString("projectName")?.takeIf { it.isNotEmpty() }
        ?: tab.projectId?.takeIf { it.isNotEmpty() }

    val panel: WebviewPanelUI? = remember(panels, panelIdMeta, viewTypeMeta, tabProjectName) {
        if (!panelIdMeta.isNullOrEmpty() && panelIdMeta in panels) {
            panels[panelIdMeta]
        } else if (!viewTypeMeta.isNullOrEmpty()) {
            val fullViewType = if (viewTypeMeta.contains(".")) viewTypeMeta else "$viewTypeMeta.view"
            val candidates = panels.values.filter { p ->
                p.viewType == viewTypeMeta || p.viewType == fullViewType
            }
            candidates.firstOrNull { p -> p.projectName != null && p.projectName == tabProjectName }
                ?: candidates.firstOrNull { p -> p.projectName == null || tabProjectName == null }
        } else {
            null
        }
    }

    val activationError: String? = remember(activationErrors, extensionIdMeta, viewTypeMeta) {
        if (!extensionIdMeta.isNullOrEmpty() && extensionIdMeta in activationErrors) {
            activationErrors[extensionIdMeta]
        } else if (!viewTypeMeta.isNullOrEmpty()) {
            val slug = viewTypeMeta.removeSuffix(".view")
            activationErrors["ext-$slug"] ?: activationErrors["ext-$viewTypeMeta"]
        } else {
            null
        }
    }

    var retryAttempt by remember(tab.id, viewTypeMeta) { mutableIntStateOf(0) }
    var failed by remember(tab.id, viewTypeMeta) { mutableStateOf(false) }

    // Keep tab's panelId metadata synced when matched by viewType + projectName
    LaunchedEffect(panel?.id, panelIdMeta) {
        if (panel != null && panel.id != panelIdMeta) {
            model.patchTabMetadata(tab.id, "panelId" to JsonPrimitive(panel.id))
        }
        if (panel != null) {
            retryAttempt = 0
            failed = false
        }
    }

    // Auto-recover when panel is missing (e.g. page reload or server restart)
    LaunchedEffect(panel == null, viewTypeMeta, tabProjectName, contributions != null, retryAttempt, failed) {
        if (panel != null || viewTypeMeta.isNullOrEmpty() || contributions == null || failed) return@LaunchedEffect
        val cmd = if (viewTypeMeta.contains(".")) viewTypeMeta else "$viewTypeMeta.view"
        if (retryAttempt == 0) {
            retryAttempt = 1
            model.dispatchExtCommand(cmd, recovery = true, projectName = tabProjectName)
        }
        delay(RETRY_INTERVAL_MS)
        if (retryAttempt >= MAX_RETRIES) {
            failed = true
        } else {
            retryAttempt += 1
            model.dispatchExtCommand(cmd, recovery = true, projectName = tabProjectName)
        }
    }

    val resolvedViewType = panel?.viewType ?: viewTypeMeta
    if (resolvedViewType == "git-graph.view" || resolvedViewType in GIT_INSIGHTS_VIEW_TYPES) {
        if (panel == null) {
            ExtensionLoadingOrFailedView(
                title = tab.title,
                failed = failed,
                activationError = activationError,
                onRetry = {
                    if (!viewTypeMeta.isNullOrEmpty()) {
                        retryAttempt = 1
                        failed = false
                        val cmd = if (viewTypeMeta.contains(".")) viewTypeMeta else "$viewTypeMeta.view"
                        model.dispatchExtCommand(cmd, recovery = true, projectName = tabProjectName)
                    }
                },
                modifier = modifier,
            )
            return
        }
        if (resolvedViewType == "git-graph.view") {
            GitGraphNativePanelBody(
                model = model,
                tab = tab,
                panel = panel,
                modifier = modifier,
            )
        } else if (resolvedViewType != null) {
            GitInsightsNativePanelBody(
                model = model,
                tab = tab,
                panel = panel,
                viewType = resolvedViewType,
                modifier = modifier,
            )
        }
        return
    }

    if (!isWebViewModernEnough(context)) {
        Box(
            modifier
                .fillMaxSize()
                .background(tokens.bgSolid.color)
                .padding(24.dp)
                .testTag("ext-webview-outdated"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.AlertTriangle, 28.dp, tokens.warning.color)
                BasicText(
                    "Android System WebView Update Required",
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(
                        color = tokens.text.color,
                        textAlign = TextAlign.Center,
                    ),
                )
                BasicText(
                    "Android System WebView 80+ is required to render extension webviews. Please update Android System WebView from Google Play.",
                    style = ppmText(12f, lineHeight = 18f).copy(
                        color = tokens.text3.color,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
        return
    }

    if (panel == null || panel.html.isEmpty()) {
        ExtensionLoadingOrFailedView(
            title = tab.title,
            failed = failed,
            activationError = activationError,
            onRetry = {
                if (!viewTypeMeta.isNullOrEmpty()) {
                    retryAttempt = 1
                    failed = false
                    val cmd = if (viewTypeMeta.contains(".")) viewTypeMeta else "$viewTypeMeta.view"
                    model.dispatchExtCommand(cmd, recovery = true, projectName = tabProjectName)
                }
            },
            modifier = modifier,
        )
        return
    }

    val themeMode = if (theme.isDark) "dark" else "light"
    val themeCss = remember(tokens) {
        ExtensionWebviewBridge.hostThemeCss { varName -> tokens.readCssVar(varName) }
    }
    val initialMode = remember(panel.id) { themeMode }
    val initialCss = remember(panel.id) { themeCss }
    val initialStateJson = remember(panel.id, panel.viewType, tabProjectName) {
        ext?.getWebviewState(panel.viewType, tabProjectName)
    }

    val srcDoc = remember(panel.html, initialMode, initialCss, initialStateJson) {
        ExtensionWebviewBridge.injectHostTheme(
            ExtensionWebviewBridge.injectVscodeApiShim(panel.html, initialStateJson),
            initialMode,
            initialCss,
        )
    }

    val channel = remember(panel.id) { FrameChannel() }
    val externalLinkGate = remember(panel.id) { ExternalLinkGate() }
    var crashed by remember(panel.id) { mutableStateOf(false) }

    fun postHostTheme() {
        channel.post(
            buildJsonObject {
                put("command", ExtensionWebviewBridge.HOST_THEME_MESSAGE)
                put("mode", themeMode)
                put("css", themeCss)
            },
        )
    }

    LaunchedEffect(themeMode, themeCss, panel.id) {
        postHostTheme()
    }

    LaunchedEffect(ext, panel.id) {
        val client = ext ?: return@LaunchedEffect
        client.webviewMessages.collect { (targetId, msg) ->
            if (targetId == panel.id) {
                channel.post(msg)
            }
        }
    }

    if (crashed) {
        ExtensionLoadingOrFailedView(
            title = tab.title,
            failed = true,
            activationError = activationError,
            onRetry = { crashed = false },
            modifier = modifier,
        )
        return
    }

    ExtensionWebviewSurface(
        srcDoc = srcDoc,
        title = panel.title,
        bgHex = tokens.bgSolid.toHexRgb(),
        channel = channel,
        onMessage = { rawJson ->
            val data = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull() ?: return@ExtensionWebviewSurface
            val stateUpdate = ExtensionWebviewBridge.setStateRequest(data)
            if (stateUpdate != null) {
                ext?.setWebviewState(panel.viewType, tabProjectName, stateUpdate)
                return@ExtensionWebviewSurface
            }
            val extLink = externalLinkGate.tryConsumeOpenExternal(data)
            if (extLink != null) {
                extLink.getOrNull()?.let { url ->
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
                return@ExtensionWebviewSurface
            }
            val iconNames = ExtensionWebviewBridge.fileIconsRequest(data)
            if (iconNames != null) {
                if (iconNames.isNotEmpty()) {
                    scope.launch {
                        val (icons, rules) = FileIcons.get(context).webviewFileIcons(iconNames, framework)
                        channel.post(ExtensionWebviewBridge.fileIconsAnswer(icons, rules))
                    }
                }
                return@ExtensionWebviewSurface
            }
            ext?.sendWebviewMessage(panel.id, data)
        },
        onFrameLoad = { postHostTheme() },
        onCrash = { crashed = true },
        onUserGesture = { externalLinkGate.recordUserGesture() },
        modifier = modifier
            .fillMaxSize()
            .testTag("ext-webview-${panel.id}"),
    )
}

@Composable
internal fun ExtensionLoadingOrFailedView(
    title: String,
    failed: Boolean,
    onRetry: () -> Unit,
    activationError: String? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Box(
        modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .padding(24.dp)
            .testTag(if (failed) "ext-webview-failed" else "ext-webview-loading"),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    "Extension failed to load",
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text3.color),
                )
                if (!activationError.isNullOrEmpty()) {
                    BasicText(
                        activationError,
                        style = ppmText(12f, lineHeight = 16f).copy(
                            color = tokens.text3.color,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(tokens.radSm.dp))
                        .cssBorder(tokens.border.color, RoundedCornerShape(tokens.radSm.dp))
                        .background(tokens.panel.color)
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("ext-webview-retry"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.RefreshCw, 12.dp, tokens.text.color)
                    BasicText(
                        "Retry",
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
                BasicText(
                    "Loading $title...",
                    style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color),
                )
            }
        }
    }
}

internal fun ThemeTokens.readCssVar(varName: String): String = when (varName) {
    "--bg-solid" -> bgSolid.toCssString()
    "--panel" -> panel.toCssString()
    "--panel-2" -> panel2.toCssString()
    "--accent-wash" -> accentWash.toCssString()
    "--accent-wash-border" -> accentWashBorder.toCssString()
    "--text" -> text.toCssString()
    "--text-2" -> text2.toCssString()
    "--text-3" -> text3.toCssString()
    "--border-soft" -> borderSoft.toCssString()
    "--border" -> border.toCssString()
    "--accent" -> accent.toCssString()
    "--accent-fg" -> accentFg.toCssString()
    "--success" -> success.toCssString()
    "--warning" -> warning.toCssString()
    "--error" -> error.toCssString()
    "--shadow-float" -> shadowFloat.toCssString()
    "--shadow-panel" -> shadowPanel.toCssString()
    else -> ""
}

internal fun Rgba.toHexRgb(): String {
    val ri = (r * 255.0).roundToInt().coerceIn(0, 255)
    val gi = (g * 255.0).roundToInt().coerceIn(0, 255)
    val bi = (b * 255.0).roundToInt().coerceIn(0, 255)
    return String.format(Locale.US, "#%02x%02x%02x", ri, gi, bi)
}

internal fun Rgba.toCssString(): String {
    val ri = (r * 255.0).roundToInt().coerceIn(0, 255)
    val gi = (g * 255.0).roundToInt().coerceIn(0, 255)
    val bi = (b * 255.0).roundToInt().coerceIn(0, 255)
    return if (a >= 0.999) {
        String.format(Locale.US, "#%02x%02x%02x", ri, gi, bi)
    } else {
        String.format(Locale.US, "rgba(%d, %d, %d, %.3f)", ri, gi, bi, a.coerceIn(0.0, 1.0))
    }
}

internal fun ThemeShadow.toCssString(): String =
    "${x}px ${y}px ${blur}px ${spread}px ${color.toCssString()}"
