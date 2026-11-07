package com.hienlh.ppm.app.ui.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.extensions.InputBoxRequest
import com.hienlh.ppm.state.extensions.QuickPickItemUI
import com.hienlh.ppm.state.extensions.QuickPickRequest

@Composable
fun ExtensionOverlays(model: AppModel) {
    val ext = model.extensions ?: return
    val quickPick by ext.quickPick.collectAsState()
    val inputBox by ext.inputBox.collectAsState()

    quickPick?.let { req ->
        Dialog(onDismissRequest = { ext.resolveQuickPick(req.requestId, null) }) {
            ExtensionQuickPickContent(
                request = req,
                onResolve = { selected -> ext.resolveQuickPick(req.requestId, selected) },
            )
        }
    }

    inputBox?.let { req ->
        Dialog(onDismissRequest = { ext.resolveInputBox(req.requestId, null) }) {
            ExtensionInputBoxContent(
                request = req,
                onResolve = { value -> ext.resolveInputBox(req.requestId, value) },
            )
        }
    }
}

@Composable
fun ExtensionQuickPickContent(
    request: QuickPickRequest,
    onResolve: (List<QuickPickItemUI>?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var search by remember(request.requestId) { mutableStateOf("") }
    val selectedSet = remember(request.requestId) {
        mutableStateListOf<String>().apply {
            request.items.filter { it.picked }.forEach { add(it.label) }
        }
    }

    val filtered = remember(request.items, search) {
        if (search.isBlank()) {
            request.items
        } else {
            val q = search.trim().lowercase()
            request.items.filter { item ->
                item.label.lowercase().contains(q) ||
                    item.description?.lowercase()?.contains(q) == true ||
                    item.detail?.lowercase()?.contains(q) == true
            }
        }
    }

    val shape = RoundedCornerShape(tokens.rad.dp)
    Column(
        modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .clip(shape)
            .cssBorder(tokens.border.color, shape)
            .background(tokens.panel.color)
            .testTag("ext-quickpick-dialog"),
    ) {
        // Search header
        Row(
            Modifier
                .fillMaxWidth()
                .edge(Edge.Bottom, tokens.borderSoft.color)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Search, 14.dp, tokens.text3.color)
            Box(Modifier.weight(1f)) {
                if (search.isEmpty()) {
                    BasicText(
                        request.options.placeholder ?: "Select an item...",
                        style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color),
                    )
                }
                BasicTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    textStyle = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color),
                    cursorBrush = SolidColor(tokens.accent.color),
                    modifier = Modifier.fillMaxWidth().testTag("ext-quickpick-search"),
                )
            }
        }

        // Items list
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .padding(vertical = 4.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "No matching items",
                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                        )
                    }
                }
            } else {
                itemsIndexed(filtered, key = { i, item -> "$i:${item.label}" }) { idx, item ->
                    val isPicked = item.label in selectedSet
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (request.options.canPickMany) {
                                    if (isPicked) selectedSet.remove(item.label) else selectedSet.add(item.label)
                                } else {
                                    onResolve(listOf(item))
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("ext-quickpick-item-$idx"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (request.options.canPickMany) {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .cssBorder(if (isPicked) tokens.accent.color else tokens.border.color, RoundedCornerShape(3.dp))
                                        .background(if (isPicked) tokens.accent.color else tokens.bgSolid.color),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isPicked) {
                                        PpmIcon(ProductIcon.Check, 11.dp, tokens.accentFg.color)
                                    }
                                }
                            }
                            BasicText(
                                item.label,
                                style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val desc = item.description
                            if (!desc.isNullOrEmpty()) {
                                BasicText(
                                    desc,
                                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val detail = item.detail
                        if (!detail.isNullOrEmpty()) {
                            BasicText(
                                detail,
                                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (request.options.canPickMany) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .edge(Edge.Top, tokens.borderSoft.color)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    "${selectedSet.size} selected",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(tokens.radSm.dp))
                            .clickable { onResolve(null) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("ext-quickpick-cancel"),
                    ) {
                        BasicText("Cancel", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(tokens.radSm.dp))
                            .background(tokens.accent.color)
                            .clickable {
                                val chosen = request.items.filter { it.label in selectedSet }
                                onResolve(chosen)
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                            .testTag("ext-quickpick-ok"),
                    ) {
                        BasicText("OK", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accentFg.color))
                    }
                }
            }
        }
    }
}

@Composable
fun ExtensionInputBoxContent(
    request: InputBoxRequest,
    onResolve: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var value by remember(request.requestId) { mutableStateOf(request.options.value.orEmpty()) }
    val shape = RoundedCornerShape(tokens.rad.dp)
    val inputShape = RoundedCornerShape(tokens.radSm.dp)

    Column(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .cssBorder(tokens.border.color, shape)
            .background(tokens.panel.color)
            .padding(16.dp)
            .testTag("ext-inputbox-dialog"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val prompt = request.options.prompt
        if (!prompt.isNullOrEmpty()) {
            BasicText(
                prompt,
                style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(inputShape)
                .cssBorder(tokens.border.color, inputShape)
                .background(tokens.bgSolid.color)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            val placeholder = request.options.placeholder
            if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
                BasicText(
                    placeholder,
                    style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                visualTransformation = if (request.options.password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onResolve(value) }),
                textStyle = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color),
                cursorBrush = SolidColor(tokens.accent.color),
                modifier = Modifier.fillMaxWidth().testTag("ext-inputbox-input"),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(inputShape)
                    .clickable { onResolve(null) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("ext-inputbox-cancel"),
            ) {
                BasicText("Cancel", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
            }
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .clip(inputShape)
                    .background(tokens.accent.color)
                    .clickable { onResolve(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("ext-inputbox-ok"),
            ) {
                BasicText("OK", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accentFg.color))
            }
        }
    }
}
