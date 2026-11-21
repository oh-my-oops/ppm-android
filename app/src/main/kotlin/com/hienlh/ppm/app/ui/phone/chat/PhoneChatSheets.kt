package com.hienlh.ppm.app.ui.phone.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.chat.middleEllipsis
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.ConfirmDialog
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone tool-card scope policy (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`,
 * Section 3 C1 & Note 77):
 * - `open_preview` on phone omits the `Open` tab button and expands inline (`"no problems"` / `"N problems"`).
 *
 * An `open_query` tab runs on a phone as on a tablet, a readonly connection's refused script
 * included: the query tab offers "Run with write access (once)" there (`write-once-dialog.tsx`).
 */
object PhoneToolCardPolicy {
    fun canOpenPreviewInTabOnPhone(toolName: String): Boolean {
        val clean = toolName.trim()
        return clean != "open_preview" && !clean.endsWith("__open_preview")
    }

    fun inlinePreviewDiagnosticSummary(problemCount: Int): String =
        if (problemCount <= 0) "no problems" else "$problemCount problem${if (problemCount == 1) "" else "s"}"
}

/**
 * Long-press message action sheet on phone (Phone 1 Section 2 B12):
 * 4 top action tiles (68 dp: `Copy`, `Reply`, `Edit`, `Fork`) + 2 list rows (52 dp: `Copy turn link`, `Select text`).
 */
@Composable
fun PhoneMessageActionSheet(
    isUserMessage: Boolean,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onFork: () -> Unit,
    onCopyTurnLink: () -> Unit,
    onSelectText: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Sheet(
        title = "Message actions",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 4 x 68 dp action tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MessageActionTile(
                    icon = ProductIcon.Copy,
                    label = "Copy",
                    onClick = {
                        onCopy()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                MessageActionTile(
                    icon = ProductIcon.ArrowLeft,
                    label = "Reply",
                    onClick = {
                        onReply()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                if (isUserMessage) {
                    MessageActionTile(
                        icon = ProductIcon.Pencil,
                        label = "Edit",
                        onClick = {
                            onEdit()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                MessageActionTile(
                    icon = ProductIcon.GitBranch,
                    label = "Fork",
                    onClick = {
                        onFork()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // 52 dp list rows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(14.dp)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .ppmClickable(
                            onClickLabel = "Copy turn link",
                            onClick = {
                                onCopyTurnLink()
                                onDismiss()
                            },
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PpmIcon(ProductIcon.Link, 18.dp, tokens.text2.color)
                    BasicText(
                        text = "Copy turn link",
                        style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = tokens.text.color),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                        .ppmClickable(
                            onClickLabel = "Select text",
                            onClick = {
                                onSelectText()
                                onDismiss()
                            },
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PpmIcon(ProductIcon.FileText, 18.dp, tokens.text2.color)
                    BasicText(
                        text = "Select text",
                        style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = tokens.text.color),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageActionTile(
    icon: ProductIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .height(68.dp)
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .ppmClickable(
                shape = shape,
                onClickLabel = label,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        PpmIcon(icon, 18.dp, tokens.text.color)
        BasicText(
            text = label,
            style = ppmText(12f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text.color),
            maxLines = 1,
        )
    }
}

/**
 * Floating pill shown when scrolled to a turn link (`"Viewing turn N · Back to latest"`, Phone 1 B12).
 */
@Composable
fun PhoneTurnJumpPill(
    turnNumber: Int,
    onBackToLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .height(40.dp)
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.accent.opacity(0.45).color, shape)
            .ppmClickable(
                shape = shape,
                onClickLabel = "Viewing turn $turnNumber · Back to latest",
                onClick = onBackToLatest,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = "Viewing turn #$turnNumber · Back to latest",
            style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accent.color),
        )
        PpmIcon(ProductIcon.ArrowDown, 14.dp, tokens.accent.color)
    }
}

/**
 * Floating pill shown when 2+ error banners accumulate (`"Clear all errors (N)"`, Phone 1 B15).
 */
@Composable
fun PhoneClearErrorsPill(
    errorCount: Int,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (errorCount < 2) return
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .height(40.dp)
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.error.opacity(0.45).color, shape)
            .ppmClickable(
                shape = shape,
                onClickLabel = "Clear all errors ($errorCount)",
                onClick = onClearAll,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.X, 14.dp, tokens.error.color)
        BasicText(
            text = "Clear all errors ($errorCount)",
            style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.error.color),
        )
    }
}

/**
 * Attachment item displayed in the composer attachment tray (Phone 1 Section 5 E3).
 */
data class PhoneDraftAttachment(
    val id: String,
    val name: String,
    val sizeLabel: String,
    val isImage: Boolean = false,
)

/**
 * 64 dp attachment tray inside the composer card with 28 dp remove buttons (Phone 1 E3).
 */
@Composable
fun PhoneAttachmentTray(
    attachments: List<PhoneDraftAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { item ->
            val cardShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .height(56.dp)
                    .clip(cardShape)
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.border.color, cardShape)
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(
                    icon = if (item.isImage) ProductIcon.Image else ProductIcon.FileText,
                    size = 18.dp,
                    tint = tokens.accent.color,
                )
                Column(modifier = Modifier.width(110.dp)) {
                    BasicText(
                        text = item.name,
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        text = item.sizeLabel,
                        style = ppmText(10.5f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(tokens.panel.color)
                        .ppmClickable(
                            shape = CircleShape,
                            onClickLabel = "Remove ${item.name}",
                            onClick = { onRemove(item.id) },
                        )
                        .semantics { contentDescription = "Remove ${item.name}" },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 14.dp, tokens.text2.color)
                }
            }
        }
    }
}

/**
 * The composer's + sheet on phone, laid out as Claude's app's "Add to Chat": recent photos strip
 * (76x76 dp tiles), then `Camera`, `Photos`, `Files` tiles, then the chat's settings as rows — the
 * permission mode ([onOpenPermissionMode]; null hides the row).
 */
@Composable
fun PhoneAttachSheet(
    onPickPhotos: () -> Unit,
    /** Null hides Camera (no camera app on this device). */
    onOpenCamera: (() -> Unit)?,
    onPickFiles: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    recentPhotoLabels: List<String> = emptyList(),
    onSelectRecentPhoto: (Int) -> Unit = {},
    permissionModeLabel: String = "",
    /** The mode is Bypass: the row is drawn in the error colour, as the + button's dot. */
    permissionModeAlert: Boolean = false,
    onOpenPermissionMode: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    Sheet(
        title = "Add to Chat",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (recentPhotoLabels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recentPhotoLabels.forEachIndexed { index, label ->
                        val tileShape = RoundedCornerShape(12.dp)
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(tileShape)
                                .background(tokens.panel.color)
                                .cssBorder(tokens.border.color, tileShape)
                                .ppmClickable(
                                    shape = tileShape,
                                    onClickLabel = label,
                                    onClick = {
                                        onSelectRecentPhoto(index)
                                        onDismiss()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                PpmIcon(ProductIcon.Image, 20.dp, tokens.text2.color)
                                BasicText(
                                    text = label,
                                    style = ppmText(10f, lineHeight = 13f).copy(color = tokens.text3.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onOpenCamera != null) {
                    AttachActionTile(
                        icon = ProductIcon.ImagePlus,
                        label = "Camera",
                        onClick = {
                            onOpenCamera()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                AttachActionTile(
                    icon = ProductIcon.Image,
                    label = "Photos",
                    onClick = {
                        onPickPhotos()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                AttachActionTile(
                    icon = ProductIcon.Folder,
                    label = "Files",
                    onClick = {
                        onPickFiles()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (onOpenPermissionMode != null) {
                val shape = RoundedCornerShape(14.dp)
                val tint = if (permissionModeAlert) tokens.error.color else tokens.text2.color
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(shape)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, shape)
                        .ppmClickable(
                            shape = shape,
                            onClickLabel = "Permission mode: $permissionModeLabel",
                            onClick = {
                                onDismiss()
                                onOpenPermissionMode()
                            },
                        )
                        .testTag("phone-attach-permission-mode")
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PpmIcon(ProductIcon.ShieldAlert, 20.dp, tint)
                    BasicText(
                        text = "Permission mode",
                        modifier = Modifier.weight(1f),
                        style = ppmText(15f, lineHeight = 20f).copy(color = tokens.text.color),
                    )
                    BasicText(
                        text = permissionModeLabel,
                        style = ppmText(14f, lineHeight = 18f).copy(color = if (permissionModeAlert) tokens.error.color else tokens.text3.color),
                        maxLines = 1,
                    )
                    PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color)
                }
            }
        }
    }
}

@Composable
private fun AttachActionTile(
    icon: ProductIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .height(84.dp)
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .ppmClickable(
                shape = shape,
                onClickLabel = label,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        PpmIcon(icon, 22.dp, tokens.text.color)
        BasicText(
            text = label,
            style = ppmText(15f, lineHeight = 20f).copy(color = tokens.text.color),
        )
    }
}

/**
 * 44 dp turn reference chip (`Turn #N`) inside the composer (Phone 1 Section 5 E6).
 */
@Composable
fun PhoneTurnRefChip(
    turnNumber: Int,
    snippet: String,
    onOpenPreview: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(tokens.accentWash.color)
            .cssBorder(tokens.accentWashBorder.color, shape)
            .ppmClickable(
                shape = shape,
                onClickLabel = "Turn #$turnNumber: $snippet",
                onClick = onOpenPreview,
            )
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.ArrowLeft, 14.dp, tokens.accent.color)
        BasicText(
            text = "Turn #$turnNumber",
            style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f).copy(color = tokens.accent.color),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .ppmClickable(
                    shape = CircleShape,
                    onClickLabel = "Remove Turn #$turnNumber",
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.X, 14.dp, tokens.accent.color)
        }
    }
}

