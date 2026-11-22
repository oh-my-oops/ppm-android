package com.hienlh.ppm.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * What the app adds to a `value`-based text field drawn by a module below it (a chat card's "Other" answer, a
 * password on an approval card), so the app knows that field holds the focus: a tap in it keeps the keyboard
 * instead of being taken for a tap on nothing, and the page pans the field above the keyboard. Every field
 * that applies it gets a node of its own. Nothing outside the app.
 */
val LocalTextFieldFocus = staticCompositionLocalOf<Modifier> { Modifier }
