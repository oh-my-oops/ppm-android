package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SavedApprovalRule
import com.hienlh.ppm.client.approvalRules
import com.hienlh.ppm.client.removeApprovalRule
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What "Always in this project" on a Claude approval card allowed, by project, each removable
 * (`SavedApprovalsSection` in Settings → AI Provider). Hidden while there is none, and on a server
 * that keeps none (its 404).
 */
@Composable
internal fun SavedApprovals(api: ApiClient?) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var rules by remember(api) { mutableStateOf<List<SavedApprovalRule>>(emptyList()) }
    suspend fun load() {
        try {
            rules = api?.approvalRules() ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(api) { load() }
    if (rules.isEmpty()) return
    val text = ppmText(12f, lineHeight = 16f)
    CssColumn(Modifier.fillMaxWidth(), gap = 6.dp) {
        FieldLabel("Always allowed in a project (${rules.size})")
        // `text-[11px] text-text-subtle`.
        BasicText(
            "Saved from Claude approval cards. These calls run without asking in every chat of that project.",
            style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color),
        )
        for (rule in rules) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(tokens.panel2.color).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(rule.title, style = text.copy(color = tokens.text.color, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(rule.projectPath, style = text.copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val hover = rememberHover()
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .semantics { contentDescription = "Remove " + rule.label }
                        .ppmClickable(pressedColor = Color.Transparent, source = hover.source) {
                            scope.launch {
                                try {
                                    api?.removeApprovalRule(rule.projectPath, rule.key) ?: return@launch
                                    rules = rules.filterNot { it.projectPath == rule.projectPath && it.key == rule.key }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    load()
                                }
                            }
                        }
                        .padding(4.dp),
                ) {
                    PpmIcon(ProductIcon.Trash2, 14.dp, tokens.text3.mix(tokens.error, hover.lit.toDouble()).color)
                }
            }
        }
    }
}