/**
 * Turn reference preview sheet opened on tap / long-press of a turn reference chip (Phone 1 E6).
 */
@Composable
fun PhoneTurnRefPreviewSheet(
    turnNumber: Int,
    authorLabel: String,
    previewText: String,
    onOpenTurn: () -> Unit,
    onRemoveReference: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Sheet(
        title = "Turn #$turnNumber",
        subtitle = authorLabel,
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
        footer = {
            SheetButton(
                label = "Remove reference",
                destructive = true,
                outlined = true,
                onClick = {
                    onRemoveReference()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            SheetButton(
                label = "Open turn #$turnNumber",
                primary = true,
                onClick = {
                    onOpenTurn()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                .padding(14.dp),
        ) {
            BasicText(
                text = previewText,
                style = ppmText(13.5f, lineHeight = 20f).copy(color = tokens.text.color),
            )
        }
    }
}

/**
 * Subagent step entry displayed inside [PhoneAgentSessionSheet] (Phone 1 Section 3 C5).
 */
data class PhoneAgentStepItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val status: String = "done",
)

/**
 * Full-height subagent session sheet (`Agent session`, Phone 1 Section 3 C5) with collapsible
 * `"Prompt"` disclosure and step cards on page tint so nested cards do not stack 3 borders deep.
 */
@Composable
fun PhoneAgentSessionSheet(
    agentTitle: String,
    agentSubtitle: String,
    promptText: String,
    steps: List<PhoneAgentStepItem>,
    summaryText: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var promptExpanded by remember { mutableStateOf(false) }

    Sheet(
        title = "Agent session",
        subtitle = "$agentTitle · $agentSubtitle",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.bgSolid.color)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Collapsible Prompt disclosure
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(12.dp)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .ppmClickable(
                            onClickLabel = "Prompt",
                            onClick = { promptExpanded = !promptExpanded },
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(
                        text = "Prompt",
                        style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text2.color),
                    )
                    PpmIcon(
                        icon = if (promptExpanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                        size = 16.dp,
                        tint = tokens.text3.color,
                    )
                }
                if (promptExpanded) {
                    BasicText(
                        text = promptText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                            .padding(12.dp),
                        style = ppmText(13f, lineHeight = 19f).copy(color = tokens.text.color),
                    )
                }
            }

            // Agent steps
            steps.forEach { step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val running = step.status == "running"
                    PpmIcon(
                        icon = if (running) ProductIcon.Loader2 else ProductIcon.Check,
                        size = 15.dp,
                        tint = if (running) tokens.accent.color else tokens.success.color,
                        spinning = running,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = step.title,
                            style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                        )
                        step.subtitle?.let { sub ->
                            BasicText(
                                text = sub,
                                style = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (!summaryText.isNullOrEmpty()) {
                BasicText(
                    text = summaryText,
                    modifier = Modifier.padding(top = 4.dp),
                    style = ppmText(14f, lineHeight = 21f).copy(color = tokens.text.color),
                )
            }
        }
    }
}

/** One background command the bar shows: its shell id, command line, and whether a stop is under way. */
data class PhoneBackgroundCommand(val id: String, val command: String, val stopping: Boolean = false)

/**
 * 52 dp top bar for a running background command with 44 dp `"View output"` and `"Stop"` buttons
 * (Phone 1 Section 6 F1, Note 90: kept at the top of the chat view so it stays clear of the thumb zone).
 */
@Composable
fun PhoneBackgroundCommandBar(
    command: String,
    elapsedLabel: String,
    onViewOutput: () -> Unit,
    /** Null hides Stop (already asked to stop). */
    onStop: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.Terminal, 16.dp, tokens.accent.color)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = command,
                style = ppmText(12f, FontWeight.Medium, FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = elapsedLabel,
                style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(10.dp))
                .ppmClickable(
                    shape = RoundedCornerShape(10.dp),
                    onClickLabel = "View output",
                    onClick = onViewOutput,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "View output",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
        }
        if (onStop != null) Box(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.error.opacity(0.14).color)
                .cssBorder(tokens.error.opacity(0.40).color, RoundedCornerShape(10.dp))
                .ppmClickable(
                    shape = RoundedCornerShape(10.dp),
                    onClickLabel = "Stop",
                    onClick = onStop,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "Stop",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.error.color),
            )
        }
    }
}

