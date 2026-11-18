package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.ListRow

/**
 * The row at the foot of a phone page that opens the rest of its section, one level down: the
 * tablet's whole pane, where what the phone page leaves out (Accounts' rotation, backup and token
 * tests; AI Provider's keys, budgets, MCP, teams and instructions) is set.
 */
@Composable
internal fun PhoneAllSettingsRow(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    PhoneSectionCard {
        ListRow(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.testTag(tag),
            trailingContent = { PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color) },
            onClick = onClick,
        )
    }
}
