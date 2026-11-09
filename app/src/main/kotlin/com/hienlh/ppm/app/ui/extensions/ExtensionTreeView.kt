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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.hienlh.ppm.state.extensions.TreeHeaderAction
import com.hienlh.ppm.state.extensions.TreeItemAction
import com.hienlh.ppm.state.extensions.TreeItemUI
import com.hienlh.ppm.state.extensions.inferTreeActionIcon
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
    val contributions by (ext?.contributions?.collectAsState() ?: remember { mutableStateOf(null) })
    val items = treeViews[viewId].orEmpty()
    val headerActions = remember(contributions, viewId) {
        contributions?.headerActionsForView(viewId).orEmpty()
    }

    ExtensionTreeViewList(
        viewId = viewId,
        viewName = viewName,
        items = items,
        onExpand = { itemId -> ext?.expandTree(viewId, itemId) },
        onCommand = { cmd, args -> model.dispatchExtCommand(cmd, args) },
        headerActions = headerActions,
        modifier = modifier,
    )
}

internal data class TreeFlatRow(
    val item: TreeItemUI,
    val depth: Int,
    val keyPath: String,
)

@Composable
fun ExtensionTreeViewList(
    viewId: String,
    viewName: String,
    items: List<TreeItemUI>,
    onExpand: (String) -> Unit,
    onCommand: (String, List<JsonElement>) -> Unit,
    headerActions: List<TreeHeaderAction> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val expandedOverrides = remember(viewId) { mutableStateMapOf<String, Boolean>() }
    val childrenLoaded = remember(viewId) { HashSet<String>() }

    // Prune stale expanded state after a root update or refresh, and auto-expand once when children arrive.
    LaunchedEffect(items) {
        if (items.isEmpty()) {
            expandedOverrides.clear()
            childrenLoaded.clear()
            return@LaunchedEffect
        }
        val validIds = HashSet<String>()
        val resetToCollapsed = HashSet<String>()
        val newlyLoaded = HashSet<String>()
        fun inspect(nodes: List<TreeItemUI>) {
            for (node in nodes) {
                validIds += node.id
                val kids = node.children
                if (kids.isNullOrEmpty()) {
                    if (node.id in childrenLoaded) {
                        resetToCollapsed += node.id
                    }
                } else {
                    if (node.id !in childrenLoaded) {
                        newlyLoaded += node.id
                    }
                    inspect(kids)
                }
            }
        }
        inspect(items)
        val staleKeys = expandedOverrides.keys.filter { it !in validIds || it in resetToCollapsed }
        for (k in staleKeys) expandedOverrides.remove(k)
        childrenLoaded.removeAll { it !in validIds || it in resetToCollapsed }
        for (id in newlyLoaded) {
            childrenLoaded += id
            if (expandedOverrides[id] != true) {
                expandedOverrides[id] = true
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(tokens.panel.color)
            .testTag("ext-treeview-$viewId"),
    ) {
        if (viewName.isNotEmpty() || headerActions.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .edge(Edge.Bottom, tokens.borderSoft.color)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    viewName.uppercase(),
                    style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color),
                )
                if (headerActions.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (action in headerActions) {
                            val iconName = action.icon ?: inferTreeActionIcon(action.command)
                            val iconVec = ExtensionIcons.icon(iconName) ?: ProductIcon.RefreshCw.vector
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onCommand(action.command, emptyList()) }
                                    .semantics { contentDescription = action.title }
                                    .testTag("ext-tree-header-action-${action.command}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                PpmIcon(iconVec, 14.dp, tokens.text3.color)
                            }
                        }
                    }
                }
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
            fun isNodeOpen(node: TreeItemUI): Boolean {
                return expandedOverrides[node.id] ?: (!node.children.isNullOrEmpty() && node.id !in childrenLoaded)
            }

            val flatRows = remember(items, expandedOverrides.toMap()) {
                val out = mutableListOf<TreeFlatRow>()
                val usedKeys = HashSet<String>()
                fun walk(nodes: List<TreeItemUI>, depth: Int, parentKey: String) {
                    for ((idx, node) in nodes.withIndex()) {
                        val baseKey = if (parentKey.isEmpty()) node.id else "$parentKey/${node.id}"
                        val keyPath = if (usedKeys.add(baseKey)) baseKey else "$baseKey#$idx"
                        out += TreeFlatRow(item = node, depth = depth, keyPath = keyPath)
                        val isCollapsible = node.collapsibleState != "none"
                        val isOpen = isNodeOpen(node)
                        val kids = node.children
                        if (isCollapsible && isOpen && !kids.isNullOrEmpty()) {
                            walk(kids, depth + 1, keyPath)
                        }
                    }
                }
                walk(items, 0, "")
                out
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(vertical = 4.dp),
            ) {
                items(flatRows, key = { row -> row.keyPath }) { row ->
                    val item = row.item
                    val depth = row.depth
                    val isCollapsible = item.collapsibleState != "none"
                    val isOpen = isNodeOpen(item)
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
                                if (nextOpen && item.children.isNullOrEmpty()) {
                                    onExpand(item.id)
                                }
                            }
                            if (!cmd.isNullOrEmpty()) {
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
                    .let { m ->
                        if (action.tooltip.isNotEmpty()) m.semantics { contentDescription = action.tooltip } else m
                    }
                    .testTag("ext-tree-action-${item.id}-${action.command}"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(actionIcon, 12.dp, tokens.text2.color)
            }
        }
    }
}