/**
 * Background command output bottom sheet with `"Stop command"` button at the foot (Phone 1 F1).
 */
@Composable
fun PhoneBackgroundOutputSheet(
    command: String,
    outputText: String,
    running: Boolean,
    onStopCommand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Sheet(
        title = "Background command",
        subtitle = command,
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
        footer = if (running) {
            {
                SheetButton(
                    label = "Stop command",
                    destructive = true,
                    primary = true,
                    onClick = {
                        onStopCommand()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            null
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            BasicText(
                text = outputText.ifEmpty { "Waiting for output…" },
                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 18f).copy(color = tokens.text.color),
            )
        }
    }
}

/**
 * File entry in [PhoneSessionChangesSheet] and [PhoneTurnChangesSheet] (Phone 1 Section 6 F4 & F5).
 */
data class PhoneChangedFileItem(
    /** What identifies the file to the callbacks (the server's absolute path for a session's change). */
    val path: String,
    val additions: Int,
    val deletions: Int,
    val reviewed: Boolean = false,
    /** What the row shows: the path relative to the project. */
    val displayPath: String = path,
)

/**
 * Session changes bottom sheet on phone (Phone 1 Section 6 F4, Note 91):
 * - Header shows `"N files changed"` + `+A −D`.
 * - Each file row has a 48 dp `"Reviewed"` checkbox and opens the file diff on row tap.
 * - No `"Review"` side-pane button on phone (side-by-side review belongs to tablet).
 */
@Composable
fun PhoneSessionChangesSheet(
    files: List<PhoneChangedFileItem>,
    onToggleReviewed: (String) -> Unit,
    onOpenFileDiff: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val totalAdd = files.sumOf { it.additions }
    val totalDel = files.sumOf { it.deletions }

    Sheet(
        title = "${files.size} file${if (files.size == 1) "" else "s"} changed",
        subtitle = "+$totalAdd −$totalDel",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            files.forEachIndexed { index, file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .then(if (index > 0) Modifier.cssBorder(tokens.borderSoft.color, sides = CssSides.Top) else Modifier)
                        .ppmClickable(
                            onClickLabel = "Open diff for ${file.displayPath}",
                            onClick = {
                                onOpenFileDiff(file.path)
                                onDismiss()
                            },
                        )
                        .padding(start = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 48 dp Reviewed checkbox touch target
                    Box(
                        modifier = Modifier
                            .size(KitSpec.MinTouchTarget)
                            .clip(RoundedCornerShape(10.dp))
                            .ppmClickable(
                                shape = RoundedCornerShape(10.dp),
                                onClickLabel = "Reviewed ${file.displayPath}",
                                onClick = { onToggleReviewed(file.path) },
                            )
                            .semantics { contentDescription = "Reviewed ${file.displayPath}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (file.reviewed) tokens.accent.color else tokens.panel.color)
                                .cssBorder(
                                    color = if (file.reviewed) tokens.accent.color else tokens.border.color,
                                    shape = RoundedCornerShape(6.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (file.reviewed) {
                                PpmIcon(ProductIcon.Check, 13.dp, tokens.accentFg.color)
                            }
                        }
                    }

                    BasicText(
                        text = file.displayPath,
                        modifier = Modifier.weight(1f),
                        style = ppmText(13f, FontWeight.Medium, FontKind.MONO, lineHeight = 18f)
                            .copy(color = if (file.reviewed) tokens.text3.color else tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText(
                            text = "+${file.additions}",
                            style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f)
                                .copy(color = tokens.success.color),
                        )
                        BasicText(
                            text = "−${file.deletions}",
                            style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f)
                                .copy(color = tokens.error.color),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Changed-this-turn sheet on phone with per-edit 48 dp `"Revert"` and `"Keep"` buttons
 * and `"Revert all"` / `"Keep all"` in the footer (Phone 1 Section 6 F5).
 */
@Composable
fun PhoneTurnChangesSheet(
    files: List<PhoneChangedFileItem>,
    onRevertFile: (String) -> Unit,
    onKeepFile: (String) -> Unit,
    onRevertAll: () -> Unit,
    onKeepAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Sheet(
        title = "Changed this turn (${files.size})",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
        footer = {
            SheetButton(
                label = "Revert all",
                destructive = true,
                outlined = true,
                onClick = {
                    onRevertAll()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            SheetButton(
                label = "Keep all",
                primary = true,
                onClick = {
                    onKeepAll()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            files.forEach { file ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicText(
                            text = file.path,
                            modifier = Modifier.weight(1f),
                            style = ppmText(13f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 18f)
                                .copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            text = "+${file.additions} −${file.deletions}",
                            style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SheetButton(
                            label = "Revert",
                            destructive = true,
                            outlined = true,
                            onClick = { onRevertFile(file.path) },
                            modifier = Modifier.weight(1f),
                        )
                        SheetButton(
                            label = "Keep",
                            outlined = true,
                            onClick = { onKeepFile(file.path) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Confirmation dialog shown when stopping a parent turn that has running child sessions (Phone 1 F6).
 */
@Composable
fun PhoneStopChildSessionsDialog(
    childCount: Int,
    onKeepRunning: () -> Unit,
    onStopThemToo: () -> Unit,
) {
    ConfirmDialog(
        title = "Stop child sessions?",
        message = "$childCount child session${if (childCount == 1) " is" else "s are"} still running.",
        confirmLabel = "Stop them too",
        cancelLabel = "Keep running",
        destructive = true,
        onConfirm = onStopThemToo,
        onDismiss = onKeepRunning,
    )
}

/**
 * Spawned session preview sheet on phone (`Open full session` / `Send message`, Phone 1 F7).
 */
@Composable
fun PhoneSpawnedSessionSheet(
    title: String,
    statusLabel: String,
    previewMessages: List<String>,
    onOpenFullSession: () -> Unit,
    onSendMessage: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Sheet(
        title = title,
        subtitle = statusLabel,
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
        footer = {
            SheetButton(
                label = "Send message",
                outlined = true,
                onClick = onSendMessage,
                modifier = Modifier.weight(1f),
            )
            SheetButton(
                label = "Open full session",
                primary = true,
                onClick = {
                    onOpenFullSession()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            previewMessages.forEach { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    BasicText(
                        text = msg,
                        style = ppmText(13f, lineHeight = 19f).copy(color = tokens.text.color),
                    )
                }
            }
        }
    }
}

/**
 * Data model for the phone Session Info sheet (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`,
 * Section 8 H2).
 */
data class PhoneSessionInfoData(
    val sessionId: String,
    val title: String,
    val modelLabel: String,
    val modeLabel: String,
    val accountLabel: String,
    val costLabel: String,
    val projectName: String,
    val tagLabel: String? = null,
    val createdByLabel: String? = null,
    val linkedSessionsCount: Int = 0,
)

/**
 * Phone Session Info bottom sheet (Phone 1 Section 8 H2):
 * Gathers chat facts (`Title` + `Rename`, `Model`, `Mode`, `Account`, `Cost`, `Project`, `Tag`,
 * `Created by`, `Linked`, `Session id` with copy) and action rows (`Mark as unread`,
 * `Show logs for this chat`, `Session debug info`).
 */
@Composable
fun PhoneSessionInfoSheet(
    info: PhoneSessionInfoData,
    /** Null (a chat with no session yet) hides Rename and the session's action rows. */
    onRename: (() -> Unit)?,
    onOpenModelSheet: () -> Unit,
    onOpenModeSheet: () -> Unit,
    onMarkAsUnread: (() -> Unit)?,
    onShowLogs: (() -> Unit)?,
    onOpenSessionDebug: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The session is unread already: the row marks it read, as the ··· menu's does. */
    hasUnread: Boolean = false,
    /** The ··· menu's View, Bookmarks and Export as Markdown (`PhoneChatExtraRows`). */
    extras: com.hienlh.ppm.app.ui.ChatMoreExtras? = null,
) {
    val tokens = LocalPpmTokens.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copiedId by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    val bookmarks = extras?.bookmarks
    if (bookmarksOpen && bookmarks != null) {
        PhoneBookmarksSheet(bookmarks, onBack = { bookmarksOpen = false }, onDismiss = onDismiss)
        return
    }

    Sheet(
        title = "Session info",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Facts card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                // Title + Rename button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BasicText(
                        text = "Title",
                        modifier = Modifier.width(84.dp),
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                    BasicText(
                        text = info.title.ifEmpty { "Untitled" },
                        modifier = Modifier.weight(1f),
                        style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onRename != null) Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.panel2.color)
                            .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                            .ppmClickable(
                                shape = RoundedCornerShape(8.dp),
                                onClickLabel = "Rename",
                                onClick = onRename,
                            )
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = "Rename",
                            style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                        )
                    }
                }

                SessionInfoKvRow(label = "Model", value = info.modelLabel, onClick = onOpenModelSheet)
                SessionInfoKvRow(label = "Mode", value = info.modeLabel, onClick = onOpenModeSheet)
                SessionInfoKvRow(label = "Account", value = info.accountLabel)
                SessionInfoKvRow(label = "Cost", value = info.costLabel, mono = true)
                SessionInfoKvRow(label = "Project", value = info.projectName, mono = true)
                info.tagLabel?.let { SessionInfoKvRow(label = "Tag", value = it) }
                info.createdByLabel?.let { SessionInfoKvRow(label = "Created by", value = it) }
                if (info.linkedSessionsCount > 0) {
                    SessionInfoKvRow(
                        label = "Linked",
                        value = "${info.linkedSessionsCount} session${if (info.linkedSessionsCount == 1) "" else "s"}",
                    )
                }

                // Session id with copy button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BasicText(
                        text = "Session id",
                        modifier = Modifier.width(84.dp),
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                    BasicText(
                        text = middleEllipsis(info.sessionId, head = 8, tail = 8),
                        modifier = Modifier.weight(1f),
                        style = ppmText(12.5f, kind = FontKind.MONO, lineHeight = 17f).copy(color = tokens.text2.color),
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.panel2.color)
                            .ppmClickable(
                                shape = RoundedCornerShape(8.dp),
                                onClickLabel = "Copy session id",
                                onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("Session id", info.sessionId)),
                                        )
                                        copiedId = true
                                        delay(1_500)
                                        copiedId = false
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(
                            icon = if (copiedId) ProductIcon.ClipboardCheck else ProductIcon.Copy,
                            size = 15.dp,
                            tint = if (copiedId) tokens.success.color else tokens.text2.color,
                        )
                    }
                }
            }

            // Action rows (52 dp each)
            val actions = listOfNotNull(
                onMarkAsUnread?.let { Triple(ProductIcon.Circle, if (hasUnread) "Mark as read" else "Mark as unread", it) },
                onShowLogs?.let { Triple(ProductIcon.ScrollText, "Show logs for this chat", it) },
                onOpenSessionDebug?.let { Triple(ProductIcon.Bug, "Session debug info", it) },
            )
            if (actions.isNotEmpty()) Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(14.dp)),
            ) {
                actions.forEachIndexed { index, (icon, label, action) ->
                    SessionInfoActionRow(
                        icon = icon,
                        label = label,
                        showTopBorder = index > 0,
                        onClick = {
                            action()
                            onDismiss()
                        },
                    )
                }
            }
            if (extras != null) PhoneChatExtraRows(extras, onOpenBookmarks = { bookmarksOpen = true }, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun SessionInfoKvRow(
    label: String,
    value: String,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
            .then(
                if (onClick != null) {
                    Modifier.ppmClickable(onClickLabel = "$label: $value", onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            text = label,
            modifier = Modifier.width(84.dp),
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text3.color),
        )
        BasicText(
            text = value,
            modifier = Modifier.weight(1f),
            style = ppmText(
                size = 13f,
                weight = FontWeight.Medium,
                kind = if (mono) FontKind.MONO else FontKind.SANS,
                lineHeight = 18f,
            ).copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            PpmIcon(ProductIcon.ChevronRight, 15.dp, tokens.text3.color)
        }
    }
}

@Composable
internal fun SessionInfoActionRow(
    icon: ProductIcon,
    label: String,
    showTopBorder: Boolean = false,
    /** A short value before the chevron ("Normal · Ctrl+O", a count). */
    hint: String? = null,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (showTopBorder) Modifier.cssBorder(tokens.borderSoft.color, sides = CssSides.Top) else Modifier)
            .ppmClickable(
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(icon, 18.dp, tokens.text2.color)
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = tokens.text.color),
        )
        if (hint != null) BasicText(hint, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color), maxLines = 1)
        PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color)
    }
}

@Composable
private fun SheetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    outlined: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        primary && destructive -> tokens.error.color
        primary -> tokens.accent.color
        outlined && destructive -> tokens.error.opacity(0.10).color
        else -> tokens.panel.color
    }
    val border = when {
        primary -> Color.Transparent
        destructive -> tokens.error.opacity(0.45).color
        else -> tokens.border.color
    }
    val fg = when {
        primary && destructive -> Color.White
        primary -> tokens.accentFg.color
        destructive -> tokens.error.color
        else -> tokens.text.color
    }
    Box(
        modifier = modifier
            .height(KitSpec.MinTouchTarget)
            .clip(shape)
            .background(bg)
            .then(if (!primary) Modifier.cssBorder(border, shape) else Modifier)
            .ppmClickable(
                shape = shape,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = fg),
            maxLines = 1,
        )
    }
}
