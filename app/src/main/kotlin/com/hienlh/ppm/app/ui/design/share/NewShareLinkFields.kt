package com.hienlh.ppm.app.ui.design.share

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.NativeSelect
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.DesignShareLimits
import com.hienlh.ppm.client.DesignShareVersion
import com.hienlh.ppm.client.designShareVersions
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.state.design.share.CURRENT_SHARE_VERSION
import com.hienlh.ppm.state.design.share.ShareExpiryChoice
import com.hienlh.ppm.state.design.share.ShareExpiryPreset
import com.hienlh.ppm.state.design.share.maxCustomDate
import com.hienlh.ppm.state.design.share.minCustomDate
import com.hienlh.ppm.state.design.share.shareVersionLabel
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/*
 * The New link fields (`new-share-link-form.tsx`), the expiry chips (`share-expiry-picker.tsx`)
 * and the version picker (`share-version-picker.tsx`).
 */

@Composable
internal fun ShareFieldCaption(text: String) {
    BasicText(text, style = shareCaption.copy(color = LocalPpmTokens.current.text3.color, fontWeight = FontWeight.Medium))
}

@Composable
internal fun NewShareLinkFields(form: NewShareLinkForm, api: ApiClient?, events: GlobalEvents?, project: String, slug: String) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().testTag("share-new-link"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShareFieldCaption("Label (optional)")
            ShareTextInput(form.label, "For example: Client review", maxChars = DesignShareLimits.MAX_LABEL_CHARS, testTag = "share-new-label")
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShareFieldCaption("Expires after")
            ShareExpiryPicker(form.expiry, form::chooseExpiry, idPrefix = "share-new")
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShareFieldCaption("Version reviewers see")
            ShareVersionPicker(api, events, project, slug, form.version, onChange = { form.version = it })
        }
        form.error?.let { ShareText(it, Modifier.testTag("share-new-error"), color = tokens.error.color, style = shareCaption) }
    }
}

/** Expiry presets as one row of radio chips, plus a date for Custom (the end of that day, local time). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShareExpiryPicker(value: ShareExpiryChoice, onChange: (ShareExpiryChoice) -> Unit, idPrefix: String, error: String? = null) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in ShareExpiryPreset.entries) {
                ShareChoiceChip(preset.label, value.preset == preset, { onChange(value.copy(preset = preset)) }, testTag = "$idPrefix-expiry-${preset.name.lowercase()}")
            }
        }
        if (value.preset == ShareExpiryPreset.CUSTOM) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareText("Open until the end of", color = tokens.text3.color, style = shareCaption)
                ShareButton(
                    value.customDate.toString(),
                    onClick = {
                        val now = System.currentTimeMillis()
                        val zone = ZoneId.systemDefault()
                        val dialog = DatePickerDialog(
                            context,
                            { _, year, month, day -> onChange(value.copy(customDate = java.time.LocalDate.of(year, month + 1, day))) },
                            value.customDate.year,
                            value.customDate.monthValue - 1,
                            value.customDate.dayOfMonth,
                        )
                        dialog.datePicker.minDate = minCustomDate(now, zone).atStartOfDay(zone).toInstant().toEpochMilli()
                        dialog.datePicker.maxDate = maxCustomDate(now, zone).atStartOfDay(zone).toInstant().toEpochMilli()
                        dialog.show()
                    },
                    icon = ProductIcon.CalendarClock,
                    testTag = "$idPrefix-date",
                )
            }
        }
        error?.let { ShareText(it, color = tokens.error.color, style = shareCaption) }
    }
}

/**
 * Which version a new link pins: "Current version" (the server snapshots now) or one from the
 * design's history. Read while shown, and again on `design:history_changed`; a pinned version that
 * left the history (pruned) falls back to the current one.
 */
@Composable
internal fun ShareVersionPicker(api: ApiClient?, events: GlobalEvents?, project: String, slug: String, value: String, onChange: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    var items by remember(project, slug) { mutableStateOf<List<DesignShareVersion>?>(null) }
    var error by remember(project, slug) { mutableStateOf<String?>(null) }
    var reloads by remember(project, slug) { mutableIntStateOf(0) }
    LaunchedEffect(api, project, slug, reloads) {
        val client = api ?: return@LaunchedEffect
        try {
            items = client.designShareVersions(project, slug)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load the history"
        }
    }
    DisposableEffect(events, project, slug) {
        val off = events?.onDesignHistoryChanged { p, s -> if (p == project && s == slug) reloads++ }
        onDispose { off?.invoke() }
    }
    LaunchedEffect(items, value) {
        val list = items ?: return@LaunchedEffect
        if (value != CURRENT_SHARE_VERSION && list.none { it.id == value }) onChange(CURRENT_SHARE_VERSION)
    }
    val options = remember(items) {
        listOf(SelectOption(CURRENT_SHARE_VERSION, "Current version (saved now)")) +
            items.orEmpty().map { SelectOption(it.id, shareVersionLabel(it)) }
    }
    Column(Modifier.testTag("share-version-picker"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NativeSelect(value, options, onChange)
        error?.let { ShareText(it, color = tokens.error.color, style = shareCaption) }
    }
}
