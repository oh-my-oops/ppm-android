package com.hienlh.ppm.app.ui.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.extensions.TreeItemAction
import com.hienlh.ppm.state.extensions.TreeItemUI
import kotlinx.serialization.json.JsonElement

@Composable
fun ExtensionTreeViewPanel(
    model: AppModel,
    viewId: String,
    viewName: String,
    modifier: Modifier = Modifier,
) {
    val ext = model.extensions
    val treeViews by (ext?.treeViews?.collectAsState() ?: remember { mutableStateOf(emptyMap()) })
    val items = treeViews[viewId].orEmpty()

    ExtensionTreeViewList(
        viewId = viewId,
        viewName = viewName,
        items = items,
        onExpand = { itemId -> ext?.expandTree(viewId, itemId) },
        onCommand = { cmd, args -> model.dispatchExtCommand(cmd, args) },
        modifier = modifier,
    )
}

@Composable
fun ExtensionTreeViewList(
    viewId: String,
    viewName: String,
    items: List<TreeItemUI>,
    onExpand: (String) -> Unit,
    onCommand: (String, List<JsonElement>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val expandedOverrides = remember(viewId) { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier
            .fillMaxSize()
            .background(tokens.panel.color)
            .testTag("ext-treeview-$viewId"),
    ) {
        if (viewName.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .edge(Edge.Bottom, tokens.borderSoft.color)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    viewName.uppercase(),
                    style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color),
                )
            }
        }

        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "No items",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
        } else {
            val flatRows = remember(items, expandedOverrides.toMap()) {
                val out = mutableListOf<Pair<TreeItemUI, Int>>()
                fun walk(nodes: List<TreeItemUI>, depth: Int) {
                    for (node in nodes) {
                        out += node to depth
                        val isCollapsible = node.collapsibleState != "none"
                        val isOpen = expandedOverrides[node.id] ?: (node.collapsibleState == "expanded")
                        val kids = node.children
                        if (isCollapsible && isOpen && !kids.isNullOrEmpty()) {
                            walk(kids, depth + 1)
                        }
                    }
                }
                walk(items, 0)
                out
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(vertical = 4.dp),
            ) {
                items(flatRows, key = { (item, depth) -> "$depth:${item.id}" }) { (item, depth) ->
                    val isCollapsible = item.collapsibleState != "none"
                    val isOpen = expandedOverrides[item.id] ?: (item.collapsibleState == "expanded")
                    ExtensionTreeRow(
                        item = item,
                        depth = depth,
                        isCollapsible = isCollapsible,
                        isOpen = isOpen,
                        onToggleOrClick = {
                            val cmd = item.command
                            if (isCollapsible) {
                                val nextOpen = !isOpen
                                expandedOverrides[item.id] = nextOpen
                                if (nextOpen && item.children == null) {
                                    onExpand(item.id)
                                }
                            } else if (!cmd.isNullOrEmpty()) {
                                onCommand(cmd, item.commandArgs)
                            }
                        },
                        onAction = { action ->
                            onCommand(action.command, action.commandArgs)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionTreeRow(
    item: TreeItemUI,
    depth: Int,
    isCollapsible: Boolean,
    isOpen: Boolean,
    onToggleOrClick: () -> Unit,
    onAction: (TreeItemAction) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val iconVec = ExtensionIcons.icon(item.icon)
    val iconTint: Color = remember(item.color, tokens) {
        item.color?.let { Rgba.hex(it)?.color } ?: tokens.text3.color
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleOrClick)
            .padding(
                start = (depth * 14 + 8).dp,
                end = 8.dp,
                top = 5.dp,
                bottom = 5.dp,
            )
            .testTag("ext-tree-item-${item.id}"),
        verticalAlignment = if (item.wrap) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isCollapsible) {
            PpmIcon(
                if (isOpen) ProductIcon.ChevronDown.vector else ProductIcon.ChevronRight.vector,
                14.dp,
                tokens.text3.color,
            )
        } else {
            Spacer(Modifier.width(14.dp))
        }

        if (iconVec != null) {
            PpmIcon(iconVec, 14.dp, iconTint)
        }

        BasicText(
            item.label,
            Modifier.weight(1f),
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
            maxLines = if (item.wrap) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )

        val badge = item.badge
        if (!badge.isNullOrEmpty()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.accent.opacity(0.15).color)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                BasicText(
                    badge,
                    style = ppmText(10f, FontWeight.Medium, kind = FontKind.MONO, lineHeight = 14f).copy(
                        color = tokens.accent.color,
                    ),
                    maxLines = 1,
                )
            }
        }

        val desc = item.description
        if (!desc.isNullOrEmpty()) {
            BasicText(
                desc,
                style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        for (action in item.actions) {
            val actionIcon = ExtensionIcons.icon(action.icon) ?: ProductIcon.RefreshCw.vector
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onAction(action) }
                    .testTag("ext-tree-action-${item.id}-${action.command}"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(actionIcon, 12.dp, tokens.text2.color)
            }
        }
    }
}
